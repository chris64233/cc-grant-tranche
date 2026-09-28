package com.chris64233.granttranche.repo;

import com.chris64233.granttranche.domain.BudgetAdjustment;
import com.chris64233.granttranche.domain.AdjustmentStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BudgetAdjustmentRepository extends JpaRepository<BudgetAdjustment, Long> {

    /** 按业务号查询，调整申请/确认幂等的依据（数据库另有唯一约束兜底）。 */
    Optional<BudgetAdjustment> findByBusinessNo(String businessNo);

    /** 项目的全部调整记录，新的在前（用于留痕查询）。 */
    List<BudgetAdjustment> findByProjectIdOrderByIdDesc(Long projectId);

    /** 项目内处于待确认状态的调整方案（发起合规暂停时批量作废）。 */
    List<BudgetAdjustment> findByProjectIdAndStatus(Long projectId, AdjustmentStatus status);

    /** 标量查询调整所属项目：用于在加载调整实体、加项目行锁之前确定项目并做版本快照。 */
    @org.springframework.data.jpa.repository.Query(
            "select a.project.id from BudgetAdjustment a where a.id = :id")
    Optional<Long> findProjectIdById(@org.springframework.data.repository.query.Param("id") Long id);
}
