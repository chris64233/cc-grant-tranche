package com.chris64233.granttranche.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 合规暂停记录。存在处于活动状态（未解除）的暂停时，拨款一律不得批准。
 */
@Entity
@Table(name = "compliance_suspension")
public class ComplianceSuspension {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private GrantProject project;

    /** 是否处于活动中（解除后置为 false，记录保留）。 */
    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "reason", nullable = false, length = 1000)
    private String reason;

    @Column(name = "raised_by", nullable = false, length = 64)
    private String raisedBy;

    @Column(name = "raised_at", nullable = false)
    private Instant raisedAt;

    @Column(name = "lifted_reason", length = 1000)
    private String liftedReason;

    @Column(name = "lifted_by", length = 64)
    private String liftedBy;

    @Column(name = "lifted_at")
    private Instant liftedAt;

    protected ComplianceSuspension() {
    }

    public ComplianceSuspension(GrantProject project, String reason, String raisedBy, Instant now) {
        this.project = project;
        this.reason = reason;
        this.raisedBy = raisedBy;
        this.raisedAt = now;
    }

    public Long getId() {
        return id;
    }

    public GrantProject getProject() {
        return project;
    }

    public boolean isActive() {
        return active;
    }

    public String getReason() {
        return reason;
    }

    public String getRaisedBy() {
        return raisedBy;
    }

    public Instant getRaisedAt() {
        return raisedAt;
    }

    public String getLiftedReason() {
        return liftedReason;
    }

    public String getLiftedBy() {
        return liftedBy;
    }

    public Instant getLiftedAt() {
        return liftedAt;
    }

    public void lift(String liftedBy, String reason, Instant now) {
        this.active = false;
        this.liftedBy = liftedBy;
        this.liftedReason = reason;
        this.liftedAt = now;
    }
}
