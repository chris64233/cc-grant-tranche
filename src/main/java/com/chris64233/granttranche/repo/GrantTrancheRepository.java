package com.chris64233.granttranche.repo;

import com.chris64233.granttranche.domain.GrantTranche;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GrantTrancheRepository extends JpaRepository<GrantTranche, Long> {

    List<GrantTranche> findByProjectIdOrderBySequenceNoAsc(Long projectId);

    Optional<GrantTranche> findByIdAndProjectId(Long id, Long projectId);

    boolean existsByProjectIdAndSequenceNo(Long projectId, Integer sequenceNo);
}
