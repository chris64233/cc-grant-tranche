package com.chris64233.granttranche.repo;

import com.chris64233.granttranche.domain.GrantProject;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface GrantProjectRepository extends JpaRepository<GrantProject, Long> {

    /**
     * 项目行悲观写锁：验收决定、合规暂停、拨款批准/撤销/支付/追回等所有
     * 相互竞争的写操作都在此锁内串行化，保证只形成一种一致结果。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from GrantProject p where p.id = :id")
    Optional<GrantProject> findByIdForUpdate(Long id);
}
