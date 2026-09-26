package com.chris64233.granttranche.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 科研资助项目。
 *
 * <p>{@code totalApproved} 为批准总额；{@code totalDisbursed} 为累计拨款额（批准时原子增加、
 * 撤销未支付拨款时减少）；{@code totalRecovered} 为已支付拨款的追回总额（只增不减，
 * 原拨款资金记录保持不变）。所有金额使用 {@link BigDecimal} 并按两位小数存储。
 */
@Entity
@Table(name = "grant_project",
        uniqueConstraints = @UniqueConstraint(name = "uk_project_code", columnNames = "project_code"))
public class GrantProject {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_code", nullable = false, length = 64)
    private String projectCode;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(name = "approved_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal approvedAmount;

    /** 累计拨款额：所有批准拨款（含未支付）减已撤销拨款。 */
    @Column(name = "total_disbursed", nullable = false, precision = 19, scale = 2)
    private BigDecimal totalDisbursed = BigDecimal.ZERO;

    /** 已支付拨款的追回总额。 */
    @Column(name = "total_recovered", nullable = false, precision = 19, scale = 2)
    private BigDecimal totalRecovered = BigDecimal.ZERO;

    @OneToMany(mappedBy = "project")
    @OrderBy("sequenceNo ASC")
    private List<GrantTranche> tranches = new ArrayList<>();

    protected GrantProject() {
    }

    public GrantProject(String projectCode, String title, BigDecimal approvedAmount) {
        this.projectCode = projectCode;
        this.title = title;
        this.approvedAmount = approvedAmount;
    }

    public Long getId() {
        return id;
    }

    public String getProjectCode() {
        return projectCode;
    }

    public String getTitle() {
        return title;
    }

    public BigDecimal getApprovedAmount() {
        return approvedAmount;
    }

    /** 调整批准总额（如预算调减）。 */
    public void setApprovedAmount(BigDecimal approvedAmount) {
        this.approvedAmount = approvedAmount;
    }

    public BigDecimal getTotalDisbursed() {
        return totalDisbursed;
    }

    public void setTotalDisbursed(BigDecimal totalDisbursed) {
        this.totalDisbursed = totalDisbursed;
    }

    public BigDecimal getTotalRecovered() {
        return totalRecovered;
    }

    public void setTotalRecovered(BigDecimal totalRecovered) {
        this.totalRecovered = totalRecovered;
    }

    public List<GrantTranche> getTranches() {
        return tranches;
    }
}
