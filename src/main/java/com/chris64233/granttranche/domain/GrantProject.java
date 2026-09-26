package com.chris64233.granttranche.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 资助项目：记录总批准金额与净累计拨款额（批准加、撤销/追回减）。
 * 所有改变累计拨款的写操作都必须在项目行悲观锁内完成。
 */
@Entity
@Table(name = "grant_project")
public class GrantProject {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal approvedAmount;

    /** 净累计拨款额 = 已批准拨款 - 撤销 - 追回。 */
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal disbursedAmount = BigDecimal.ZERO;

    @OneToMany(mappedBy = "project", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sequence ASC")
    private List<Tranche> tranches = new ArrayList<>();

    protected GrantProject() {
    }

    public GrantProject(String name, BigDecimal approvedAmount) {
        this.name = name;
        this.approvedAmount = approvedAmount;
    }

    public void addTranche(Tranche tranche) {
        tranche.assignProject(this);
        this.tranches.add(tranche);
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public BigDecimal getApprovedAmount() {
        return approvedAmount;
    }

    public BigDecimal getDisbursedAmount() {
        return disbursedAmount;
    }

    public void increaseDisbursed(BigDecimal amount) {
        this.disbursedAmount = this.disbursedAmount.add(amount);
    }

    public void decreaseDisbursed(BigDecimal amount) {
        this.disbursedAmount = this.disbursedAmount.subtract(amount);
    }

    public BigDecimal getRemainingAmount() {
        return approvedAmount.subtract(disbursedAmount);
    }

    public List<Tranche> getTranches() {
        return tranches;
    }
}
