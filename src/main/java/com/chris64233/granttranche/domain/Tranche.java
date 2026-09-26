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
 * 拨款期次：有顺序，包含计划金额、所需成果与预算条件；承载成果提交与验收决定。
 */
@Entity
@Table(name = "tranche", uniqueConstraints = @UniqueConstraint(columnNames = {"project_id", "sequence"}))
public class Tranche {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id")
    private GrantProject project;

    @Column(nullable = false)
    private int sequence;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal plannedAmount;

    @Column(nullable = false)
    private String requiredDeliverable;

    @Column(nullable = false)
    private String budgetCondition;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TrancheStatus status = TrancheStatus.PENDING;

    // 成果提交证据
    private String deliverableEvidence;
    private String submittedBy;
    private Instant submittedAt;

    // 验收决定（保留人员与原因）
    private String acceptanceDecidedBy;
    private String acceptanceReason;
    private Instant acceptanceDecidedAt;

    protected Tranche() {
    }

    public Tranche(int sequence, BigDecimal plannedAmount, String requiredDeliverable, String budgetCondition) {
        this.sequence = sequence;
        this.plannedAmount = plannedAmount;
        this.requiredDeliverable = requiredDeliverable;
        this.budgetCondition = budgetCondition;
    }

    void assignProject(GrantProject project) {
        this.project = project;
    }

    public void submitDeliverable(String evidence, String operator) {
        this.status = TrancheStatus.SUBMITTED;
        this.deliverableEvidence = evidence;
        this.submittedBy = operator;
        this.submittedAt = Instant.now();
        this.acceptanceDecidedBy = null;
        this.acceptanceReason = null;
        this.acceptanceDecidedAt = null;
    }

    public void decideAcceptance(boolean approved, String operator, String reason) {
        this.status = approved ? TrancheStatus.ACCEPTED : TrancheStatus.REJECTED;
        this.acceptanceDecidedBy = operator;
        this.acceptanceReason = reason;
        this.acceptanceDecidedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public GrantProject getProject() {
        return project;
    }

    public int getSequence() {
        return sequence;
    }

    public BigDecimal getPlannedAmount() {
        return plannedAmount;
    }

    public String getRequiredDeliverable() {
        return requiredDeliverable;
    }

    public String getBudgetCondition() {
        return budgetCondition;
    }

    public TrancheStatus getStatus() {
        return status;
    }

    public String getDeliverableEvidence() {
        return deliverableEvidence;
    }

    public String getSubmittedBy() {
        return submittedBy;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public String getAcceptanceDecidedBy() {
        return acceptanceDecidedBy;
    }

    public String getAcceptanceReason() {
        return acceptanceReason;
    }

    public Instant getAcceptanceDecidedAt() {
        return acceptanceDecidedAt;
    }
}
