package com.chris64233.granttranche.repo;

import com.chris64233.granttranche.domain.GrantTranche;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GrantTrancheRepository extends JpaRepository<GrantTranche, Long> {

    List<GrantTranche> findByProjectIdOrderBySequenceNoAsc(Long projectId);

    Optional<GrantTranche> findByIdAndProjectId(Long id, Long projectId);

    boolean existsByProjectIdAndSequenceNo(Long projectId, Integer sequenceNo);

    /** 标量查询期次所属项目：用于在加载期次实体、加项目行锁之前确定项目并做版本快照。 */
    @org.springframework.data.jpa.repository.Query(
            "select t.project.id from GrantTranche t where t.id = :id")
    Optional<Long> findProjectIdById(@org.springframework.data.repository.query.Param("id") Long id);
}
