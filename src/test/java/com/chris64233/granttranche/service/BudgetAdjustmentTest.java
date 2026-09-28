package com.chris64233.granttranche.service;

import com.chris64233.granttranche.domain.AdjustmentStatus;
import com.chris64233.granttranche.domain.BudgetAdjustment;
import com.chris64233.granttranche.domain.FundRecord;
import com.chris64233.granttranche.domain.GrantProject;
import com.chris64233.granttranche.domain.GrantTranche;
import com.chris64233.granttranche.domain.TrancheStatus;
import com.chris64233.granttranche.dto.AdjustmentView;
import com.chris64233.granttranche.dto.CreateProjectRequest;
import com.chris64233.granttranche.dto.ProjectBalance;
import com.chris64233.granttranche.repo.BudgetAdjustmentRepository;
import com.chris64233.granttranche.repo.GrantProjectRepository;
import com.chris64233.granttranche.repo.GrantTrancheRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 未拨付预算期次间调整的业务规则、留痕、幂等与并发一致性测试。
 */
@SpringBootTest
class BudgetAdjustmentTest {

    @Autowired
    private GrantService grantService;
    @Autowired
    private GrantProjectRepository projectRepository;
    @Autowired
    private GrantTrancheRepository trancheRepository;
    @Autowired
    private BudgetAdjustmentRepository adjustmentRepository;

    private static CreateProjectRequest.TrancheSpec tranche(int seq, String amount) {
        return new CreateProjectRequest.TrancheSpec(seq, new BigDecimal(amount),
                "成果" + seq, "预算条件" + seq);
    }

    private GrantProject createProject(String code) {
        return grantService.createProject(new CreateProjectRequest(
                code, "项目" + code, new BigDecimal("100"),
                List.of(tranche(1, "40"), tranche(2, "60"))));
    }

    private List<GrantTranche> tranches(Long projectId) {
        return trancheRepository.findByProjectIdOrderBySequenceNoAsc(projectId);
    }

    private GrantTranche accept(Long trancheId) {
        grantService.submitDeliverable(trancheId, "成果.pdf", "pi-zhang");
        grantService.review(trancheId, true, "reviewer-li", "通过");
        return trancheRepository.findById(trancheId).orElseThrow();
    }

    private GrantTranche reload(GrantTranche t) {
        return trancheRepository.findById(t.getId()).orElseThrow();
    }

    @Test
    void confirmedAdjustmentMovesOnlyUndisbursedAmountAndKeepsProjectTotal() {
        GrantProject project = createProject("A-HAPPY");
        List<GrantTranche> ts = tranches(project.getId());

        BudgetAdjustment adjustment = grantService.requestAdjustment(
                ts.get(0).getId(), ts.get(1).getId(), new BigDecimal("10"),
                "ADJ-HAPPY-1", "研究节奏调整，后移 10", "manager-qian");

        // 申请阶段不动任何金额。
        assertThat(adjustment.getStatus()).isEqualTo(AdjustmentStatus.PROPOSED);
        assertThat(reload(ts.get(0)).getPlannedAmount()).isEqualByComparingTo("40");
        assertThat(reload(ts.get(1)).getPlannedAmount()).isEqualByComparingTo("60");

        BudgetAdjustment confirmed = grantService.confirmAdjustment(adjustment.getId(), "approver-wang");

        // 两期次等额转移。
        assertThat(reload(ts.get(0)).getPlannedAmount()).isEqualByComparingTo("30");
        assertThat(reload(ts.get(1)).getPlannedAmount()).isEqualByComparingTo("70");
        // 项目批准总额、累计拨款不变，可拨余额不变，且不产生任何资金记录。
        GrantProject reloaded = projectRepository.findById(project.getId()).orElseThrow();
        assertThat(reloaded.getApprovedAmount()).isEqualByComparingTo("100");
        assertThat(reloaded.getTotalDisbursed()).isEqualByComparingTo("0");
        ProjectBalance balance = grantService.getBalance(project.getId());
        assertThat(balance.availableBalance()).isEqualByComparingTo("100");
        // 留痕：原因、申请人、批准人、调整前后金额齐备。
        assertThat(confirmed.getStatus()).isEqualTo(AdjustmentStatus.CONFIRMED);
        assertThat(confirmed.getReason()).isEqualTo("研究节奏调整，后移 10");
        assertThat(confirmed.getRequestedBy()).isEqualTo("manager-qian");
        assertThat(confirmed.getConfirmedBy()).isEqualTo("approver-wang");
        assertThat(confirmed.getConfirmedAt()).isNotNull();
        assertThat(confirmed.getFromAmountBefore()).isEqualByComparingTo("40");
        assertThat(confirmed.getFromAmountAfter()).isEqualByComparingTo("30");
        assertThat(confirmed.getToAmountBefore()).isEqualByComparingTo("60");
        assertThat(confirmed.getToAmountAfter()).isEqualByComparingTo("70");

        List<AdjustmentView> views = grantService.listAdjustments(project.getId());
        assertThat(views).hasSize(1);
        assertThat(views.get(0).amount()).isEqualByComparingTo("10");
        assertThat(views.get(0).fromSequenceNo()).isEqualTo(1);
        assertThat(views.get(0).toSequenceNo()).isEqualTo(2);
    }

    @Test
    void acceptedButUndisbursedTrancheCanStillBeAdjustedAndLaterDisbursesNewAmount() {
        GrantProject project = createProject("A-ACCEPTED");
        List<GrantTranche> ts = tranches(project.getId());
        // 第一期已验收通过但尚未批准拨款——仍属"尚未使用"，可调减。
        GrantTranche t1 = accept(ts.get(0).getId());

        BudgetAdjustment adjustment = grantService.requestAdjustment(
                t1.getId(), ts.get(1).getId(), new BigDecimal("10"),
                "ADJ-ACC-1", "验收后微调", "manager-qian");
        grantService.confirmAdjustment(adjustment.getId(), "approver-wang");

        assertThat(reload(t1).getPlannedAmount()).isEqualByComparingTo("30");
        // 之后拨款按调整后金额占用额度。
        FundRecord record = grantService.disburse(t1.getId(), "ADJ-ACC-D", "approver-wang", "调整后拨款");
        assertThat(record.getAmount()).isEqualByComparingTo("30");
        assertThat(projectRepository.findById(project.getId()).orElseThrow().getTotalDisbursed())
                .isEqualByComparingTo("30");
    }

    @Test
    void disbursingTrancheCannotBeSourceOrTarget() {
        GrantProject project = createProject("A-DISB");
        List<GrantTranche> ts = tranches(project.getId());
        GrantTranche t1 = accept(ts.get(0).getId());
        grantService.disburse(t1.getId(), "ADJ-DISB-D", "approver-wang", "第一期");

        // 正在拨付（DISBURSED，未支付）的期次不能调出。
        assertThatThrownBy(() -> grantService.requestAdjustment(
                t1.getId(), ts.get(1).getId(), new BigDecimal("10"),
                "ADJ-DISB-1", "r", "m"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("不得调整");
        // 也不能调入。
        assertThatThrownBy(() -> grantService.requestAdjustment(
                ts.get(1).getId(), t1.getId(), new BigDecimal("10"),
                "ADJ-DISB-2", "r", "m"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("不得调整");
        // 金额未被改动。
        assertThat(reload(t1).getPlannedAmount()).isEqualByComparingTo("40");
    }

    @Test
    void paidOrRecoveredMoneyCannotBeMoved() {
        GrantProject project = createProject("A-PAID");
        List<GrantTranche> ts = tranches(project.getId());
        GrantTranche t1 = accept(ts.get(0).getId());
        FundRecord record = grantService.disburse(t1.getId(), "ADJ-PAID-D", "approver", "拨款");
        grantService.markPaid(record.getId(), "cashier");
        // 已支付并登记追回的拨款，其原期次已进入 PAID，任何调整都被拒绝。
        grantService.recover(record.getId(), "ADJ-PAID-R", new BigDecimal("5"), "auditor", "部分追回");

        assertThatThrownBy(() -> grantService.requestAdjustment(
                t1.getId(), ts.get(1).getId(), new BigDecimal("1"),
                "ADJ-PAID-1", "r", "m"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("不得调整");
        assertThat(reload(t1).getPlannedAmount()).isEqualByComparingTo("40");
        assertThat(projectRepository.findById(project.getId()).orElseThrow().getTotalDisbursed())
                .isEqualByComparingTo("40");
    }

    @Test
    void requestRejectsOverAmountSameTrancheCrossProjectAndNonPositive() {
        GrantProject p1 = createProject("A-VALID1");
        GrantProject p2 = createProject("A-VALID2");
        List<GrantTranche> t1 = tranches(p1.getId());
        List<GrantTranche> t2 = tranches(p2.getId());

        // 调整额超过调出期次尚未拨付余额。
        assertThatThrownBy(() -> grantService.requestAdjustment(
                t1.get(0).getId(), t1.get(1).getId(), new BigDecimal("41"),
                "ADJ-V-1", "r", "m"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("超过调出期次");
        // 同一期次不能既是调出又是调入。
        assertThatThrownBy(() -> grantService.requestAdjustment(
                t1.get(0).getId(), t1.get(0).getId(), new BigDecimal("10"),
                "ADJ-V-2", "r", "m"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("不能相同");
        // 跨项目。
        assertThatThrownBy(() -> grantService.requestAdjustment(
                t1.get(0).getId(), t2.get(1).getId(), new BigDecimal("10"),
                "ADJ-V-3", "r", "m"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("同一个项目");
        // 非正数。
        assertThatThrownBy(() -> grantService.requestAdjustment(
                t1.get(0).getId(), t1.get(1).getId(), BigDecimal.ZERO,
                "ADJ-V-4", "r", "m"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("必须为正数");
    }

    @Test
    void suspensionInvalidatesPendingPlansAndLiftRequiresFreshPlan() {
        GrantProject project = createProject("A-SUSP");
        List<GrantTranche> ts = tranches(project.getId());
        BudgetAdjustment pending = grantService.requestAdjustment(
                ts.get(0).getId(), ts.get(1).getId(), new BigDecimal("10"),
                "ADJ-SUSP-1", "后移预算", "manager-qian");

        // 暂停把待确认方案作废并留痕，金额不动。
        var suspension = grantService.suspend(project.getId(), "飞行检查", "officer-he");
        BudgetAdjustment stored = adjustmentRepository.findById(pending.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(AdjustmentStatus.INVALIDATED);
        assertThat(stored.getInvalidatedReason()).contains("合规暂停");
        assertThat(stored.getInvalidatedAt()).isNotNull();
        assertThat(reload(ts.get(0)).getPlannedAmount()).isEqualByComparingTo("40");

        // 暂停期间不能确认。
        assertThatThrownBy(() -> grantService.confirmAdjustment(pending.getId(), "approver"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("已作废");

        // 恢复后旧方案仍不能沿用，必须重新申请。
        grantService.liftSuspension(suspension.getId(), "officer-he", "整改完成");
        assertThatThrownBy(() -> grantService.confirmAdjustment(pending.getId(), "approver"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("已作废");
        BudgetAdjustment fresh = grantService.requestAdjustment(
                ts.get(0).getId(), ts.get(1).getId(), new BigDecimal("10"),
                "ADJ-SUSP-2", "恢复后重新申请", "manager-qian");
        grantService.confirmAdjustment(fresh.getId(), "approver-wang");
        assertThat(reload(ts.get(0)).getPlannedAmount()).isEqualByComparingTo("30");
        assertThat(reload(ts.get(1)).getPlannedAmount()).isEqualByComparingTo("70");
    }

    @Test
    void confirmAfterTrancheDisbursedSinceRequestInvalidatesOldPlan() {
        GrantProject project = createProject("A-RACE");
        List<GrantTranche> ts = tranches(project.getId());
        GrantTranche t1 = accept(ts.get(0).getId());
        BudgetAdjustment pending = grantService.requestAdjustment(
                t1.getId(), ts.get(1).getId(), new BigDecimal("10"),
                "ADJ-RACE-1", "申请后先拨款", "manager-qian");

        // 申请之后、确认之前，调出期次被拨款。
        grantService.disburse(t1.getId(), "ADJ-RACE-D", "approver", "抢先拨款");

        assertThatThrownBy(() -> grantService.confirmAdjustment(pending.getId(), "approver"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("已作废");
        // 旧方案失效留痕；已拨金额保留在原期次原用途，计划金额未被改动。
        assertThat(adjustmentRepository.findById(pending.getId()).orElseThrow().getStatus())
                .isEqualTo(AdjustmentStatus.INVALIDATED);
        assertThat(reload(t1).getStatus()).isEqualTo(TrancheStatus.DISBURSED);
        assertThat(reload(t1).getPlannedAmount()).isEqualByComparingTo("40");
        assertThat(reload(ts.get(1)).getPlannedAmount()).isEqualByComparingTo("60");
    }

    @Test
    void confirmRevalidatesBalancesAndCannotReuseStalePlan() {
        GrantProject project = createProject("A-STALE");
        List<GrantTranche> ts = tranches(project.getId());
        BudgetAdjustment first = grantService.requestAdjustment(
                ts.get(0).getId(), ts.get(1).getId(), new BigDecimal("10"),
                "ADJ-STALE-1", "第一次", "m");
        BudgetAdjustment second = grantService.requestAdjustment(
                ts.get(0).getId(), ts.get(1).getId(), new BigDecimal("5"),
                "ADJ-STALE-2", "第二次（旧余额快照）", "m");

        grantService.confirmAdjustment(first.getId(), "approver");
        assertThat(reload(ts.get(0)).getPlannedAmount()).isEqualByComparingTo("30");

        // 第二个方案的余额快照已过期，确认时重新检查并作废，金额不再变化。
        assertThatThrownBy(() -> grantService.confirmAdjustment(second.getId(), "approver"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("已作废");
        assertThat(adjustmentRepository.findById(second.getId()).orElseThrow().getStatus())
                .isEqualTo(AdjustmentStatus.INVALIDATED);
        assertThat(reload(ts.get(0)).getPlannedAmount()).isEqualByComparingTo("30");
        assertThat(reload(ts.get(1)).getPlannedAmount()).isEqualByComparingTo("70");
    }

    @Test
    void requestAndConfirmAreIdempotentByBusinessNo() {
        GrantProject project = createProject("A-IDEM");
        List<GrantTranche> ts = tranches(project.getId());
        BudgetAdjustment first = grantService.requestAdjustment(
                ts.get(0).getId(), ts.get(1).getId(), new BigDecimal("10"),
                "ADJ-IDEM-1", "r", "m");
        BudgetAdjustment repeat = grantService.requestAdjustment(
                ts.get(0).getId(), ts.get(1).getId(), new BigDecimal("10"),
                "ADJ-IDEM-1", "重复提交", "m2");
        assertThat(repeat.getId()).isEqualTo(first.getId());
        assertThat(adjustmentRepository.findByProjectIdOrderByIdDesc(project.getId())).hasSize(1);

        // 同业务号但参数不同 → 冲突。
        assertThatThrownBy(() -> grantService.requestAdjustment(
                ts.get(0).getId(), ts.get(1).getId(), new BigDecimal("20"),
                "ADJ-IDEM-1", "r", "m"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("已被其他调整记录占用");

        // 业务号与资金记录冲突。
        GrantTranche t1 = accept(ts.get(0).getId());
        grantService.disburse(t1.getId(), "ADJ-IDEM-D", "approver", "拨款");
        // 注意：该期次拨款后已不能调出，这里用一个新项目验证业务号跨对象占用。
        GrantProject other = createProject("A-IDEM2");
        List<GrantTranche> ot = tranches(other.getId());
        assertThatThrownBy(() -> grantService.requestAdjustment(
                ot.get(0).getId(), ot.get(1).getId(), new BigDecimal("5"),
                "ADJ-IDEM-D", "r", "m"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("已被其他资金记录占用");

        // 重复确认不重复转移金额。
        GrantProject p3 = createProject("A-IDEM3");
        List<GrantTranche> t3 = tranches(p3.getId());
        BudgetAdjustment a3 = grantService.requestAdjustment(
                t3.get(0).getId(), t3.get(1).getId(), new BigDecimal("10"),
                "ADJ-IDEM-3", "r", "m");
        BudgetAdjustment c1 = grantService.confirmAdjustment(a3.getId(), "approver");
        BudgetAdjustment c2 = grantService.confirmAdjustment(a3.getId(), "approver");
        assertThat(c2.getId()).isEqualTo(c1.getId());
        assertThat(reload(t3.get(0)).getPlannedAmount()).isEqualByComparingTo("30");
        assertThat(reload(t3.get(1)).getPlannedAmount()).isEqualByComparingTo("70");
    }

    @Test
    void concurrentDisburseAndConfirmOnlyOneSucceedsWithFullRollback() throws Exception {
        GrantProject project = createProject("A-CONC");
        List<GrantTranche> ts = tranches(project.getId());
        GrantTranche t1 = accept(ts.get(0).getId());
        BudgetAdjustment adjustment = grantService.requestAdjustment(
                t1.getId(), ts.get(1).getId(), new BigDecimal("10"),
                "ADJ-CONC-1", "与拨款并发", "manager-qian");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<?> disburseFuture = pool.submit(() -> {
            start.await();
            grantService.disburse(t1.getId(), "ADJ-CONC-D", "approver", "并发拨款");
            return null;
        });
        Future<?> confirmFuture = pool.submit(() -> {
            start.await();
            grantService.confirmAdjustment(adjustment.getId(), "approver");
            return null;
        });
        start.countDown();

        boolean disburseOk;
        boolean confirmOk;
        try {
            disburseFuture.get(30, TimeUnit.SECONDS);
            disburseOk = true;
        } catch (Exception e) {
            disburseOk = false;
        }
        try {
            confirmFuture.get(30, TimeUnit.SECONDS);
            confirmOk = true;
        } catch (Exception e) {
            confirmOk = false;
        }
        pool.shutdownNow();

        // 真正重叠时恰好一个成功；若调度使两者完全不重叠（确认先提交、拨款在其后才读到新版本），
        // 则两者合法串行成功（拨款按调整后金额）。无论哪种结局都必须一致，不允许半成功。
        GrantProject reloaded = projectRepository.findById(project.getId()).orElseThrow();
        GrantTranche t1After = reload(t1);
        GrantTranche t2After = reload(ts.get(1));
        // 两期次计划金额合计始终等于原总额，累计拨款不超过批准总额。
        assertThat(t1After.getPlannedAmount().add(t2After.getPlannedAmount()))
                .isEqualByComparingTo("100");
        assertThat(reloaded.getTotalDisbursed())
                .isLessThanOrEqualTo(reloaded.getApprovedAmount());

        if (!disburseOk && confirmOk) {
            // 调整在重叠中胜出：30/70，方案已确认；拨款落败未占用额度。
            assertThat(t1After.getPlannedAmount()).isEqualByComparingTo("30");
            assertThat(t2After.getPlannedAmount()).isEqualByComparingTo("70");
            assertThat(reloaded.getTotalDisbursed()).isEqualByComparingTo("0");
            assertThat(adjustmentRepository.findById(adjustment.getId()).orElseThrow().getStatus())
                    .isEqualTo(AdjustmentStatus.CONFIRMED);
            // 落败的拨款可在调整完成后按新金额重试成功。
            FundRecord retry = grantService.disburse(t1.getId(), "ADJ-CONC-D2", "approver", "调整后拨款");
            assertThat(retry.getAmount()).isEqualByComparingTo("30");
        } else if (disburseOk && !confirmOk) {
            // 拨款在重叠中胜出：保持原金额 40/60，调整确认整体回滚（无半成功减额）。
            assertThat(t1After.getPlannedAmount()).isEqualByComparingTo("40");
            assertThat(t2After.getPlannedAmount()).isEqualByComparingTo("60");
            assertThat(reloaded.getTotalDisbursed()).isEqualByComparingTo("40");
            assertThat(t1After.getStatus()).isEqualTo(TrancheStatus.DISBURSED);
            // 失败的调整在版本守卫处回滚，方案仍待确认；重新确认时按最新状态判定不可沿用并作废。
            assertThat(adjustmentRepository.findById(adjustment.getId()).orElseThrow().getStatus())
                    .isEqualTo(AdjustmentStatus.PROPOSED);
            assertThatThrownBy(() -> grantService.confirmAdjustment(adjustment.getId(), "approver"))
                    .isInstanceOf(BusinessRuleException.class);
            assertThat(adjustmentRepository.findById(adjustment.getId()).orElseThrow().getStatus())
                    .isEqualTo(AdjustmentStatus.INVALIDATED);
        } else if (disburseOk) {
            // 完全串行（确认先提交，拨款后读到新版本）：30/70 且拨款按调整后 30 成功。
            assertThat(confirmOk).isTrue();
            assertThat(t1After.getPlannedAmount()).isEqualByComparingTo("30");
            assertThat(t2After.getPlannedAmount()).isEqualByComparingTo("70");
            assertThat(t1After.getStatus()).isEqualTo(TrancheStatus.DISBURSED);
            assertThat(reloaded.getTotalDisbursed()).isEqualByComparingTo("30");
            assertThat(adjustmentRepository.findById(adjustment.getId()).orElseThrow().getStatus())
                    .isEqualTo(AdjustmentStatus.CONFIRMED);
        } else {
            throw new AssertionError("拨款与调整至少应有一个成功");
        }
    }
}
