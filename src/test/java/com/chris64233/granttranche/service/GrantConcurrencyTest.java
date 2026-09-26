package com.chris64233.granttranche.service;

import com.chris64233.granttranche.domain.FundRecord;
import com.chris64233.granttranche.domain.FundRecordStatus;
import com.chris64233.granttranche.domain.FundRecordType;
import com.chris64233.granttranche.domain.GrantProject;
import com.chris64233.granttranche.domain.GrantTranche;
import com.chris64233.granttranche.domain.TrancheStatus;
import com.chris64233.granttranche.dto.CreateProjectRequest;
import com.chris64233.granttranche.repo.ComplianceSuspensionRepository;
import com.chris64233.granttranche.repo.FundRecordRepository;
import com.chris64233.granttranche.repo.GrantProjectRepository;
import com.chris64233.granttranche.repo.GrantTrancheRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 并发一致性测试：在真实线程 + 真实事务 + H2 行锁下验证
 * 总额不突破、期次顺序不绕过、业务号幂等以及验收/暂停/拨款只形成一种一致结果。
 */
@SpringBootTest
class GrantConcurrencyTest {

    @Autowired
    private GrantService grantService;
    @Autowired
    private GrantProjectRepository projectRepository;
    @Autowired
    private GrantTrancheRepository trancheRepository;
    @Autowired
    private FundRecordRepository fundRecordRepository;
    @Autowired
    private ComplianceSuspensionRepository suspensionRepository;
    @Autowired
    private TransactionTemplate transactionTemplate;

    private static CreateProjectRequest.TrancheSpec spec(int seq, String amount) {
        return new CreateProjectRequest.TrancheSpec(seq, new BigDecimal(amount), "成果" + seq, "条件" + seq);
    }

    private GrantProject createProject(String code, String approved, String... amounts) {
        List<CreateProjectRequest.TrancheSpec> specs = new ArrayList<>();
        for (int i = 0; i < amounts.length; i++) {
            specs.add(spec(i + 1, amounts[i]));
        }
        return grantService.createProject(new CreateProjectRequest(code, "项目" + code,
                new BigDecimal(approved), specs));
    }

    private void accept(Long trancheId) {
        grantService.submitDeliverable(trancheId, "证据-" + trancheId, "pi");
        grantService.review(trancheId, true, "reviewer", "通过");
    }

    private boolean isBusinessFailure(Throwable t) {
        Throwable cause = t;
        while (cause != null) {
            if (cause instanceof BusinessRuleException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    /** 并发完成两个任务（开始闸门保证真正同时发起），返回各自是否成功。 */
    private boolean[] runConcurrently(Runnable a, Runnable b) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (Runnable r : List.of(a, b)) {
                futures.add(pool.submit(() -> {
                    start.await();
                    r.run();
                    return null;
                }));
            }
            start.countDown();
            boolean[] ok = new boolean[2];
            for (int i = 0; i < 2; i++) {
                try {
                    futures.get(i).get(30, TimeUnit.SECONDS);
                    ok[i] = true;
                } catch (Exception e) {
                    if (!isBusinessFailure(e)) {
                        throw e;
                    }
                    ok[i] = false;
                }
            }
            return ok;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentDisbursementsNeverExceedApprovedAmount() throws Exception {
        GrantProject project = createProject("C-CAP", "100", "60", "40");
        List<GrantTranche> tranches = trancheRepository
                .findByProjectIdOrderBySequenceNoAsc(project.getId());
        accept(tranches.get(0).getId());
        accept(tranches.get(1).getId());
        // 直接收紧批准额度（模拟预算调减），使两期无法同时容纳，专测运行时总额护栏。
        transactionTemplate.executeWithoutResult(s -> {
            GrantProject p = projectRepository.findById(project.getId()).orElseThrow();
            p.setApprovedAmount(new BigDecimal("70"));
        });

        AtomicInteger seq = new AtomicInteger();
        boolean[] ok = runConcurrently(
                () -> grantService.disburse(tranches.get(0).getId(),
                        "C-CAP-" + seq.incrementAndGet(), "approver", "并发拨款1"),
                () -> grantService.disburse(tranches.get(1).getId(),
                        "C-CAP-" + seq.incrementAndGet(), "approver", "并发拨款2"));

        int successCount = (ok[0] ? 1 : 0) + (ok[1] ? 1 : 0);
        assertThat(successCount).isEqualTo(1);

        GrantProject reloaded = projectRepository.findById(project.getId()).orElseThrow();
        assertThat(reloaded.getTotalDisbursed()).isLessThanOrEqualTo(reloaded.getApprovedAmount());
        assertThat(reloaded.getTotalDisbursed()).isEqualByComparingTo("60");
        long effectiveDisbursements = fundRecordRepository.findByProjectIdOrderByIdAsc(project.getId())
                .stream()
                .filter(r -> r.getRecordType() == FundRecordType.DISBURSEMENT
                        && r.getStatus() != FundRecordStatus.REVOKED)
                .count();
        assertThat(effectiveDisbursements).isEqualTo(1);
    }

    @Test
    void sameTrancheConcurrentDisburseOnlySucceedsOnceEvenWithDifferentBusinessNos() throws Exception {
        GrantProject project = createProject("C-DUP", "100", "100");
        GrantTranche t = trancheRepository.findByProjectIdOrderBySequenceNoAsc(project.getId()).get(0);
        accept(t.getId());

        boolean[] ok = runConcurrently(
                () -> grantService.disburse(t.getId(), "C-DUP-1", "approver", "并发A"),
                () -> grantService.disburse(t.getId(), "C-DUP-2", "approver", "并发B"));

        assertThat(ok[0] ^ ok[1]).as("同一期次的并发拨款只能成功一笔").isTrue();
        GrantProject reloaded = projectRepository.findById(project.getId()).orElseThrow();
        assertThat(reloaded.getTotalDisbursed()).isEqualByComparingTo("100");
        assertThat(fundRecordRepository.findByProjectIdOrderByIdAsc(project.getId())).hasSize(1);
    }

    @Test
    void sameBusinessNoConcurrentDisburseIsIdempotent() throws Exception {
        GrantProject project = createProject("C-IDEM", "100", "100");
        GrantTranche t = trancheRepository.findByProjectIdOrderBySequenceNoAsc(project.getId()).get(0);
        accept(t.getId());

        List<FundRecord> winners = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<FundRecord>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return grantService.disburse(t.getId(), "C-IDEM-SAME", "approver", "幂等并发");
            }));
        }
        start.countDown();
        for (Future<FundRecord> f : futures) {
            winners.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdownNow();

        assertThat(winners).extracting(FundRecord::getId).containsOnly(winners.get(0).getId());
        assertThat(winners.get(0).getId()).isEqualTo(winners.get(1).getId());
        assertThat(fundRecordRepository.findByProjectIdOrderByIdAsc(project.getId())).hasSize(1);
        assertThat(projectRepository.findById(project.getId()).orElseThrow().getTotalDisbursed())
                .isEqualByComparingTo("100");
    }

    @Test
    void concurrentOrderedDisbursementsNeverSkipPriorTranche() throws Exception {
        GrantProject project = createProject("C-ORDER", "100", "40", "60");
        List<GrantTranche> tranches = trancheRepository.findByProjectIdOrderBySequenceNoAsc(project.getId());
        accept(tranches.get(0).getId());
        accept(tranches.get(1).getId());

        // 多轮并发冲击：每轮两期同时申请，第二期失败就在下一轮重试。
        // 任何中间时刻都不允许“第二期已拨款而第一期未拨款”。
        for (int round = 0; round < 10; round++) {
            final int r = round;
            boolean[] ok = runConcurrently(
                    () -> {
                        GrantTranche t1 = trancheRepository.findById(tranches.get(0).getId()).orElseThrow();
                        if (t1.getStatus() == TrancheStatus.ACCEPTED) {
                            grantService.disburse(t1.getId(), "C-ORDER-T1-" + r, "approver", "第一期");
                        }
                    },
                    () -> {
                        GrantTranche t2 = trancheRepository.findById(tranches.get(1).getId()).orElseThrow();
                        if (t2.getStatus() == TrancheStatus.ACCEPTED) {
                            grantService.disburse(t2.getId(), "C-ORDER-T2-" + r, "approver", "第二期");
                        }
                    });

            TrancheStatus s1 = trancheRepository.findById(tranches.get(0).getId()).orElseThrow().getStatus();
            TrancheStatus s2 = trancheRepository.findById(tranches.get(1).getId()).orElseThrow().getStatus();
            boolean t2Disbursed = s2 == TrancheStatus.DISBURSED || s2 == TrancheStatus.PAID;
            boolean t1Disbursed = s1 == TrancheStatus.DISBURSED || s1 == TrancheStatus.PAID;
            assertThat(!t2Disbursed || t1Disbursed)
                    .as("第 %d 轮：第二期不得先于第一期拨款 (s1=%s, s2=%s, ok=%s)", round, s1, s2, java.util.Arrays.toString(ok))
                    .isTrue();

            GrantProject reloaded = projectRepository.findById(project.getId()).orElseThrow();
            assertThat(reloaded.getTotalDisbursed()).isLessThanOrEqualTo(reloaded.getApprovedAmount());

            if (t1Disbursed && t2Disbursed) {
                break;
            }
        }

        // 收敛：两期最终都完成拨款，累计等于批准总额。
        GrantTranche finalT1 = trancheRepository.findById(tranches.get(0).getId()).orElseThrow();
        GrantTranche finalT2 = trancheRepository.findById(tranches.get(1).getId()).orElseThrow();
        assertThat(finalT1.getStatus()).isIn(TrancheStatus.DISBURSED, TrancheStatus.PAID);
        assertThat(finalT2.getStatus()).isIn(TrancheStatus.DISBURSED, TrancheStatus.PAID);
        assertThat(projectRepository.findById(project.getId()).orElseThrow().getTotalDisbursed())
                .isEqualByComparingTo("100");
    }

    @Test
    void concurrentSuspensionAndDisbursementProduceOneConsistentOutcome() throws Exception {
        GrantProject project = createProject("C-SUSP", "100", "100");
        GrantTranche t = trancheRepository.findByProjectIdOrderBySequenceNoAsc(project.getId()).get(0);
        accept(t.getId());

        boolean[] ok = runConcurrently(
                () -> grantService.suspend(project.getId(), "飞行检查发现问题", "officer"),
                () -> grantService.disburse(t.getId(), "C-SUSP-D", "approver", "与暂停并发"));

        boolean suspended = suspensionRepository.existsByProjectIdAndActiveTrue(project.getId());
        TrancheStatus status = trancheRepository.findById(t.getId()).orElseThrow().getStatus();
        long disbursementCount = fundRecordRepository.findByProjectIdOrderByIdAsc(project.getId()).stream()
                .filter(r -> r.getRecordType() == FundRecordType.DISBURSEMENT
                        && r.getStatus() != FundRecordStatus.REVOKED)
                .count();

        if (suspended && disbursementCount > 0) {
            // 暂停先生效：拨款必然失败；两者并存只可能是拨款先提交、暂停后发起。
            assertThat(status).isEqualTo(TrancheStatus.DISBURSED);
        } else if (suspended) {
            assertThat(status).isEqualTo(TrancheStatus.ACCEPTED);
            assertThat(disbursementCount).isZero();
        }
        assertThat(disbursementCount).isLessThanOrEqualTo(1);
        assertThat(projectRepository.findById(project.getId()).orElseThrow().getTotalDisbursed())
                .isLessThanOrEqualTo(new BigDecimal("100"));
    }

    @Test
    void mixedConcurrentDecisionsAlwaysLeaveConsistentLedger() throws Exception {
        GrantProject project = createProject("C-MIX", "200", "50", "50", "100");
        List<GrantTranche> tranches = trancheRepository
                .findByProjectIdOrderBySequenceNoAsc(project.getId());

        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger counter = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        // 线程 1：依次提交并验收通过各期。
        futures.add(pool.submit(() -> {
            start.await();
            for (GrantTranche t : tranches) {
                try {
                    grantService.submitDeliverable(t.getId(), "证据", "pi");
                    grantService.review(t.getId(), true, "reviewer", "通过");
                } catch (BusinessRuleException ignored) {
                    // 状态已被推进时忽略。
                }
            }
            return null;
        }));
        // 线程 2：尝试按序拨款（失败可重试）。
        futures.add(pool.submit(() -> {
            start.await();
            for (int attempt = 0; attempt < 30; attempt++) {
                for (GrantTranche t : tranches) {
                    Long id = trancheRepository.findById(t.getId()).orElseThrow().getId();
                    GrantTranche fresh = trancheRepository.findById(id).orElseThrow();
                    if (fresh.getStatus() == TrancheStatus.ACCEPTED) {
                        try {
                            grantService.disburse(id, "C-MIX-" + counter.incrementAndGet(),
                                    "approver", "混合并发拨款");
                        } catch (BusinessRuleException ignored) {
                            // 暂停或前置期次未就绪，稍后重试。
                        }
                    }
                }
            }
            return null;
        }));
        // 线程 3：暂停然后解除。
        futures.add(pool.submit(() -> {
            start.await();
            try {
                var s = grantService.suspend(project.getId(), "临时合规检查", "officer");
                grantService.liftSuspension(s.getId(), "officer", "检查通过");
            } catch (BusinessRuleException ignored) {
                // 与其他暂停竞争落败。
            }
            return null;
        }));
        // 线程 4：拨款后立即撤销未支付拨款、再重新拨款。
        futures.add(pool.submit(() -> {
            start.await();
            for (int attempt = 0; attempt < 20; attempt++) {
                List<FundRecord> records = fundRecordRepository
                        .findByProjectIdOrderByIdAsc(project.getId());
                FundRecord unpaid = records.stream()
                        .filter(r -> r.getRecordType() == FundRecordType.DISBURSEMENT
                                && r.getStatus() == FundRecordStatus.UNPAID)
                        .findFirst().orElse(null);
                if (unpaid != null) {
                    try {
                        grantService.revoke(unpaid.getId(), "manager", "并发撤销");
                    } catch (BusinessRuleException ignored) {
                        // 与支付/其他撤销竞争落败。
                    }
                }
            }
            return null;
        }));

        start.countDown();
        for (Future<?> f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        // 最终一致性核对：台账金额与项目累计字段严格相等。
        GrantProject reloaded = projectRepository.findById(project.getId()).orElseThrow();
        BigDecimal effectiveDisbursed = fundRecordRepository.findByProjectIdOrderByIdAsc(project.getId())
                .stream()
                .filter(r -> r.getRecordType() == FundRecordType.DISBURSEMENT
                        && r.getStatus() != FundRecordStatus.REVOKED)
                .map(FundRecord::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal recovered = fundRecordRepository.findByProjectIdOrderByIdAsc(project.getId()).stream()
                .filter(r -> r.getRecordType() == FundRecordType.RECOVERY)
                .map(FundRecord::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(reloaded.getTotalDisbursed()).isEqualByComparingTo(effectiveDisbursed);
        assertThat(reloaded.getTotalRecovered()).isEqualByComparingTo(recovered);
        assertThat(reloaded.getTotalDisbursed()).isLessThanOrEqualTo(reloaded.getApprovedAmount());
    }
}
