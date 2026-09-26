package com.chris64233.granttranche.repo;

import com.chris64233.granttranche.domain.Tranche;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TrancheRepository extends JpaRepository<Tranche, Long> {
}
