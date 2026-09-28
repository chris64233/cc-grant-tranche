package com.chris64233.granttranche.repo;

import com.chris64233.granttranche.domain.GrantProject;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface GrantProjectRepository extends JpaRepository<GrantProject, Long> {

    Optional<GrantProject> findByProjectCode(String projectCode);

    /**
     * 悲观写锁加载项目：验收、合规暂停/解除、拨款批准、支付、撤销、追回与预算调整确认等所有
     * 会改变项目额度或一致性状态的事务，都通过同一把行锁串行化。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "10000"))
    @Query("select p from GrantProject p where p.id = :id")
    Optional<GrantProject> findByIdForUpdate(@Param("id") Long id);

    /** 标量读取当前项目版本（不经一级缓存），用于加锁前快照、加锁后比对的并发裁决。 */
    @Query("select p.version from GrantProject p where p.id = :id")
    Optional<Long> findVersionById(@Param("id") Long id);

    /**
     * 条件式版本递增：仅当版本号仍为期望值时 +1，返回受影响行数。
     *
     * <p>用于自身不修改项目金额字段、但必须推进项目版本的操作（合规暂停、预算调整确认），
     * 使并发、依据旧版本的拨款确认在加锁后版本比对时落败；调用方须已持有项目行悲观锁，
     * 因此正常情况下返回 1，返回 0 即表示发生并发状态变化。默认 flushAutomatically 会先把
     * 同事务内期次/方案的更新落库，版本递增与它们处于同一事务，任一失败整体回滚。
     */
    @Modifying
    @Query("update GrantProject p set p.version = p.version + 1 where p.id = :id and p.version = :expected")
    int bumpVersionIfMatches(@Param("id") Long id, @Param("expected") Long expected);
}
