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

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 拨款：业务号全局唯一保证幂等；批准即占用额度，撤销恢复额度，支付后只能追回。
 */
@Entity
@Table(name = "disbursement")
public class Disbursement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id")
    private GrantProject project;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tranche_id")
    private Tranche tranche;

    /** 拨款业务号，幂等键。 */
    @Column(nullable = false, unique = true)
    private String businessNo;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DisbursementStatus status = DisbursementStatus.APPROVED;

    @Column(nullable = false)
    private String approvedBy;

    @Column(nullable = false)
    private String approvalReason;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    private String paidBy;
    private Instant paidAt;

    private String revokedBy;
    private String revokeReason;
    private Instant revokedAt;

    protected Disbursement() {
    }

    public Disbursement(GrantProject project, Tranche tranche, String businessNo, BigDecimal amount,
                        String approvedBy, String approvalReason) {
        this.project = project;
        this.tranche = tranche;
        this.businessNo = businessNo;
        this.amount = amount;
        this.approvedBy = approvedBy;
        this.approvalReason = approvalReason;
    }

    public void markPaid(String operator) {
        this.status = DisbursementStatus.PAID;
        this.paidBy = operator;
        this.paidAt = Instant.now();
    }

    public void revoke(String operator, String reason) {
        this.status = DisbursementStatus.REVOKED;
        this.revokedBy = operator;
        this.revokeReason = reason;
        this.revokedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public GrantProject getProject() {
        return project;
    }

    public Tranche getTranche() {
        return tranche;
    }

    public String getBusinessNo() {
        return businessNo;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public DisbursementStatus getStatus() {
        return status;
    }

    public String getApprovedBy() {
        return approvedBy;
    }

    public String getApprovalReason() {
        return approvalReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getPaidBy() {
        return paidBy;
    }

    public Instant getPaidAt() {
        return paidAt;
    }

    public String getRevokedBy() {
        return revokedBy;
    }

    public String getRevokeReason() {
        return revokeReason;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }
}
