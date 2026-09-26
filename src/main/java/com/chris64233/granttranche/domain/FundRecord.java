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
 * 资金台账记录：只增不改。DISBURSEMENT 增加净拨款，REVERSAL/CLAWBACK 减少净拨款。
 */
@Entity
@Table(name = "fund_record")
public class FundRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id")
    private GrantProject project;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "disbursement_id")
    private Disbursement disbursement;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FundRecordType type;

    /** 台账业务号，幂等键（拨款记录与拨款同号，冲正/追回各自编号）。 */
    @Column(nullable = false, unique = true)
    private String businessNo;

    /** 正数金额；方向由 type 决定。 */
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false)
    private String operator;

    @Column(nullable = false)
    private String reason;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    protected FundRecord() {
    }

    public FundRecord(GrantProject project, Disbursement disbursement, FundRecordType type,
                      String businessNo, BigDecimal amount, String operator, String reason) {
        this.project = project;
        this.disbursement = disbursement;
        this.type = type;
        this.businessNo = businessNo;
        this.amount = amount;
        this.operator = operator;
        this.reason = reason;
    }

    public Long getId() {
        return id;
    }

    public GrantProject getProject() {
        return project;
    }

    public Disbursement getDisbursement() {
        return disbursement;
    }

    public FundRecordType getType() {
        return type;
    }

    public String getBusinessNo() {
        return businessNo;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getOperator() {
        return operator;
    }

    public String getReason() {
        return reason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
