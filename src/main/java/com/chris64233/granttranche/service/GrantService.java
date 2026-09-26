package com.chris64233.granttranche.service;

import com.chris64233.granttranche.domain.ComplianceSuspension;
import com.chris64233.granttranche.domain.FundRecord;
import com.chris64233.granttranche.domain.FundRecordType;
import com.chris64233.granttranche.domain.GrantProject;
import com.chris64233.granttranche.domain.GrantTranche;
import com.chris64233.granttranche.domain.TrancheStatus;
import com.chris64233.granttranche.dto.CreateProjectRequest;
import com.chris64233.granttranche.dto.FundRecordView;
import com.chris64233.granttranche.dto.ProjectBalance;
import com.chris64233.granttranche.dto.SuspensionView;
import com.chris64233.granttranche.dto.TrancheView;
import com.chris64233.granttranche.repo.ComplianceSuspensionRepository;
import com.chris64233.granttranche.repo.FundRecordRepository;
import com.chris64233.granttranche.repo.GrantProjectRepository;
import com.chris64233.granttranche.repo.GrantTrancheRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
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
    private final Clock clock;

    public GrantService(GrantProjectRepository projectRepository,
                        GrantTrancheRepository trancheRepository,
                        FundRecordRepository fundRecordRepository,
                        ComplianceSuspensionRepository suspensionRepository,
                        Clock clock) {
        this.projectRepository = projectRepository;
        this.trancheRepository = trancheRepository;
        this.fundRecordRepository = fundRecordRepository;
        this.suspensionRepository = suspensionRepository;
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

    /** 发起合规暂停。存在活动暂停期间，任何期次拨款都不得批准。 */
    @Transactional
    public ComplianceSuspension suspend(Long projectId, String reason, String raisedBy) {
        GrantProject project = lockProject(projectId);
        if (suspensionRepository.existsByProjectIdAndActiveTrue(projectId)) {
            throw new BusinessRuleException("项目已存在活动中的合规暂停");
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

        GrantTranche tranche = getTranche(trancheId);
        GrantProject project = lockProject(tranche.getProject().getId());

        // 锁内复查，防止与并发事务竞争同一业务号。
        existing = fundRecordRepository.findByBusinessNo(businessNo).orElse(null);
        if (existing != null) {
            return sameDisbursementOrConflict(existing, trancheId, businessNo);
        }

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
}
