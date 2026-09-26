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
 * 合规暂停：活动期间阻止任何新拨款批准；解除需记录人员与原因。
 */
@Entity
@Table(name = "compliance_hold")
public class ComplianceHold {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id")
    private GrantProject project;

    @Column(nullable = false)
    private boolean active = true;

    @Column(nullable = false)
    private String reason;

    @Column(nullable = false)
    private String createdBy;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    private String liftedBy;
    private String liftReason;
    private Instant liftedAt;

    protected ComplianceHold() {
    }

    public ComplianceHold(GrantProject project, String reason, String createdBy) {
        this.project = project;
        this.reason = reason;
        this.createdBy = createdBy;
    }

    public void lift(String operator, String reason) {
        this.active = false;
        this.liftedBy = operator;
        this.liftReason = reason;
        this.liftedAt = Instant.now();
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

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getLiftedBy() {
        return liftedBy;
    }

    public String getLiftReason() {
        return liftReason;
    }

    public Instant getLiftedAt() {
        return liftedAt;
    }
}
