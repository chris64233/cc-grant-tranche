package com.chris64233.granttranche.repo;

import com.chris64233.granttranche.domain.GrantProject;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface GrantProjectRepository extends JpaRepository<GrantProject, Long> {

    Optional<GrantProject> findByProjectCode(String projectCode);

    /**
     * 悲观写锁加载项目：验收、合规暂停/解除、拨款批准、支付、撤销与追回等所有
     * 会改变项目额度或一致性状态的事务，都通过同一把行锁串行化。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "10000"))
    @Query("select p from GrantProject p where p.id = :id")
    Optional<GrantProject> findByIdForUpdate(@Param("id") Long id);
}
