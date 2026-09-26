package com.chris64233.granttranche.service;

import com.chris64233.granttranche.domain.ComplianceHold;
import com.chris64233.granttranche.domain.Disbursement;
import com.chris64233.granttranche.domain.DisbursementStatus;
import com.chris64233.granttranche.domain.FundRecord;
import com.chris64233.granttranche.domain.FundRecordType;
import com.chris64233.granttranche.domain.GrantProject;
import com.chris64233.granttranche.domain.Tranche;
import com.chris64233.granttranche.domain.TrancheStatus;
import com.chris64233.granttranche.error.BusinessException;
import com.chris64233.granttranche.repo.ComplianceHoldRepository;
import com.chris64233.granttranche.repo.DisbursementRepository;
import com.chris64233.granttranche.repo.FundRecordRepository;
import com.chris64233.granttranche.repo.GrantProjectRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 资助项目分期拨款核心服务。
 *
 * 并发模型：所有改变项目资金状态或验收/合规状态的写操作，都先通过
 * {@link GrantProjectRepository#findByIdForUpdate} 获取项目行悲观写锁，
 * 在锁内完成校验与状态变更，因此验收决定、合规暂停与拨款批准/确认并发时
 * 只会按锁顺序形成一种一致结果。幂等由业务号唯一约束 + 锁内重查保证。
 */
@Service
public class GrantService {

    private final GrantProjectRepository projectRepository;
    private final ComplianceHoldRepository holdRepository;
    private final DisbursementRepository disbursementRepository;
    private final FundRecordRepository fundRecordRepository;

    public GrantService(GrantProjectRepository projectRepository,
                        ComplianceHoldRepository holdRepository,
                        DisbursementRepository disbursementRepository,
                        FundRecordRepository fundRecordRepository) {
        this.projectRepository = projectRepository;
        this.holdRepository = holdRepository;
        this.disbursementRepository = disbursementRepository;
        this.fundRecordRepository = fundRecordRepository;
    }

    /** 期次定义（创建项目时随项目一并提交）。 */
    public record TrancheSpec(int sequence, BigDecimal plannedAmount,
                              String requiredDeliverable, String budgetCondition) {
    }

    // ---------- 项目与期次 ----------

    @Transactional
    public GrantProject createProject(String name, BigDecimal approvedAmount, List<TrancheSpec> specs) {
        requirePositive(approvedAmount, "项目批准金额必须为正数");
        if (specs == null || specs.isEmpty()) {
            throw new BusinessException("项目至少需要一个拨款期次");
        }
        Set<Integer> sequences = new HashSet<>();
        BigDecimal total = BigDecimal.ZERO;
        for (TrancheSpec spec : specs) {
            if (!sequences.add(spec.sequence())) {
                throw new BusinessException("期次序号重复: " + spec.sequence());
            }
            requirePositive(spec.plannedAmount(), "期次计划金额必须为正数: " + spec.sequence());
            total = total.add(spec.plannedAmount());
        }
        if (total.compareTo(approvedAmount) > 0) {
            throw new BusinessException("全部期次计划金额之和不得超过项目批准金额");
        }
        GrantProject project = new GrantProject(name, approvedAmount);
        specs.stream()
                .sorted((a, b) -> Integer.compare(a.sequence(), b.sequence()))
                .forEach(spec -> project.addTranche(new Tranche(
                        spec.sequence(), spec.plannedAmount(),
                        spec.requiredDeliverable(), spec.budgetCondition())));
        return projectRepository.save(project);
    }

    @Transactional
    public Tranche submitDeliverable(Long projectId, int sequence, String evidence, String operator) {
        GrantProject project = lockProject(projectId);
        Tranche tranche = findTranche(project, sequence);
        if (tranche.getStatus() != TrancheStatus.PENDING && tranche.getStatus() != TrancheStatus.REJECTED) {
            throw new BusinessException("当前状态不允许提交成果: " + tranche.getStatus());
        }
        tranche.submitDeliverable(evidence, operator);
        return tranche;
    }

    @Transactional
    public Tranche decideAcceptance(Long projectId, int sequence, boolean approved,
                                    String operator, String reason) {
        GrantProject project = lockProject(projectId);
        Tranche tranche = findTranche(project, sequence);
        if (tranche.getStatus() != TrancheStatus.SUBMITTED) {
            throw new BusinessException("期次未处于验收中状态: " + tranche.getStatus());
        }
        tranche.decideAcceptance(approved, operator, reason);
        return tranche;
    }

    // ---------- 合规暂停 ----------

    @Transactional
    public ComplianceHold createHold(Long projectId, String reason, String operator) {
        GrantProject project = lockProject(projectId);
        return holdRepository.save(new ComplianceHold(project, reason, operator));
    }

    @Transactional
    public ComplianceHold liftHold(Long projectId, Long holdId, String operator, String reason) {
        lockProject(projectId);
        ComplianceHold hold = holdRepository.findById(holdId)
                .filter(h -> h.getProject().getId().equals(projectId))
                .orElseThrow(() -> new BusinessException("合规暂停不存在: " + holdId));
        if (!hold.isActive()) {
            throw new BusinessException("合规暂停已解除: " + holdId);
        }
        hold.lift(operator, reason);
        return hold;
    }

    // ---------- 拨款 ----------

    /**
     * 批准拨款（幂等）：同一业务号重复提交返回既有拨款，不重复占额、不重复生成资金记录。
     */
    @Transactional
    public Disbursement approveDisbursement(Long projectId, int sequence, String businessNo,
                                            BigDecimal amount, String operator, String reason) {
        var existing = disbursementRepository.findByBusinessNo(businessNo);
        if (existing.isPresent()) {
            return existing.get();
        }
        GrantProject project = lockProject(projectId);
        // 锁内重查，防止并发下同一业务号重复入账
        existing = disbursementRepository.findByBusinessNo(businessNo);
        if (existing.isPresent()) {
            return existing.get();
        }
        Tranche tranche = findTranche(project, sequence);
        if (tranche.getStatus() != TrancheStatus.ACCEPTED) {
            throw new BusinessException("期次成果未验收通过，不能拨款: " + sequence);
        }
        if (holdRepository.existsByProjectIdAndActiveTrue(projectId)) {
            throw new BusinessException("项目存在活动中的合规暂停，不能拨款");
        }
        if (disbursementRepository.existsByTrancheIdAndStatusNot(tranche.getId(), DisbursementStatus.REVOKED)) {
            throw new BusinessException("该期次已存在有效拨款: " + sequence);
        }
        for (Tranche prior : project.getTranches()) {
            if (prior.getSequence() >= sequence) {
                break;
            }
            if (!disbursementRepository.existsByTrancheIdAndStatusNot(prior.getId(), DisbursementStatus.REVOKED)) {
                throw new BusinessException("前置期次尚未拨款，不能绕过: " + prior.getSequence());
            }
        }
        BigDecimal effective = amount != null ? amount : tranche.getPlannedAmount();
        requirePositive(effective, "拨款金额必须为正数");
        if (effective.compareTo(tranche.getPlannedAmount()) > 0) {
            throw new BusinessException("拨款金额不得超过期次计划金额");
        }
        if (project.getDisbursedAmount().add(effective).compareTo(project.getApprovedAmount()) > 0) {
            throw new BusinessException("累计拨款将超过项目批准金额");
        }
        Disbursement disbursement = new Disbursement(project, tranche, businessNo, effective, operator, reason);
        disbursementRepository.save(disbursement);
        project.increaseDisbursed(effective);
        fundRecordRepository.save(new FundRecord(project, disbursement, FundRecordType.DISBURSEMENT,
                businessNo, effective, operator, reason));
        return disbursement;
    }

    /** 支付确认：已批准 -> 已支付。 */
    @Transactional
    public Disbursement confirmPayment(Long projectId, Long disbursementId, String operator) {
        lockProject(projectId);
        Disbursement disbursement = findDisbursement(projectId, disbursementId);
        if (disbursement.getStatus() != DisbursementStatus.APPROVED) {
            throw new BusinessException("只有已批准未支付的拨款可以确认支付: " + disbursement.getStatus());
        }
        disbursement.markPaid(operator);
        return disbursement;
    }

    /** 撤销未支付拨款：恢复额度并生成冲正台账记录。 */
    @Transactional
    public Disbursement revokeDisbursement(Long projectId, Long disbursementId, String operator, String reason) {
        GrantProject project = lockProject(projectId);
        Disbursement disbursement = findDisbursement(projectId, disbursementId);
        if (disbursement.getStatus() == DisbursementStatus.PAID) {
            throw new BusinessException("已支付拨款不能撤销，只能通过追回减少净拨款额");
        }
        if (disbursement.getStatus() == DisbursementStatus.REVOKED) {
            throw new BusinessException("拨款已撤销: " + disbursementId);
        }
        disbursement.revoke(operator, reason);
        project.decreaseDisbursed(disbursement.getAmount());
        fundRecordRepository.save(new FundRecord(project, disbursement, FundRecordType.REVERSAL,
                disbursement.getBusinessNo() + ":REVOKE", disbursement.getAmount(), operator, reason));
        return disbursement;
    }

    /**
     * 追回已支付拨款（幂等）：生成追回台账记录减少净拨款额，原拨款资金记录保持不变。
     */
    @Transactional
    public FundRecord clawback(Long projectId, Long disbursementId, String businessNo,
                               BigDecimal amount, String operator, String reason) {
        var existing = fundRecordRepository.findByBusinessNo(businessNo);
        if (existing.isPresent()) {
            return existing.get();
        }
        GrantProject project = lockProject(projectId);
        existing = fundRecordRepository.findByBusinessNo(businessNo);
        if (existing.isPresent()) {
            return existing.get();
        }
        Disbursement disbursement = findDisbursement(projectId, disbursementId);
        if (disbursement.getStatus() != DisbursementStatus.PAID) {
            throw new BusinessException("只有已支付拨款可以追回: " + disbursement.getStatus());
        }
        requirePositive(amount, "追回金额必须为正数");
        BigDecimal clawedBack = fundRecordRepository.sumAmountByDisbursementIdAndType(
                disbursementId, FundRecordType.CLAWBACK);
        BigDecimal netRemaining = disbursement.getAmount().subtract(clawedBack);
        if (amount.compareTo(netRemaining) > 0) {
            throw new BusinessException("追回金额超过该拨款剩余净额: " + netRemaining);
        }
        project.decreaseDisbursed(amount);
        return fundRecordRepository.save(new FundRecord(project, disbursement, FundRecordType.CLAWBACK,
                businessNo, amount, operator, reason));
    }

    // ---------- 查询 ----------

    @Transactional(readOnly = true)
    public GrantProject getProject(Long projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new BusinessException("项目不存在: " + projectId));
    }

    @Transactional(readOnly = true)
    public List<Tranche> listTranches(Long projectId) {
        return List.copyOf(getProject(projectId).getTranches());
    }

    @Transactional(readOnly = true)
    public List<ComplianceHold> listHolds(Long projectId) {
        getProject(projectId);
        return holdRepository.findByProjectIdOrderByCreatedAtAsc(projectId);
    }

    @Transactional(readOnly = true)
    public List<Disbursement> listDisbursements(Long projectId) {
        getProject(projectId);
        return disbursementRepository.findByProjectIdOrderByCreatedAtAsc(projectId);
    }

    @Transactional(readOnly = true)
    public List<FundRecord> listFundRecords(Long projectId) {
        getProject(projectId);
        return fundRecordRepository.findByProjectIdOrderByCreatedAtAsc(projectId);
    }

    // ---------- 内部 ----------

    private GrantProject lockProject(Long projectId) {
        return projectRepository.findByIdForUpdate(projectId)
                .orElseThrow(() -> new BusinessException("项目不存在: " + projectId));
    }

    private Tranche findTranche(GrantProject project, int sequence) {
        return project.getTranches().stream()
                .filter(t -> t.getSequence() == sequence)
                .findFirst()
                .orElseThrow(() -> new BusinessException("期次不存在: " + sequence));
    }

    private Disbursement findDisbursement(Long projectId, Long disbursementId) {
        return disbursementRepository.findById(disbursementId)
                .filter(d -> d.getProject().getId().equals(projectId))
                .orElseThrow(() -> new BusinessException("拨款不存在: " + disbursementId));
    }

    private void requirePositive(BigDecimal amount, String message) {
        if (amount == null || amount.signum() <= 0) {
            throw new BusinessException(message);
        }
    }
}
