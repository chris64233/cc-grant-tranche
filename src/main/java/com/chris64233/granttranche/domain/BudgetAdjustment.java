package com.chris64233.granttranche.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 未拨付预算在未来期次之间的调整记录。
 *
 * <p>调整只重新分配项目<b>尚未使用</b>的金额：调出期次 {@code plannedAmount} 减少、
 * 调入期次等额增加，项目批准总额与累计拨款均不变；已支付、正在拨付（未支付已批准拨款）
 * 或已用于追回的金额始终保留在原期次、原用途，不允许移动（期次一旦进入
 * DISBURSED/PAID 即不可调出或调入）。
 *
 * <p>两阶段：申请（{@link AdjustmentStatus#PROPOSED}）只快照依据，不动金额；
 * 确认（{@link AdjustmentStatus#CONFIRMED}）在同一事务、同一项目版本裁决下完成两个期次的更新。
 * 合规暂停发生时待确认方案一律作废（{@link AdjustmentStatus#INVALIDATED}），
 * 恢复后必须重新检查期次状态与余额并重新申请，旧方案不能沿用。
 *
 * <p>{@code businessNo} 全局唯一，是申请与确认的幂等键；记录中完整保留原因、申请人、
 * 确认批准人以及两期次调整前后的金额快照。
 */
@Entity
@Table(name = "budget_adjustment",
        uniqueConstraints = @UniqueConstraint(name = "uk_adjustment_business_no", columnNames = "business_no"))
public class BudgetAdjustment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 调用方提供的业务号，全局唯一，申请/确认幂等的依据。 */
    @Column(name = "business_no", nullable = false, length = 64)
    private String businessNo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private GrantProject project;

    /** 调出期次（减少计划金额，必须为未拨付期次）。 */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "from_tranche_id", nullable = false)
    private GrantTranche fromTranche;

    /** 调入期次（等额增加计划金额，必须为未拨付期次）。 */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "to_tranche_id", nullable = false)
    private GrantTranche toTranche;

    /** 调整金额：从调出期次转移到调入期次的未拨付金额。 */
    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AdjustmentStatus status = AdjustmentStatus.PROPOSED;

    /** 调整原因。 */
    @Column(name = "reason", nullable = false, length = 1000)
    private String reason;

    /** 申请人（发起调整的经办人）。 */
    @Column(name = "requested_by", nullable = false, length = 64)
    private String requestedBy;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    /** 确认批准人（确认时填写）。 */
    @Column(name = "confirmed_by", length = 64)
    private String confirmedBy;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    /** 申请时调出期次的计划金额（调整前）。 */
    @Column(name = "from_amount_before", nullable = false, precision = 19, scale = 2)
    private BigDecimal fromAmountBefore;

    /** 申请时调入期次的计划金额（调整前）。 */
    @Column(name = "to_amount_before", nullable = false, precision = 19, scale = 2)
    private BigDecimal toAmountBefore;

    /** 确认后调出期次的计划金额（调整后）。 */
    @Column(name = "from_amount_after", precision = 19, scale = 2)
    private BigDecimal fromAmountAfter;

    /** 确认后调入期次的计划金额（调整后）。 */
    @Column(name = "to_amount_after", precision = 19, scale = 2)
    private BigDecimal toAmountAfter;

    /** 申请时快照的项目版本；确认时必须仍匹配，否则说明依据已变化，旧方案失效。 */
    @Column(name = "project_version", nullable = false)
    private Long projectVersion;

    /** 失效原因（合规暂停或确认时依据变化）。 */
    @Column(name = "invalidated_reason", length = 1000)
    private String invalidatedReason;

    @Column(name = "invalidated_at")
    private Instant invalidatedAt;

    protected BudgetAdjustment() {
    }

    public BudgetAdjustment(String businessNo, GrantProject project,
                            GrantTranche fromTranche, GrantTranche toTranche,
                            BigDecimal amount, String reason, String requestedBy, Instant now,
                            Long projectVersion) {
        this.businessNo = businessNo;
        this.project = project;
        this.fromTranche = fromTranche;
        this.toTranche = toTranche;
        this.amount = amount;
        this.reason = reason;
        this.requestedBy = requestedBy;
        this.requestedAt = now;
        this.projectVersion = projectVersion;
        this.fromAmountBefore = fromTranche.getPlannedAmount();
        this.toAmountBefore = toTranche.getPlannedAmount();
    }

    /** 确认调整：记录批准人与调整后金额。两个期次金额的更新由服务层在同一事务内完成。 */
    public void markConfirmed(String confirmedBy, BigDecimal fromAfter, BigDecimal toAfter, Instant now) {
        this.status = AdjustmentStatus.CONFIRMED;
        this.confirmedBy = confirmedBy;
        this.confirmedAt = now;
        this.fromAmountAfter = fromAfter;
        this.toAmountAfter = toAfter;
    }

    /** 方案失效（合规暂停或申请依据在确认前已变化），旧方案不可再沿用。 */
    public void markInvalidated(String reason, Instant now) {
        this.status = AdjustmentStatus.INVALIDATED;
        this.invalidatedReason = reason;
        this.invalidatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public String getBusinessNo() {
        return businessNo;
    }

    public GrantProject getProject() {
        return project;
    }

    public GrantTranche getFromTranche() {
        return fromTranche;
    }

    public GrantTranche getToTranche() {
        return toTranche;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public AdjustmentStatus getStatus() {
        return status;
    }

    public String getReason() {
        return reason;
    }

    public String getRequestedBy() {
        return requestedBy;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public String getConfirmedBy() {
        return confirmedBy;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    public BigDecimal getFromAmountBefore() {
        return fromAmountBefore;
    }

    public BigDecimal getToAmountBefore() {
        return toAmountBefore;
    }

    public BigDecimal getFromAmountAfter() {
        return fromAmountAfter;
    }

    public BigDecimal getToAmountAfter() {
        return toAmountAfter;
    }

    public Long getProjectVersion() {
        return projectVersion;
    }

    public String getInvalidatedReason() {
        return invalidatedReason;
    }

    public Instant getInvalidatedAt() {
        return invalidatedAt;
    }
}
