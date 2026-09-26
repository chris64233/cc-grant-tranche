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
 * 资金台账记录：拨款或对已支付拨款的追回。
 *
 * <p>拨款记录在批准时生成，业务号 {@code businessNo} 全局唯一，保证拨款幂等；
 * 未支付拨款撤销时状态置为 REVOKED，原记录保留；已支付拨款只能生成 RECOVERY 记录冲减净拨款，
 * 原拨款记录保持 PAID 不变。所有处理决定均保留经办人与原因。
 */
@Entity
@Table(name = "fund_record",
        uniqueConstraints = @UniqueConstraint(name = "uk_fund_business_no", columnNames = "business_no"))
public class FundRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 拨款业务号（或追回业务号），全局唯一，保证幂等。 */
    @Column(name = "business_no", nullable = false, length = 64)
    private String businessNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "record_type", nullable = false, length = 20)
    private FundRecordType recordType;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private GrantProject project;

    /** 拨款记录关联的期次；追回记录可为空（通过 originalDisbursement 关联原拨款）。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tranche_id")
    private GrantTranche tranche;

    /** 追回记录指向的原拨款记录。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "original_disbursement_id")
    private FundRecord originalDisbursement;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FundRecordStatus status;

    /** 拨款记录上已追回的累计金额（仅对 DISBURSEMENT/PAID 有意义）。 */
    @Column(name = "recovered_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal recoveredAmount = BigDecimal.ZERO;

    @Column(name = "created_by", nullable = false, length = 64)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** 批准拨款时的原因（拨款依据）。 */
    @Column(name = "reason", length = 1000)
    private String reason;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "paid_by", length = 64)
    private String paidBy;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_by", length = 64)
    private String revokedBy;

    @Column(name = "revoke_reason", length = 1000)
    private String revokeReason;

    protected FundRecord() {
    }

    /** 创建拨款记录。 */
    public static FundRecord disbursement(String businessNo, GrantProject project, GrantTranche tranche,
                                          BigDecimal amount, String createdBy, String reason, Instant now) {
        FundRecord record = new FundRecord();
        record.businessNo = businessNo;
        record.recordType = FundRecordType.DISBURSEMENT;
        record.project = project;
        record.tranche = tranche;
        record.amount = amount;
        record.status = FundRecordStatus.UNPAID;
        record.createdBy = createdBy;
        record.reason = reason;
        record.createdAt = now;
        return record;
    }

    /** 创建追回记录（针对已支付拨款）。 */
    public static FundRecord recovery(String businessNo, GrantProject project, FundRecord original,
                                      BigDecimal amount, String createdBy, String reason, Instant now) {
        FundRecord record = new FundRecord();
        record.businessNo = businessNo;
        record.recordType = FundRecordType.RECOVERY;
        record.project = project;
        record.originalDisbursement = original;
        record.tranche = original.getTranche();
        record.amount = amount;
        record.status = FundRecordStatus.PAID;
        record.createdBy = createdBy;
        record.reason = reason;
        record.createdAt = now;
        record.paidAt = now;
        record.paidBy = createdBy;
        return record;
    }

    public Long getId() {
        return id;
    }

    public String getBusinessNo() {
        return businessNo;
    }

    public FundRecordType getRecordType() {
        return recordType;
    }

    public GrantProject getProject() {
        return project;
    }

    public GrantTranche getTranche() {
        return tranche;
    }

    public FundRecord getOriginalDisbursement() {
        return originalDisbursement;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public FundRecordStatus getStatus() {
        return status;
    }

    public void setStatus(FundRecordStatus status) {
        this.status = status;
    }

    public BigDecimal getRecoveredAmount() {
        return recoveredAmount;
    }

    public void setRecoveredAmount(BigDecimal recoveredAmount) {
        this.recoveredAmount = recoveredAmount;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getReason() {
        return reason;
    }

    public Instant getPaidAt() {
        return paidAt;
    }

    public String getPaidBy() {
        return paidBy;
    }

    public void markPaid(String paidBy, Instant now) {
        this.status = FundRecordStatus.PAID;
        this.paidBy = paidBy;
        this.paidAt = now;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public String getRevokedBy() {
        return revokedBy;
    }

    public String getRevokeReason() {
        return revokeReason;
    }

    public void markRevoked(String revokedBy, String reason, Instant now) {
        this.status = FundRecordStatus.REVOKED;
        this.revokedBy = revokedBy;
        this.revokeReason = reason;
        this.revokedAt = now;
    }
}
