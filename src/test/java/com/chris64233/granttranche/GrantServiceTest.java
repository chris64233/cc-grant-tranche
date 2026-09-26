package com.chris64233.granttranche;

import com.chris64233.granttranche.domain.ComplianceHold;
import com.chris64233.granttranche.domain.Disbursement;
import com.chris64233.granttranche.domain.DisbursementStatus;
import com.chris64233.granttranche.domain.FundRecord;
import com.chris64233.granttranche.domain.FundRecordType;
import com.chris64233.granttranche.domain.GrantProject;
import com.chris64233.granttranche.domain.TrancheStatus;
import com.chris64233.granttranche.error.BusinessException;
import com.chris64233.granttranche.repo.ComplianceHoldRepository;
import com.chris64233.granttranche.repo.DisbursementRepository;
import com.chris64233.granttranche.repo.FundRecordRepository;
import com.chris64233.granttranche.repo.GrantProjectRepository;
import com.chris64233.granttranche.repo.TrancheRepository;
import com.chris64233.granttranche.service.GrantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class GrantServiceTest {

    @Autowired
    GrantService service;
    @Autowired
    GrantProjectRepository projectRepository;
    @Autowired
    TrancheRepository trancheRepository;
    @Autowired
    ComplianceHoldRepository holdRepository;
    @Autowired
    DisbursementRepository disbursementRepository;
    @Autowired
    FundRecordRepository fundRecordRepository;

    @BeforeEach
    void clean() {
        fundRecordRepository.deleteAll();
        disbursementRepository.deleteAll();
        holdRepository.deleteAll();
        trancheRepository.deleteAll();
        projectRepository.deleteAll();
    }

    private GrantProject newProject(String approved, int... plannedAmounts) {
        var specs = java.util.stream.IntStream.range(0, plannedAmounts.length)
                .mapToObj(i -> new GrantService.TrancheSpec(i + 1,
                        new BigDecimal(plannedAmounts[i]), "成果" + (i + 1), "预算条件" + (i + 1)))
                .toList();
        return service.createProject("重点项目", new BigDecimal(approved), specs);
    }

    private void accept(Long projectId, int sequence) {
        service.submitDeliverable(projectId, sequence, "证据-" + sequence, "张三");
        service.decideAcceptance(projectId, sequence, true, "李验收", "成果达标");
    }

    // ---------- 项目与期次规则 ----------

    @Test
    void 全部期次金额不得超过批准金额() {
        assertThatThrownBy(() -> service.createProject("超支项目", new BigDecimal("1000"), List.of(
                new GrantService.TrancheSpec(1, new BigDecimal("600"), "成果1", "条件1"),
                new GrantService.TrancheSpec(2, new BigDecimal("500"), "成果2", "条件2"))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不得超过项目批准金额");
    }

    @Test
    void 期次序号不得重复() {
        assertThatThrownBy(() -> service.createProject("重复期次", new BigDecimal("1000"), List.of(
                new GrantService.TrancheSpec(1, new BigDecimal("100"), "成果1", "条件1"),
                new GrantService.TrancheSpec(1, new BigDecimal("100"), "成果2", "条件2"))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("期次序号重复");
    }

    // ---------- 拨款批准规则 ----------

    @Test
    void 完整流程_提交验收拨款_余额与台账一致() {
        GrantProject project = newProject("1000", 400, 600);
        accept(project.getId(), 1);

        Disbursement d = service.approveDisbursement(project.getId(), 1, "BN-1", null, "王财务", "首期拨款");

        assertThat(d.getAmount()).isEqualByComparingTo("400");
        assertThat(d.getStatus()).isEqualTo(DisbursementStatus.APPROVED);
        GrantProject reloaded = service.getProject(project.getId());
        assertThat(reloaded.getDisbursedAmount()).isEqualByComparingTo("400");
        assertThat(reloaded.getRemainingAmount()).isEqualByComparingTo("600");

        List<FundRecord> ledger = service.listFundRecords(project.getId());
        assertThat(ledger).hasSize(1);
        assertThat(ledger.get(0).getType()).isEqualTo(FundRecordType.DISBURSEMENT);
        assertThat(ledger.get(0).getOperator()).isEqualTo("王财务");
        assertThat(ledger.get(0).getReason()).isEqualTo("首期拨款");
    }

    @Test
    void 成果未验收通过不得拨款() {
        GrantProject project = newProject("1000", 400, 600);
        service.submitDeliverable(project.getId(), 1, "证据", "张三");

        assertThatThrownBy(() -> service.approveDisbursement(project.getId(), 1, "BN-1", null, "王财务", "拨款"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("未验收通过");
    }

    @Test
    void 验收驳回后不得拨款_重新提交通过后可拨款() {
        GrantProject project = newProject("1000", 400, 600);
        service.submitDeliverable(project.getId(), 1, "证据v1", "张三");
        service.decideAcceptance(project.getId(), 1, false, "李验收", "成果不达标");

        assertThatThrownBy(() -> service.approveDisbursement(project.getId(), 1, "BN-1", null, "王财务", "拨款"))
                .isInstanceOf(BusinessException.class);

        service.submitDeliverable(project.getId(), 1, "证据v2", "张三");
        service.decideAcceptance(project.getId(), 1, true, "李验收", "整改后达标");
        Disbursement d = service.approveDisbursement(project.getId(), 1, "BN-1", null, "王财务", "拨款");
        assertThat(d.getStatus()).isEqualTo(DisbursementStatus.APPROVED);
    }

    @Test
    void 活动中合规暂停阻止拨款_解除后可拨款() {
        GrantProject project = newProject("1000", 400, 600);
        accept(project.getId(), 1);
        ComplianceHold hold = service.createHold(project.getId(), "审计发现问题", "赵合规");

        assertThatThrownBy(() -> service.approveDisbursement(project.getId(), 1, "BN-1", null, "王财务", "拨款"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("合规暂停");

        service.liftHold(project.getId(), hold.getId(), "赵合规", "整改完成");
        Disbursement d = service.approveDisbursement(project.getId(), 1, "BN-1", null, "王财务", "拨款");
        assertThat(d.getStatus()).isEqualTo(DisbursementStatus.APPROVED);
    }

    @Test
    void 不得绕过前置期次() {
        GrantProject project = newProject("1000", 400, 600);
        accept(project.getId(), 1);
        accept(project.getId(), 2);

        assertThatThrownBy(() -> service.approveDisbursement(project.getId(), 2, "BN-2", null, "王财务", "二期"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("前置期次");

        service.approveDisbursement(project.getId(), 1, "BN-1", null, "王财务", "一期");
        Disbursement d2 = service.approveDisbursement(project.getId(), 2, "BN-2", null, "王财务", "二期");
        assertThat(d2.getAmount()).isEqualByComparingTo("600");
        assertThat(service.getProject(project.getId()).getDisbursedAmount()).isEqualByComparingTo("1000");
    }

    @Test
    void 拨款金额不得超过期次计划金额() {
        GrantProject project = newProject("1000", 400, 600);
        accept(project.getId(), 1);

        assertThatThrownBy(() -> service.approveDisbursement(
                project.getId(), 1, "BN-1", new BigDecimal("401"), "王财务", "超额"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不得超过期次计划金额");
    }

    @Test
    void 业务号幂等_重复提交不重复占额() {
        GrantProject project = newProject("1000", 400, 600);
        accept(project.getId(), 1);

        Disbursement first = service.approveDisbursement(project.getId(), 1, "BN-1", null, "王财务", "拨款");
        Disbursement second = service.approveDisbursement(project.getId(), 1, "BN-1", null, "王财务", "拨款");

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(service.getProject(project.getId()).getDisbursedAmount()).isEqualByComparingTo("400");
        assertThat(service.listFundRecords(project.getId())).hasSize(1);
    }

    // ---------- 撤销与追回 ----------

    @Test
    void 未支付拨款可撤销并恢复额度() {
        GrantProject project = newProject("1000", 400, 600);
        accept(project.getId(), 1);
        Disbursement d = service.approveDisbursement(project.getId(), 1, "BN-1", null, "王财务", "拨款");

        service.revokeDisbursement(project.getId(), d.getId(), "王财务", "计划调整");

        GrantProject reloaded = service.getProject(project.getId());
        assertThat(reloaded.getDisbursedAmount()).isEqualByComparingTo("0");
        assertThat(reloaded.getRemainingAmount()).isEqualByComparingTo("1000");
        List<FundRecord> ledger = service.listFundRecords(project.getId());
        assertThat(ledger).extracting(FundRecord::getType)
                .containsExactly(FundRecordType.DISBURSEMENT, FundRecordType.REVERSAL);
        // 撤销后期次可重新拨款
        acceptAgainAndApprove(project.getId());
    }

    private void acceptAgainAndApprove(Long projectId) {
        Disbursement d = service.approveDisbursement(projectId, 1, "BN-2", null, "王财务", "重新拨款");
        assertThat(d.getStatus()).isEqualTo(DisbursementStatus.APPROVED);
    }

    @Test
    void 已支付拨款不能撤销只能追回_原资金记录不变() {
        GrantProject project = newProject("1000", 400, 600);
        accept(project.getId(), 1);
        Disbursement d = service.approveDisbursement(project.getId(), 1, "BN-1", null, "王财务", "拨款");
        service.confirmPayment(project.getId(), d.getId(), "出纳");

        assertThatThrownBy(() -> service.revokeDisbursement(project.getId(), d.getId(), "王财务", "想撤销"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能撤销");

        FundRecord clawback = service.clawback(project.getId(), d.getId(), "CB-1",
                new BigDecimal("150"), "审计", "追回部分资金");

        assertThat(clawback.getType()).isEqualTo(FundRecordType.CLAWBACK);
        GrantProject reloaded = service.getProject(project.getId());
        assertThat(reloaded.getDisbursedAmount()).isEqualByComparingTo("250");

        List<FundRecord> ledger = service.listFundRecords(project.getId());
        assertThat(ledger).hasSize(2);
        FundRecord original = ledger.get(0);
        assertThat(original.getType()).isEqualTo(FundRecordType.DISBURSEMENT);
        assertThat(original.getAmount()).isEqualByComparingTo("400");
        // 拨款状态保持已支付
        assertThat(service.listDisbursements(project.getId()).get(0).getStatus())
                .isEqualTo(DisbursementStatus.PAID);
    }

    @Test
    void 追回金额不得超过剩余净额_且幂等() {
        GrantProject project = newProject("1000", 400, 600);
        accept(project.getId(), 1);
        Disbursement d = service.approveDisbursement(project.getId(), 1, "BN-1", null, "王财务", "拨款");
        service.confirmPayment(project.getId(), d.getId(), "出纳");
        service.clawback(project.getId(), d.getId(), "CB-1", new BigDecimal("300"), "审计", "追回");

        assertThatThrownBy(() -> service.clawback(project.getId(), d.getId(), "CB-2",
                new BigDecimal("200"), "审计", "再追回"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("剩余净额");

        FundRecord again = service.clawback(project.getId(), d.getId(), "CB-1",
                new BigDecimal("300"), "审计", "追回");
        assertThat(service.listFundRecords(project.getId())).hasSize(2);
        assertThat(again.getBusinessNo()).isEqualTo("CB-1");
        assertThat(service.getProject(project.getId()).getDisbursedAmount()).isEqualByComparingTo("100");
    }

    @Test
    void 未支付拨款不能追回() {
        GrantProject project = newProject("1000", 400, 600);
        accept(project.getId(), 1);
        Disbursement d = service.approveDisbursement(project.getId(), 1, "BN-1", null, "王财务", "拨款");

        assertThatThrownBy(() -> service.clawback(project.getId(), d.getId(), "CB-1",
                new BigDecimal("100"), "审计", "追回"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("只有已支付拨款可以追回");
    }

    // ---------- 并发一致性 ----------

    @Test
    void 并发批准同一期次_只有一笔成功且不超额() throws Exception {
        GrantProject project = newProject("1000", 400, 600);
        accept(project.getId(), 1);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            int n = i;
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    service.approveDisbursement(project.getId(), 1, "BN-C-" + n, null, "王财务", "并发拨款");
                    succeeded.incrementAndGet();
                } catch (BusinessException e) {
                    failed.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        ready.await();
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        assertThat(succeeded.get()).isEqualTo(1);
        assertThat(failed.get()).isEqualTo(threads - 1);
        assertThat(service.getProject(project.getId()).getDisbursedAmount()).isEqualByComparingTo("400");
        assertThat(service.listFundRecords(project.getId())).hasSize(1);
    }

    @Test
    void 并发相同业务号_只产生一笔拨款() throws Exception {
        GrantProject project = newProject("1000", 400, 600);
        accept(project.getId(), 1);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        var ids = new java.util.concurrent.ConcurrentLinkedQueue<Long>();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    ids.add(service.approveDisbursement(
                            project.getId(), 1, "BN-SAME", null, "王财务", "幂等").getId());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        ready.await();
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        assertThat(ids).hasSize(threads);
        assertThat(ids).containsOnly(ids.peek());
        assertThat(service.getProject(project.getId()).getDisbursedAmount()).isEqualByComparingTo("400");
        assertThat(service.listFundRecords(project.getId())).hasSize(1);
    }

    @Test
    void 并发拨款与合规暂停_结果一致() throws Exception {
        // 无论锁顺序如何，最终状态必须自洽：
        // 若拨款成功则其必发生在暂停建立之前；若失败则必因暂停。
        GrantProject project = newProject("1000", 400, 600);
        accept(project.getId(), 1);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        var approved = new AtomicInteger();
        var rejected = new AtomicInteger();
        pool.submit(() -> {
            ready.countDown();
            try {
                go.await();
                service.approveDisbursement(project.getId(), 1, "BN-1", null, "王财务", "拨款");
                approved.incrementAndGet();
            } catch (BusinessException e) {
                rejected.incrementAndGet();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        pool.submit(() -> {
            ready.countDown();
            try {
                go.await();
                service.createHold(project.getId(), "合规检查", "赵合规");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        ready.await();
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        assertThat(approved.get() + rejected.get()).isEqualTo(1);
        GrantProject reloaded = service.getProject(project.getId());
        boolean holdActive = service.listHolds(project.getId()).stream().anyMatch(ComplianceHold::isActive);
        assertThat(holdActive).isTrue();
        if (approved.get() == 1) {
            assertThat(reloaded.getDisbursedAmount()).isEqualByComparingTo("400");
            assertThat(service.listFundRecords(project.getId())).hasSize(1);
        } else {
            assertThat(reloaded.getDisbursedAmount()).isEqualByComparingTo("0");
            assertThat(service.listFundRecords(project.getId())).isEmpty();
        }
    }

    @Test
    void 期次证据与验收决定可查询() {
        GrantProject project = newProject("1000", 400, 600);
        accept(project.getId(), 1);

        var tranches = service.listTranches(project.getId());
        assertThat(tranches).hasSize(2);
        var first = tranches.get(0);
        assertThat(first.getStatus()).isEqualTo(TrancheStatus.ACCEPTED);
        assertThat(first.getDeliverableEvidence()).isEqualTo("证据-1");
        assertThat(first.getSubmittedBy()).isEqualTo("张三");
        assertThat(first.getAcceptanceDecidedBy()).isEqualTo("李验收");
        assertThat(first.getAcceptanceReason()).isEqualTo("成果达标");
    }
}
