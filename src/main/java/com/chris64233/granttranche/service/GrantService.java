package com.chris64233.granttranche.service;

import com.chris64233.granttranche.domain.AdjustmentStatus;
import com.chris64233.granttranche.domain.BudgetAdjustment;
import com.chris64233.granttranche.domain.ComplianceSuspension;
import com.chris64233.granttranche.domain.FundRecord;
import com.chris64233.granttranche.domain.FundRecordType;
import com.chris64233.granttranche.domain.GrantProject;
import com.chris64233.granttranche.domain.GrantTranche;
import com.chris64233.granttranche.domain.TrancheStatus;
import com.chris64233.granttranche.dto.AdjustmentView;
import com.chris64233.granttranche.dto.CreateProjectRequest;
import com.chris64233.granttranche.dto.FundRecordView;
import com.chris64233.granttranche.dto.ProjectBalance;
import com.chris64233.granttranche.dto.SuspensionView;
import com.chris64233.granttranche.dto.TrancheView;
import com.chris64233.granttranche.repo.BudgetAdjustmentRepository;
import com.chris64233.granttranche.repo.ComplianceSuspensionRepository;
import com.chris64233.granttranche.repo.FundRecordRepository;
import com.chris64233.granttranche.repo.GrantProjectRepository;
import com.chris64233.granttranche.repo.GrantTrancheRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 分期拨款核心业务服务。
 *
 * <p><b>并发模型：</b>所有改变项目额度或一致性状态的操作（验收、合规暂停/解除、拨款批准、
 * 支付、撤销、追回）都在事务内以悲观写锁锁定项目行后执行，因此同一项目的并发决定被严格串行化，
 * 只会形成一种一致结果；累计拨款额在锁内原子增减，任何并发组合都无法突破批准总额。
 *
 * <p><b>幂等：</b>拨款与追回以调用方提供的业务号为幂等键（数据库唯一约束兜底），
 * 重复提交返回原始记录，不重复占用额度。
 */
@Service
public class GrantService {

    private final GrantProjectRepository projectRepository;
    private final GrantTrancheRepository trancheRepository;
    private final FundRecordRepository fundRecordRepository;
    private final ComplianceSuspensionRepository suspensionRepository;
    private final BudgetAdjustmentRepository adjustmentRepository;
    /** 独立提交事务：当前业务事务因校验失败即将回滚时，用它把方案作废决定单独落库留痕。 */
    private final TransactionTemplate requiresNewTransactionTemplate;
    private final Clock clock;

    public GrantService(GrantProjectRepository projectRepository,
                        GrantTrancheRepository trancheRepository,
                        FundRecordRepository fundRecordRepository,
                        ComplianceSuspensionRepository suspensionRepository,
                        BudgetAdjustmentRepository adjustmentRepository,
                        org.springframework.transaction.PlatformTransactionManager transactionManager,
                        Clock clock) {
        this.projectRepository = projectRepository;
        this.trancheRepository = trancheRepository;
        this.fundRecordRepository = fundRecordRepository;
        this.suspensionRepository = suspensionRepository;
        this.adjustmentRepository = adjustmentRepository;
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    private Instant now() {
        return clock.instant();
    }

    // ------------------------------------------------------------------
    // 项目与期次建立
    // ------------------------------------------------------------------

    /** 创建项目及全部期次。期次号须为从 1 开始的连续唯一序列，期次金额合计不得超过批准总额。 */
    @Transactional
    public GrantProject createProject(CreateProjectRequest request) {
        List<CreateProjectRequest.TrancheSpec> specs = request.tranches();
        int n = specs.size();

        Set<Integer> seen = new HashSet<>();
        Set<Integer> expected = new HashSet<>();
        for (int i = 1; i <= n; i++) {
            expected.add(i);
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (CreateProjectRequest.TrancheSpec spec : specs) {
            if (spec.sequenceNo() == null || spec.sequenceNo() < 1 || spec.sequenceNo() > n
                    || !seen.add(spec.sequenceNo())) {
                throw new BusinessRuleException("期次号必须为从 1 开始、不重复的连续序列: " + spec.sequenceNo());
            }
            sum = sum.add(spec.plannedAmount());
        }
        if (sum.compareTo(request.approvedAmount()) > 0) {
            throw new BusinessRuleException(
                    "全部期次计划金额合计 %s 超过项目批准金额 %s".formatted(sum, request.approvedAmount()));
        }
        if (projectRepository.findByProjectCode(request.projectCode()).isPresent()) {
            throw new BusinessRuleException("项目编号已存在: " + request.projectCode());
        }

        GrantProject project = projectRepository.save(
                new GrantProject(request.projectCode(), request.title(), request.approvedAmount()));
        List<CreateProjectRequest.TrancheSpec> ordered = specs.stream()
                .sorted(java.util.Comparator.comparing(CreateProjectRequest.TrancheSpec::sequenceNo))
                .toList();
        for (CreateProjectRequest.TrancheSpec spec : ordered) {
            trancheRepository.save(new GrantTranche(project, spec.sequenceNo(), spec.plannedAmount(),
                    spec.requiredDeliverable(), spec.budgetConditions()));
        }
        return project;
    }

    // ------------------------------------------------------------------
    // 成果提交与验收
    // ------------------------------------------------------------------

    /** 提交期次成果，进入待验收。已计划或验收被退回的期次可提交。 */
    @Transactional
    public GrantTranche submitDeliverable(Long trancheId, String evidence, String submittedBy) {
        GrantTranche tranche = getTranche(trancheId);
        lockProject(tranche.getProject().getId());
        if (tranche.getStatus() != TrancheStatus.PLANNED
                && tranche.getStatus() != TrancheStatus.REJECTED) {
            throw new BusinessRuleException("当前期次状态 %s 不允许提交成果".formatted(tranche.getStatus()));
        }
        tranche.setDeliverableEvidence(evidence);
        tranche.setSubmittedBy(submittedBy);
        tranche.setSubmittedAt(now());
        tranche.setStatus(TrancheStatus.SUBMITTED);
        return tranche;
    }

    /**
     * 验收决定。通过后期次进入 ACCEPTED；不通过进入 REJECTED（须填写原因），可重新提交。
     * 与合规暂停、拨款批准在同一项目行锁上串行执行。
     */
    @Transactional
    public GrantTranche review(Long trancheId, boolean approved, String reviewedBy, String comment) {
        GrantTranche tranche = getTranche(trancheId);
        lockProject(tranche.getProject().getId());
        if (tranche.getStatus() != TrancheStatus.SUBMITTED) {
            throw new BusinessRuleException("仅待验收(SUBMITTED)期次可做验收决定，当前状态: " + tranche.getStatus());
        }
        if (!approved && (comment == null || comment.isBlank())) {
            throw new BusinessRuleException("验收不通过必须填写原因");
        }
        tranche.setReviewedBy(reviewedBy);
        tranche.setReviewComment(comment);
        tranche.setReviewedAt(now());
        tranche.setStatus(approved ? TrancheStatus.ACCEPTED : TrancheStatus.REJECTED);
        return tranche;
    }

    // ------------------------------------------------------------------
    // 合规暂停
    // ------------------------------------------------------------------

    /**
     * 发起合规暂停。存在活动暂停期间，任何期次拨款都不得批准；预算调整也不得确认。
     *
     * <p>暂停在同一事务内把项目内所有待确认(PROPOSED)的预算调整方案作 INVALIDATED 处理：
     * 合规暂停意味着申请依据已变化，恢复后必须重新检查期次状态与余额并重新申请，
     * 旧方案不能直接沿用。项目版本同时强制递增，与并发的拨款确认/调整确认只可能有一方成功。
     */
    @Transactional
    public ComplianceSuspension suspend(Long projectId, String reason, String raisedBy) {
        GrantProject project = lockProject(projectId);
        if (suspensionRepository.existsByProjectIdAndActiveTrue(projectId)) {
            throw new BusinessRuleException("项目已存在活动中的合规暂停");
        }
        List<BudgetAdjustment> pending = adjustmentRepository
                .findByProjectIdAndStatus(projectId, AdjustmentStatus.PROPOSED);
        for (BudgetAdjustment adjustment : pending) {
            adjustment.markInvalidated("合规暂停（%s），旧调整方案作废，恢复后须重新申请".formatted(reason), now());
            adjustmentRepository.save(adjustment);
        }
        // 暂停本身不改金额字段，显式推进项目版本，使并发、依据旧版本的拨款/调整确认落败。
        if (projectRepository.bumpVersionIfMatches(projectId, project.getVersion()) != 1) {
            throw new BusinessRuleException("项目状态已被并发事务改变，合规暂停失败，请重试");
        }
        return suspensionRepository.save(new ComplianceSuspension(project, reason, raisedBy, now()));
    }

    /** 解除合规暂停，保留解除人、原因与时间。 */
    @Transactional
    public ComplianceSuspension liftSuspension(Long suspensionId, String liftedBy, String reason) {
        ComplianceSuspension suspension = suspensionRepository.findById(suspensionId)
                .orElseThrow(() -> new NotFoundException("合规暂停记录不存在: " + suspensionId));
        lockProject(suspension.getProject().getId());
        if (!suspension.isActive()) {
            throw new BusinessRuleException("该合规暂停已解除，不能重复解除");
        }
        suspension.lift(liftedBy, reason, now());
        return suspension;
    }

    // ------------------------------------------------------------------
    // 拨款批准（核心）
    // ------------------------------------------------------------------

    /**
     * 批准期次拨款。必须同时满足：
     * <ol>
     *   <li>期次成果已验收通过（ACCEPTED）；</li>
     *   <li>项目没有活动中的合规暂停；</li>
     *   <li>所有前置期次已有生效拨款（未撤销），不得跳期；</li>
     *   <li>累计拨款 + 本期金额不超过项目批准总额；</li>
     *   <li>业务号未被用于其他拨款/追回。</li>
     * </ol>
     * 批准在同一事务、同一项目行锁内原子增加累计拨款并生成资金记录。
     * 业务号重复提交时原样返回既有记录，保证幂等。
     */
    @Transactional
    public FundRecord disburse(Long trancheId, String businessNo, String approvedBy, String reason) {
        FundRecord existing = fundRecordRepository.findByBusinessNo(businessNo).orElse(null);
        if (existing != null) {
            return sameDisbursementOrConflict(existing, trancheId, businessNo);
        }

        Long projectId = trancheRepository.findProjectIdById(trancheId)
                .orElseThrow(() -> new NotFoundException("拨款期次不存在: " + trancheId));
        // 加锁前快照项目版本：与并发的预算调整确认通过项目版本裁决，持锁后版本若已变则本方落败（409）。
        Long versionBeforeLock = projectRepository.findVersionById(projectId)
                .orElseThrow(() -> new NotFoundException("项目不存在: " + projectId));
        GrantProject project = lockProject(projectId);

        // 锁内先做幂等复查：同业务号的重复提交即使在锁等待期间项目已变化，也原样返回同一笔，
        // 不能被版本守卫误判为冲突。
        existing = fundRecordRepository.findByBusinessNo(businessNo).orElse(null);
        if (existing != null) {
            return sameDisbursementOrConflict(existing, trancheId, businessNo);
        }

        // 全新拨款：持锁后版本若已变，说明与并发的调整确认/拨款竞争，本方落败。
        assertVersionUnchangedSinceLock(projectId, versionBeforeLock, "拨款确认");
        // 期次在加锁通过守卫之后才加载：持项目行锁期间读到的状态与计划金额必然是最新提交值
        //（例如已由预算调整改为新金额，拨款按调整后金额占用额度），无需 refresh。
        GrantTranche tranche = getTranche(trancheId);

        if (tranche.getStatus() != TrancheStatus.ACCEPTED) {
            throw new BusinessRuleException("仅验收通过(ACCEPTED)的期次可批准拨款，当前状态: " + tranche.getStatus());
        }
        if (suspensionRepository.existsByProjectIdAndActiveTrue(project.getId())) {
            throw new BusinessRuleException("项目存在活动中的合规暂停，不得批准拨款");
        }
        assertPriorTranchesDisbursed(tranche);

        BigDecimal planned = tranche.getPlannedAmount();
        BigDecimal newTotal = project.getTotalDisbursed().add(planned);
        if (newTotal.compareTo(project.getApprovedAmount()) > 0) {
            throw new BusinessRuleException(
                    "拨款后累计 %s 将超过批准总额 %s（剩余额度 %s）".formatted(
                            newTotal, project.getApprovedAmount(),
                            project.getApprovedAmount().subtract(project.getTotalDisbursed())));
        }

        project.setTotalDisbursed(newTotal);
        FundRecord record = fundRecordRepository.save(
                FundRecord.disbursement(businessNo, project, tranche, planned, approvedBy, reason, now()));
        tranche.setStatus(TrancheStatus.DISBURSED);
        return record;
    }

    /** 幂等命中：业务号对应的是同一期次的拨款则返回；业务号复用到其他对象则冲突。 */
    private FundRecord sameDisbursementOrConflict(FundRecord existing, Long trancheId, String businessNo) {
        boolean sameTranche = existing.getRecordType() == FundRecordType.DISBURSEMENT
                && existing.getTranche() != null
                && trancheId.equals(existing.getTranche().getId());
        if (!sameTranche) {
            throw new BusinessRuleException("业务号已被其他资金记录占用: " + businessNo);
        }
        return existing;
    }

    /** 前置期次必须都有生效（未撤销）拨款，防止并发批准绕过期次顺序。 */
    private void assertPriorTranchesDisbursed(GrantTranche tranche) {
        List<GrantTranche> all = trancheRepository
                .findByProjectIdOrderBySequenceNoAsc(tranche.getProject().getId());
        for (GrantTranche other : all) {
            if (other.getSequenceNo() >= tranche.getSequenceNo()) {
                break;
            }
            if (other.getStatus() != TrancheStatus.DISBURSED && other.getStatus() != TrancheStatus.PAID) {
                throw new BusinessRuleException(
                        "前置期次 %d 尚未完成拨款，不能批准期次 %d".formatted(
                                other.getSequenceNo(), tranche.getSequenceNo()));
            }
        }
    }

    // ------------------------------------------------------------------
    // 支付 / 撤销 / 追回
    // ------------------------------------------------------------------

    /** 确认拨款已支付。仅未支付(UNPAID)拨款可支付。 */
    @Transactional
    public FundRecord markPaid(Long fundRecordId, String paidBy) {
        FundRecord record = getFundRecord(fundRecordId);
        lockProject(record.getProject().getId());
        if (record.getStatus() != com.chris64233.granttranche.domain.FundRecordStatus.UNPAID) {
            throw new BusinessRuleException("仅未支付(UNPAID)拨款可确认支付，当前状态: " + record.getStatus());
        }
        record.markPaid(paidBy, now());
        GrantTranche tranche = record.getTranche();
        if (tranche != null) {
            tranche.setStatus(TrancheStatus.PAID);
        }
        return record;
    }

    /**
     * 撤销尚未支付的拨款：资金记录置为 REVOKED 并保留（恢复额度），累计拨款原子扣减，
     * 期次回到 ACCEPTED 可重新批准。已支付拨款不得撤销，只能走追回。
     */
    @Transactional
    public FundRecord revoke(Long fundRecordId, String revokedBy, String reason) {
        FundRecord record = getFundRecord(fundRecordId);
        GrantProject project = lockProject(record.getProject().getId());
        if (record.getRecordType() != FundRecordType.DISBURSEMENT) {
            throw new BusinessRuleException("仅拨款记录可撤销");
        }
        if (record.getStatus() != com.chris64233.granttranche.domain.FundRecordStatus.UNPAID) {
            throw new BusinessRuleException("仅未支付(UNPAID)拨款可撤销，已支付拨款只能通过追回减少净拨款，当前状态: "
                    + record.getStatus());
        }
        record.markRevoked(revokedBy, reason, now());
        project.setTotalDisbursed(project.getTotalDisbursed().subtract(record.getAmount()));
        GrantTranche tranche = record.getTranche();
        if (tranche != null && tranche.getStatus() == TrancheStatus.DISBURSED) {
            tranche.setStatus(TrancheStatus.ACCEPTED);
        }
        return record;
    }

    /**
     * 对已支付拨款登记追回：生成独立的 RECOVERY 资金记录，原子增加项目追回总额，
     * 原拨款记录保持 PAID 不变（仅登记其上的累计追回额）。业务号保证幂等；
     * 累计追回不得超过原拨款金额。
     */
    @Transactional
    public FundRecord recover(Long originalDisbursementId, String businessNo, BigDecimal amount,
                              String recoveredBy, String reason) {
        FundRecord idempotent = fundRecordRepository.findByBusinessNo(businessNo).orElse(null);
        if (idempotent != null) {
            if (idempotent.getRecordType() != FundRecordType.RECOVERY
                    || !originalDisbursementId.equals(idempotent.getOriginalDisbursement().getId())) {
                throw new BusinessRuleException("业务号已被其他资金记录占用: " + businessNo);
            }
            return idempotent;
        }

        FundRecord original = getFundRecord(originalDisbursementId);
        GrantProject project = lockProject(original.getProject().getId());

        idempotent = fundRecordRepository.findByBusinessNo(businessNo).orElse(null);
        if (idempotent != null) {
            if (idempotent.getRecordType() != FundRecordType.RECOVERY
                    || !originalDisbursementId.equals(idempotent.getOriginalDisbursement().getId())) {
                throw new BusinessRuleException("业务号已被其他资金记录占用: " + businessNo);
            }
            return idempotent;
        }

        if (original.getRecordType() != FundRecordType.DISBURSEMENT) {
            throw new BusinessRuleException("只能对拨款记录登记追回");
        }
        if (original.getStatus() != com.chris64233.granttranche.domain.FundRecordStatus.PAID) {
            throw new BusinessRuleException("只能追回已支付(PAID)拨款；未支付拨款请使用撤销，当前状态: "
                    + original.getStatus());
        }
        BigDecimal remaining = original.getAmount().subtract(original.getRecoveredAmount());
        if (amount.compareTo(remaining) > 0) {
            throw new BusinessRuleException(
                    "追回金额 %s 超过该拨款可追回净额 %s".formatted(amount, remaining));
        }

        original.setRecoveredAmount(original.getRecoveredAmount().add(amount));
        project.setTotalRecovered(project.getTotalRecovered().add(amount));
        return fundRecordRepository.save(
                FundRecord.recovery(businessNo, project, original, amount, recoveredBy, reason, now()));
    }

    // ------------------------------------------------------------------
    // 未拨付预算的期次间调整
    // ------------------------------------------------------------------

    /** 尚未拨付的期次状态：只有这些期次的计划金额允许被调整。 */
    private static final Set<TrancheStatus> UNDISBURSED =
            EnumSet.of(TrancheStatus.PLANNED, TrancheStatus.SUBMITTED,
                    TrancheStatus.REJECTED, TrancheStatus.ACCEPTED);

    /**
     * 申请未拨付预算在两个未来期次之间调整（只登记方案与快照，不改变任何金额）。
     *
     * <p>规则：
     * <ul>
     *   <li>调出与调入期次必须属于同一项目且为期次不同；</li>
     *   <li>两个期次都必须是尚未拨付状态——已支付、正在拨付（已批准未支付）或其拨款已用于追回
     *       的期次一律不允许调出/调入，相关金额保留在原期次与原用途；</li>
     *   <li>调整金额必须为正，且不得超过调出期次当前计划金额（项目尚未使用的部分）；</li>
     *   <li>调整为等额转移，项目批准总额、累计拨款均不变。</li>
     * </ul>
     * 业务号重复提交原样返回同一方案，保证幂等；业务号被资金记录占用返回冲突。
     */
    @Transactional
    public BudgetAdjustment requestAdjustment(Long fromTrancheId, Long toTrancheId,
                                              BigDecimal amount, String businessNo,
                                              String reason, String requestedBy) {
        BudgetAdjustment existing = adjustmentRepository.findByBusinessNo(businessNo).orElse(null);
        if (existing != null) {
            return sameAdjustmentOrConflict(existing, fromTrancheId, toTrancheId, amount, businessNo);
        }
        if (fundRecordRepository.findByBusinessNo(businessNo).isPresent()) {
            throw new BusinessRuleException("业务号已被其他资金记录占用: " + businessNo);
        }

        GrantTranche from = getTranche(fromTrancheId);
        GrantTranche to = getTranche(toTrancheId);
        GrantProject project = lockProject(from.getProject().getId());

        // 锁内复查业务号，防止与并发申请竞争。
        existing = adjustmentRepository.findByBusinessNo(businessNo).orElse(null);
        if (existing != null) {
            return sameAdjustmentOrConflict(existing, fromTrancheId, toTrancheId, amount, businessNo);
        }
        if (fundRecordRepository.findByBusinessNo(businessNo).isPresent()) {
            throw new BusinessRuleException("业务号已被其他资金记录占用: " + businessNo);
        }

        if (!from.getProject().getId().equals(to.getProject().getId())) {
            throw new BusinessRuleException("调出与调入期次必须属于同一个项目");
        }
        if (from.getId().equals(to.getId())) {
            throw new BusinessRuleException("调出期次与调入期次不能相同");
        }
        assertUndisbursed(from, "调出");
        assertUndisbursed(to, "调入");
        if (amount == null || amount.signum() <= 0) {
            throw new BusinessRuleException("调整金额必须为正数");
        }
        if (amount.compareTo(from.getPlannedAmount()) > 0) {
            throw new BusinessRuleException(
                    "调整金额 %s 超过调出期次尚未拨付的计划金额 %s".formatted(
                            amount, from.getPlannedAmount()));
        }

        return initializeForView(adjustmentRepository.save(new BudgetAdjustment(
                businessNo, project, from, to, amount, reason, requestedBy, now(), project.getVersion())));
    }

    /** 幂等命中：业务号对应同一调整（期次与金额一致）则返回，否则冲突。 */
    private BudgetAdjustment sameAdjustmentOrConflict(BudgetAdjustment existing, Long fromTrancheId,
                                                      Long toTrancheId, BigDecimal amount, String businessNo) {
        boolean same = fromTrancheId.equals(existing.getFromTranche().getId())
                && toTrancheId.equals(existing.getToTranche().getId())
                && amount.compareTo(existing.getAmount()) == 0;
        if (!same) {
            throw new BusinessRuleException("业务号已被其他调整记录占用: " + businessNo);
        }
        return initializeForView(existing);
    }

    private void assertUndisbursed(GrantTranche tranche, String role) {
        if (!UNDISBURSED.contains(tranche.getStatus())) {
            throw new BusinessRuleException(
                    "%s期次 %d 已进入拨付/支付环节（%s），其金额不得调整".formatted(
                            role, tranche.getSequenceNo(), tranche.getStatus()));
        }
    }

    /**
     * 确认调整。确认时（而非申请时）对方案依据做<b>全量重新检查</b>：
     * <ol>
     *   <li>项目没有活动中的合规暂停——暂停期间不得确认；</li>
     *   <li>方案未被作废（暂停发生时待确认方案会被置为 INVALIDATED）；</li>
     *   <li>两个期次仍为未拨付状态——申请后任一期次被拨款，旧方案立即失效；</li>
     *   <li>两期次当前计划金额仍等于申请时快照（未被其他已确认调整改变），且调整额仍不超过调出余额。</li>
     * </ol>
     *
     * <p>通过检查后在同一事务、同一项目行锁内：项目版本 +1、调出期次减额、调入期次等额增额、
     * 方案置 CONFIRMED 并记录批准人与调整前后金额；任一步失败整体回滚，不产生半成功状态。
     *
     * <p><b>与拨款确认并发：</b>进入时先快照项目版本、再申请项目行悲观锁，获锁后比对版本。
     * 若重叠的拨款（或支付/撤销/追回/暂停/另一调整确认）已先提交并推进版本，则本确认判定为
     * 并发竞争落败方，收到业务冲突（409）；本确认提交时自身也会推进版本，使后提交的拨款落败。
     * 因此拨款确认与预算调整真正并发时恰好只允许一个成功；两者不重叠（先后发生）时互不影响。
     *
     * <p>幂等：重复确认已确认方案原样返回，不重复转移金额。
     */
    @Transactional
    public BudgetAdjustment confirmAdjustment(Long adjustmentId, String confirmedBy) {
        Long projectId = adjustmentRepository.findProjectIdById(adjustmentId)
                .orElseThrow(() -> new NotFoundException("预算调整记录不存在: " + adjustmentId));
        // 加锁前快照项目版本：与持锁后版本比对，裁决与拨款确认的并发竞争。
        Long versionBeforeLock = projectRepository.findVersionById(projectId)
                .orElseThrow(() -> new NotFoundException("项目不存在: " + projectId));
        GrantProject project = lockProject(projectId);

        // 方案在持锁之后才加载：锁等待期间若被暂停作废或由另一请求确认，这里读到的都是最新状态。
        BudgetAdjustment adjustment = getAdjustment(adjustmentId);

        // 幂等复查优先于版本守卫：重复确认已完成方案直接返回，已作废方案直接报错，
        // 不被锁等待期间其他事务的版本推进误判为并发冲突。
        if (adjustment.getStatus() == AdjustmentStatus.CONFIRMED) {
            return initializeForView(adjustment);
        }
        if (adjustment.getStatus() == AdjustmentStatus.INVALIDATED) {
            throw new BusinessRuleException("调整方案已作废（%s），不能确认，请重新申请"
                    .formatted(adjustment.getInvalidatedReason()));
        }

        // 待确认方案：持锁后版本若已变，说明与并发的拨款/暂停/另一调整竞争，本方落败。
        assertVersionUnchangedSinceLock(projectId, versionBeforeLock, "预算调整确认");

        // 期次同样在持锁后经懒加载读取，状态与计划金额为最新提交值，无需 refresh。
        GrantTranche from = adjustment.getFromTranche();
        GrantTranche to = adjustment.getToTranche();

        String invalidReason = revalidate(from, to, adjustment);
        if (invalidReason != null) {
            // 作废必须落库后再向调用方报错，使用独立事务提交，避免随当前失败事务一起回滚。
            invalidateInSeparateTransaction(adjustmentId, invalidReason);
            throw new BusinessRuleException(invalidReason + "，旧调整方案已作废，请重新申请");
        }

        BigDecimal fromAfter = from.getPlannedAmount().subtract(adjustment.getAmount());
        BigDecimal toAfter = to.getPlannedAmount().add(adjustment.getAmount());
        from.setPlannedAmount(fromAfter);
        to.setPlannedAmount(toAfter);
        adjustment.markConfirmed(confirmedBy, fromAfter, toAfter, now());

        // 两个期次与方案先落库；@Modifying 默认 flushAutomatically，版本递增语句执行前会先刷这些更新，
        // 与最后的 UPDATE 处于同一事务，任一失败整体回滚。持行锁条件更新正常返回 1。
        trancheRepository.save(from);
        trancheRepository.save(to);
        adjustmentRepository.save(adjustment);
        if (projectRepository.bumpVersionIfMatches(projectId, project.getVersion()) != 1) {
            throw new BusinessRuleException("项目状态已被并发事务改变，预算调整确认失败，请重试");
        }
        return initializeForView(adjustment);
    }

    /** 重新检查暂停、期次状态与余额快照；返回 null 表示全部通过，否则返回失效原因。 */
    private String revalidate(GrantTranche from, GrantTranche to, BudgetAdjustment adjustment) {
        if (suspensionRepository.existsByProjectIdAndActiveTrue(adjustment.getProject().getId())) {
            return "项目存在活动中的合规暂停";
        }
        if (!UNDISBURSED.contains(from.getStatus())) {
            return "调出期次 %d 已进入拨付/支付环节（%s）".formatted(from.getSequenceNo(), from.getStatus());
        }
        if (!UNDISBURSED.contains(to.getStatus())) {
            return "调入期次 %d 已进入拨付/支付环节（%s）".formatted(to.getSequenceNo(), to.getStatus());
        }
        if (from.getPlannedAmount().compareTo(adjustment.getFromAmountBefore()) != 0
                || to.getPlannedAmount().compareTo(adjustment.getToAmountBefore()) != 0) {
            return "期次计划金额自申请后已发生变化";
        }
        if (adjustment.getAmount().compareTo(from.getPlannedAmount()) > 0) {
            return "调整金额超过调出期次当前尚未拨付的余额";
        }
        return null;
    }

    /**
     * 加锁后版本守卫：持锁后版本若与加锁前快照不同，说明有重叠事务已先提交，
     * 当前操作为并发竞争落败方，抛出业务冲突（由调用方作为 409 返回），不产生半成功状态。
     */
    private void assertVersionUnchangedSinceLock(Long projectId, Long versionBeforeLock, String operation) {
        Long current = projectRepository.findVersionById(projectId)
                .orElseThrow(() -> new NotFoundException("项目不存在: " + projectId));
        if (!current.equals(versionBeforeLock)) {
            throw new BusinessRuleException(
                    "项目在%s等待期间已被并发事务修改（版本 %d → %d），本次%s失败，请重试"
                            .formatted(operation, versionBeforeLock, current, operation));
        }
    }

    /** 在独立事务中把方案置为 INVALIDATED 并提交，使其在当前业务事务回滚后仍然留痕。 */
    private void invalidateInSeparateTransaction(Long adjustmentId, String reason) {
        requiresNewTransactionTemplate.executeWithoutResult(status -> {
            BudgetAdjustment fresh = adjustmentRepository.findById(adjustmentId).orElse(null);
            if (fresh != null && fresh.getStatus() == AdjustmentStatus.PROPOSED) {
                fresh.markInvalidated(reason, now());
            }
        });
    }

    /** 项目的全部预算调整记录，新的在前。 */
    @Transactional(readOnly = true)
    public List<AdjustmentView> listAdjustments(Long projectId) {
        getProject(projectId);
        return adjustmentRepository.findByProjectIdOrderByIdDesc(projectId).stream()
                .map(GrantService::toAdjustmentView)
                .toList();
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    /** 项目余额：批准额、已承诺、已追回、净支付、可拨余额。 */
    @Transactional(readOnly = true)
    public ProjectBalance getBalance(Long projectId) {
        GrantProject project = getProject(projectId);
        BigDecimal paid = BigDecimal.ZERO;
        for (FundRecord r : fundRecordRepository.findByProjectIdOrderByIdAsc(projectId)) {
            if (r.getRecordType() == FundRecordType.DISBURSEMENT
                    && r.getStatus() == com.chris64233.granttranche.domain.FundRecordStatus.PAID) {
                paid = paid.add(r.getAmount());
            }
        }
        BigDecimal committed = project.getTotalDisbursed();
        BigDecimal recovered = project.getTotalRecovered();
        return new ProjectBalance(
                project.getApprovedAmount(),
                committed,
                recovered,
                paid.subtract(recovered),
                project.getApprovedAmount().subtract(committed));
    }

    @Transactional(readOnly = true)
    public List<TrancheView> listTranches(Long projectId) {
        getProject(projectId);
        return trancheRepository.findByProjectIdOrderBySequenceNoAsc(projectId).stream()
                .map(GrantService::toTrancheView)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<SuspensionView> listSuspensions(Long projectId) {
        getProject(projectId);
        return suspensionRepository.findByProjectIdOrderByRaisedAtDesc(projectId).stream()
                .map(s -> new SuspensionView(s.getId(), s.isActive(), s.getReason(), s.getRaisedBy(),
                        s.getRaisedAt(), s.getLiftedReason(), s.getLiftedBy(), s.getLiftedAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<FundRecordView> listFundRecords(Long projectId) {
        getProject(projectId);
        return fundRecordRepository.findByProjectIdOrderByIdAsc(projectId).stream()
                .map(GrantService::toFundRecordView)
                .toList();
    }

    private GrantProject lockProject(Long projectId) {
        return projectRepository.findByIdForUpdate(projectId)
                .orElseThrow(() -> new NotFoundException("项目不存在: " + projectId));
    }

    private GrantProject getProject(Long projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new NotFoundException("项目不存在: " + projectId));
    }

    private GrantTranche getTranche(Long trancheId) {
        return trancheRepository.findById(trancheId)
                .orElseThrow(() -> new NotFoundException("拨款期次不存在: " + trancheId));
    }

    private FundRecord getFundRecord(Long id) {
        return fundRecordRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("资金记录不存在: " + id));
    }

    private BudgetAdjustment getAdjustment(Long id) {
        return adjustmentRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("预算调整记录不存在: " + id));
    }

    /**
     * 在事务内初始化调整记录的懒加载关联（项目、调出/调入期次）。
     * 应用关闭了 open-in-view，Controller 在事务提交后才把实体转成视图，
     * 因此所有返回路径（含幂等早返回）都必须在事务内把这些关联加载好，避免 LazyInitializationException。
     */
    private BudgetAdjustment initializeForView(BudgetAdjustment adjustment) {
        org.hibernate.Hibernate.initialize(adjustment.getProject());
        org.hibernate.Hibernate.initialize(adjustment.getFromTranche());
        org.hibernate.Hibernate.initialize(adjustment.getToTranche());
        return adjustment;
    }

    public static TrancheView toTrancheView(GrantTranche t) {
        return new TrancheView(t.getId(), t.getSequenceNo(), t.getPlannedAmount(),
                t.getRequiredDeliverable(), t.getBudgetConditions(), t.getStatus(),
                t.getDeliverableEvidence(), t.getSubmittedAt(), t.getSubmittedBy(),
                t.getReviewComment(), t.getReviewedAt(), t.getReviewedBy());
    }

    public static FundRecordView toFundRecordView(FundRecord r) {
        Long trancheId = r.getTranche() == null ? null : r.getTranche().getId();
        Long originalId = r.getOriginalDisbursement() == null ? null : r.getOriginalDisbursement().getId();
        return new FundRecordView(r.getId(), r.getBusinessNo(), r.getRecordType(), trancheId, originalId,
                r.getAmount(), r.getStatus(), r.getRecoveredAmount(), r.getCreatedBy(), r.getCreatedAt(),
                r.getReason(), r.getPaidAt(), r.getPaidBy(), r.getRevokedAt(), r.getRevokedBy(),
                r.getRevokeReason());
    }

    public static AdjustmentView toAdjustmentView(BudgetAdjustment a) {
        return new AdjustmentView(a.getId(), a.getBusinessNo(), a.getProject().getId(),
                a.getFromTranche().getId(), a.getFromTranche().getSequenceNo(),
                a.getToTranche().getId(), a.getToTranche().getSequenceNo(),
                a.getAmount(), a.getStatus(), a.getReason(), a.getRequestedBy(), a.getRequestedAt(),
                a.getConfirmedBy(), a.getConfirmedAt(),
                a.getFromAmountBefore(), a.getFromAmountAfter(),
                a.getToAmountBefore(), a.getToAmountAfter(),
                a.getProjectVersion(), a.getInvalidatedReason(), a.getInvalidatedAt());
    }
}
