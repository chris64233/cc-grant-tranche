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
 * 拨款期次。每个期次按 {@code sequenceNo} 有序排列，携带计划金额、所需成果和预算条件。
 *
 * <p>状态流转：PLANNED → SUBMITTED → ACCEPTED → DISBURSED → PAID；
 * 验收未通过进入 REJECTED，可重新提交回到 SUBMITTED；撤销未支付拨款后回到 ACCEPTED。
 */
@Entity
@Table(name = "grant_tranche",
        uniqueConstraints = @UniqueConstraint(name = "uk_tranche_project_seq",
                columnNames = {"project_id", "sequence_no"}))
public class GrantTranche {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private GrantProject project;

    /** 项目内有序期次号，从 1 开始，拨款不得绕过前置期次。 */
    @Column(name = "sequence_no", nullable = false)
    private Integer sequenceNo;

    @Column(name = "planned_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal plannedAmount;

    /** 所需成果描述。 */
    @Column(name = "required_deliverable", nullable = false, length = 1000)
    private String requiredDeliverable;

    /** 预算条件描述（如配套资金、支出范围约束）。 */
    @Column(name = "budget_conditions", nullable = false, length = 1000)
    private String budgetConditions;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TrancheStatus status = TrancheStatus.PLANNED;

    /** 已提交的成果证据。 */
    @Column(name = "deliverable_evidence", length = 2000)
    private String deliverableEvidence;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "submitted_by", length = 64)
    private String submittedBy;

    /** 验收决定与原因。 */
    @Column(name = "review_comment", length = 1000)
    private String reviewComment;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "reviewed_by", length = 64)
    private String reviewedBy;

    protected GrantTranche() {
    }

    public GrantTranche(GrantProject project, Integer sequenceNo, BigDecimal plannedAmount,
                        String requiredDeliverable, String budgetConditions) {
        this.project = project;
        this.sequenceNo = sequenceNo;
        this.plannedAmount = plannedAmount;
        this.requiredDeliverable = requiredDeliverable;
        this.budgetConditions = budgetConditions;
    }

    public Long getId() {
        return id;
    }

    public GrantProject getProject() {
        return project;
    }

    public Integer getSequenceNo() {
        return sequenceNo;
    }

    public BigDecimal getPlannedAmount() {
        return plannedAmount;
    }

    /**
     * 更新计划金额。仅由预算调整确认在同一事务内对未拨付期次做等额一增一减，
     * 项目批准总额不变；已进入拨付/支付环节的期次不允许调用。
     */
    public void setPlannedAmount(BigDecimal plannedAmount) {
        this.plannedAmount = plannedAmount;
    }

    public String getRequiredDeliverable() {
        return requiredDeliverable;
    }

    public String getBudgetConditions() {
        return budgetConditions;
    }

    public TrancheStatus getStatus() {
        return status;
    }

    public void setStatus(TrancheStatus status) {
        this.status = status;
    }

    public String getDeliverableEvidence() {
        return deliverableEvidence;
    }

    public void setDeliverableEvidence(String deliverableEvidence) {
        this.deliverableEvidence = deliverableEvidence;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public void setSubmittedAt(Instant submittedAt) {
        this.submittedAt = submittedAt;
    }

    public String getSubmittedBy() {
        return submittedBy;
    }

    public void setSubmittedBy(String submittedBy) {
        this.submittedBy = submittedBy;
    }

    public String getReviewComment() {
        return reviewComment;
    }

    public void setReviewComment(String reviewComment) {
        this.reviewComment = reviewComment;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }

    public void setReviewedAt(Instant reviewedAt) {
        this.reviewedAt = reviewedAt;
    }

    public String getReviewedBy() {
        return reviewedBy;
    }

    public void setReviewedBy(String reviewedBy) {
        this.reviewedBy = reviewedBy;
    }
}
