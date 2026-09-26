package com.chris64233.granttranche.service;

import com.chris64233.granttranche.domain.ComplianceSuspension;
import com.chris64233.granttranche.domain.FundRecord;
import com.chris64233.granttranche.domain.FundRecordStatus;
import com.chris64233.granttranche.domain.FundRecordType;
import com.chris64233.granttranche.domain.GrantProject;
import com.chris64233.granttranche.domain.GrantTranche;
import com.chris64233.granttranche.domain.TrancheStatus;
import com.chris64233.granttranche.dto.CreateProjectRequest;
import com.chris64233.granttranche.dto.ProjectBalance;
import com.chris64233.granttranche.repo.ComplianceSuspensionRepository;
import com.chris64233.granttranche.repo.FundRecordRepository;
import com.chris64233.granttranche.repo.GrantProjectRepository;
import com.chris64233.granttranche.repo.GrantTrancheRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class GrantServiceTest {

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

    private static CreateProjectRequest.TrancheSpec tranche(int seq, String amount) {
        return new CreateProjectRequest.TrancheSpec(seq, new BigDecimal(amount),
                "成果" + seq, "预算条件" + seq);
    }

    private static CreateProjectRequest projectRequest(String code, String approved,
                                                       CreateProjectRequest.TrancheSpec... tranches) {
        return new CreateProjectRequest(code, "项目" + code, new BigDecimal(approved), List.of(tranches));
    }

    /** 将期次推进到“验收通过、可拨款”状态。 */
    private GrantTranche acceptFirstTranche(Long projectId) {
        GrantTranche t = trancheRepository.findByProjectIdOrderBySequenceNoAsc(projectId).get(0);
        grantService.submitDeliverable(t.getId(), "中期报告 v1", "pi-zhang");
        grantService.review(t.getId(), true, "reviewer-li", "成果达标");
        return trancheRepository.findById(t.getId()).orElseThrow();
    }

    @Test
    void createsProjectWithTranchesAndRejectsAmountExceedingApproval() {
        GrantProject project = grantService.createProject(
                projectRequest("P-OK", "100", tranche(1, "40"), tranche(2, "60")));
        assertThat(project.getApprovedAmount()).isEqualByComparingTo("100");
        assertThat(trancheRepository.findByProjectIdOrderBySequenceNoAsc(project.getId()))
                .hasSize(2)
                .extracting(GrantTranche::getSequenceNo)
                .containsExactly(1, 2);

        assertThatThrownBy(() -> grantService.createProject(
                projectRequest("P-OVER", "100", tranche(1, "60"), tranche(2, "41"))))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("超过项目批准金额");
    }

    @Test
    void rejectsNonContiguousOrDuplicateSequenceNumbers() {
        assertThatThrownBy(() -> grantService.createProject(
                projectRequest("P-GAP", "100", tranche(1, "30"), tranche(3, "30"))))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("连续序列");

        assertThatThrownBy(() -> grantService.createProject(
                projectRequest("P-DUP", "100", tranche(1, "30"), tranche(1, "30"))))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("连续序列");
    }

    @Test
    void fullHappyPathDisbursesAndShowsBalance() {
        GrantProject project = grantService.createProject(
                projectRequest("P-HAPPY", "100", tranche(1, "40"), tranche(2, "60")));

        GrantTranche t1 = acceptFirstTranche(project.getId());
        FundRecord record = grantService.disburse(t1.getId(), "BIZ-001", "approver-wang", "第一期拨款");

        assertThat(record.getStatus()).isEqualTo(FundRecordStatus.UNPAID);
        assertThat(record.getBusinessNo()).isEqualTo("BIZ-001");
        ProjectBalance balance = grantService.getBalance(project.getId());
        assertThat(balance.approvedAmount()).isEqualByComparingTo("100");
        assertThat(balance.committedAmount()).isEqualByComparingTo("40");
        assertThat(balance.availableBalance()).isEqualByComparingTo("60");
        assertThat(balance.netPaidAmount()).isEqualByComparingTo("0");

        // 支付后净支付额变化。
        grantService.markPaid(record.getId(), "cashier-zhao");
        balance = grantService.getBalance(project.getId());
        assertThat(balance.netPaidAmount()).isEqualByComparingTo("40");
        assertThat(fundRecordRepository.findById(record.getId()).orElseThrow().getStatus())
                .isEqualTo(FundRecordStatus.PAID);
    }

    @Test
    void cannotDisburseBeforeAcceptance() {
        GrantProject project = grantService.createProject(
                projectRequest("P-NOACCEPT", "100", tranche(1, "40")));
        GrantTranche t = trancheRepository.findByProjectIdOrderBySequenceNoAsc(project.getId()).get(0);

        assertThatThrownBy(() -> grantService.disburse(t.getId(), "BIZ-X1", "a", "r"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("验收通过");

        grantService.submitDeliverable(t.getId(), "证据", "pi");
        assertThatThrownBy(() -> grantService.disburse(t.getId(), "BIZ-X2", "a", "r"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("验收通过");
    }

    @Test
    void activeComplianceSuspensionBlocksDisbursementUntilLifted() {
        GrantProject project = grantService.createProject(
                projectRequest("P-SUSP", "100", tranche(1, "40")));
        GrantTranche t = acceptFirstTranche(project.getId());

        ComplianceSuspension suspension = grantService.suspend(project.getId(), "伦理审查未决", "officer-he");
        assertThat(suspension.isActive()).isTrue();
        assertThat(suspensionRepository.existsByProjectIdAndActiveTrue(project.getId())).isTrue();

        assertThatThrownBy(() -> grantService.disburse(t.getId(), "BIZ-S1", "a", "r"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("合规暂停");
        // 被阻止期间未占用任何额度。
        assertThat(grantService.getBalance(project.getId()).committedAmount()).isEqualByComparingTo("0");

        // 解除（记录解除人与原因）后可以拨款。
        grantService.liftSuspension(suspension.getId(), "officer-he", "补正完成，解除暂停");
        FundRecord record = grantService.disburse(t.getId(), "BIZ-S2", "approver-wang", "解除后拨款");
        assertThat(record.getStatus()).isEqualTo(FundRecordStatus.UNPAID);
        assertThat(grantService.getBalance(project.getId()).committedAmount()).isEqualByComparingTo("40");
    }

    @Test
    void cannotDisburseTrancheBeforePriorTranches() {
        GrantProject project = grantService.createProject(
                projectRequest("P-ORDER", "100", tranche(1, "40"), tranche(2, "60")));
        List<GrantTranche> tranches = trancheRepository.findByProjectIdOrderBySequenceNoAsc(project.getId());

        // 第二期先验收通过。
        GrantTranche t2 = tranches.get(1);
        grantService.submitDeliverable(t2.getId(), "二期成果", "pi");
        grantService.review(t2.getId(), true, "reviewer", "ok");

        assertThatThrownBy(() -> grantService.disburse(t2.getId(), "BIZ-JUMP", "a", "r"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("前置期次 1");
    }

    @Test
    void disbursementIsIdempotentByBusinessNoAndRejectsReuse() {
        GrantProject project = grantService.createProject(
                projectRequest("P-IDEM", "100", tranche(1, "40")));
        GrantTranche t = acceptFirstTranche(project.getId());

        FundRecord first = grantService.disburse(t.getId(), "BIZ-IDEM-1", "approver", "首次");
        FundRecord second = grantService.disburse(t.getId(), "BIZ-IDEM-1", "approver", "重复提交");

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(fundRecordRepository.findByProjectIdOrderByIdAsc(project.getId()))
                .filteredOn(r -> r.getRecordType() == FundRecordType.DISBURSEMENT)
                .hasSize(1);
        assertThat(projectRepository.findById(project.getId()).orElseThrow().getTotalDisbursed())
                .isEqualByComparingTo("40");

        // 业务号复用于其他期次/记录必须冲突。
        GrantProject other = grantService.createProject(
                projectRequest("P-IDEM2", "100", tranche(1, "10")));
        GrantTranche otherT = acceptFirstTranche(other.getId());
        assertThatThrownBy(() -> grantService.disburse(otherT.getId(), "BIZ-IDEM-1", "a", "r"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("业务号已被其他资金记录占用");
    }

    @Test
    void revokeRestoresQuotaAndKeepsOriginalRecord() {
        GrantProject project = grantService.createProject(
                projectRequest("P-REVOKE", "100", tranche(1, "40")));
        GrantTranche t = acceptFirstTranche(project.getId());
        FundRecord record = grantService.disburse(t.getId(), "BIZ-REV-1", "approver", "拨款");

        grantService.revoke(record.getId(), "manager-qian", "预算调整，暂缓拨付");

        FundRecord stored = fundRecordRepository.findById(record.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(FundRecordStatus.REVOKED);
        assertThat(stored.getRevokedBy()).isEqualTo("manager-qian");
        assertThat(stored.getRevokeReason()).isEqualTo("预算调整，暂缓拨付");
        assertThat(stored.getRevokedAt()).isNotNull();
        assertThat(projectRepository.findById(project.getId()).orElseThrow().getTotalDisbursed())
                .isEqualByComparingTo("0");
        assertThat(grantService.getBalance(project.getId()).availableBalance()).isEqualByComparingTo("100");
        // 期次回到验收通过，可重新拨款。
        assertThat(trancheRepository.findById(t.getId()).orElseThrow().getStatus())
                .isEqualTo(TrancheStatus.ACCEPTED);

        // 重新拨款成功（新业务号）。
        FundRecord again = grantService.disburse(t.getId(), "BIZ-REV-2", "approver", "重新批准");
        assertThat(again.getStatus()).isEqualTo(FundRecordStatus.UNPAID);
        assertThat(projectRepository.findById(project.getId()).orElseThrow().getTotalDisbursed())
                .isEqualByComparingTo("40");
    }

    @Test
    void cannotRevokePaidDisbursement() {
        GrantProject project = grantService.createProject(
                projectRequest("P-PAID", "100", tranche(1, "40")));
        GrantTranche t = acceptFirstTranche(project.getId());
        FundRecord record = grantService.disburse(t.getId(), "BIZ-PAID-1", "approver", "拨款");
        grantService.markPaid(record.getId(), "cashier");

        assertThatThrownBy(() -> grantService.revoke(record.getId(), "m", "想撤销"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("只能通过追回");
    }

    @Test
    void recoveryReducesNetDisbursementAndKeepsOriginalRecord() {
        GrantProject project = grantService.createProject(
                projectRequest("P-REC", "100", tranche(1, "40")));
        GrantTranche t = acceptFirstTranche(project.getId());
        FundRecord disbursement = grantService.disburse(t.getId(), "BIZ-REC-D", "approver", "拨款");
        grantService.markPaid(disbursement.getId(), "cashier");

        FundRecord recovery = grantService.recover(disbursement.getId(), "BIZ-REC-R1",
                new BigDecimal("15"), "auditor-sun", "审计核减，部分追回");

        assertThat(recovery.getRecordType()).isEqualTo(FundRecordType.RECOVERY);
        // 原拨款记录保持 PAID 不变。
        FundRecord original = fundRecordRepository.findById(disbursement.getId()).orElseThrow();
        assertThat(original.getStatus()).isEqualTo(FundRecordStatus.PAID);
        assertThat(original.getRecoveredAmount()).isEqualByComparingTo("15");

        GrantProject reloaded = projectRepository.findById(project.getId()).orElseThrow();
        assertThat(reloaded.getTotalDisbursed()).isEqualByComparingTo("40");
        assertThat(reloaded.getTotalRecovered()).isEqualByComparingTo("15");
        ProjectBalance balance = grantService.getBalance(project.getId());
        assertThat(balance.netPaidAmount()).isEqualByComparingTo("25");

        // 追回幂等：同一业务号返回同一笔，不重复追回。
        FundRecord repeat = grantService.recover(disbursement.getId(), "BIZ-REC-R1",
                new BigDecimal("15"), "auditor-sun", "重复提交");
        assertThat(repeat.getId()).isEqualTo(recovery.getId());
        assertThat(projectRepository.findById(project.getId()).orElseThrow().getTotalRecovered())
                .isEqualByComparingTo("15");

        // 追回累计不得超过原拨款额。
        assertThatThrownBy(() -> grantService.recover(disbursement.getId(), "BIZ-REC-R2",
                new BigDecimal("30"), "auditor-sun", "超额追回"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("可追回净额");

        // 追回剩余 25 成功。
        grantService.recover(disbursement.getId(), "BIZ-REC-R3",
                new BigDecimal("25"), "auditor-sun", "全额追回");
        assertThat(grantService.getBalance(project.getId()).netPaidAmount()).isEqualByComparingTo("0");
    }

    @Test
    void cannotRecoverUnpaidDisbursement() {
        GrantProject project = grantService.createProject(
                projectRequest("P-RECUN", "100", tranche(1, "40")));
        GrantTranche t = acceptFirstTranche(project.getId());
        FundRecord record = grantService.disburse(t.getId(), "BIZ-RECUN-D", "approver", "拨款");

        assertThatThrownBy(() -> grantService.recover(record.getId(), "BIZ-RECUN-R",
                new BigDecimal("10"), "a", "r"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("请使用撤销");
    }

    @Test
    void reviewRejectionRequiresReasonAndAllowsResubmit() {
        GrantProject project = grantService.createProject(
                projectRequest("P-REJ", "100", tranche(1, "40")));
        GrantTranche t = trancheRepository.findByProjectIdOrderBySequenceNoAsc(project.getId()).get(0);
        grantService.submitDeliverable(t.getId(), "初版", "pi");

        assertThatThrownBy(() -> grantService.review(t.getId(), false, "reviewer", " "))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("必须填写原因");

        grantService.review(t.getId(), false, "reviewer-li", "数据不完整");
        assertThat(trancheRepository.findById(t.getId()).orElseThrow().getStatus())
                .isEqualTo(TrancheStatus.REJECTED);

        grantService.submitDeliverable(t.getId(), "补充数据后的版本", "pi");
        grantService.review(t.getId(), true, "reviewer-li", "补正后通过");
        assertThat(trancheRepository.findById(t.getId()).orElseThrow().getStatus())
                .isEqualTo(TrancheStatus.ACCEPTED);
    }

    @Test
    void decisionsKeepPeopleAndReasonsInQueries() {
        GrantProject project = grantService.createProject(
                projectRequest("P-AUDIT", "100", tranche(1, "40")));
        GrantTranche t = trancheRepository.findByProjectIdOrderBySequenceNoAsc(project.getId()).get(0);
        grantService.submitDeliverable(t.getId(), "证据材料", "pi-zhang");
        grantService.review(t.getId(), true, "reviewer-li", "验收意见：通过");
        grantService.suspend(project.getId(), "暂停原因", "officer-he");
        var suspensions = suspensionRepository.findByProjectIdOrderByRaisedAtDesc(project.getId());
        grantService.liftSuspension(suspensions.get(0).getId(), "officer-he", "解除原因");
        FundRecord record = grantService.disburse(t.getId(), "BIZ-AUDIT", "approver-wang", "拨款原因");
        grantService.markPaid(record.getId(), "cashier-zhao");
        grantService.recover(record.getId(), "BIZ-AUDIT-R", new BigDecimal("5"),
                "auditor-sun", "追回原因");

        var trancheViews = grantService.listTranches(project.getId());
        assertThat(trancheViews.get(0).submittedBy()).isEqualTo("pi-zhang");
        assertThat(trancheViews.get(0).deliverableEvidence()).isEqualTo("证据材料");
        assertThat(trancheViews.get(0).reviewedBy()).isEqualTo("reviewer-li");
        assertThat(trancheViews.get(0).reviewComment()).isEqualTo("验收意见：通过");

        var compliance = grantService.listSuspensions(project.getId());
        assertThat(compliance.get(0).raisedBy()).isEqualTo("officer-he");
        assertThat(compliance.get(0).reason()).isEqualTo("暂停原因");
        assertThat(compliance.get(0).active()).isFalse();
        assertThat(compliance.get(0).liftedBy()).isEqualTo("officer-he");
        assertThat(compliance.get(0).liftedReason()).isEqualTo("解除原因");

        var ledger = grantService.listFundRecords(project.getId());
        assertThat(ledger).hasSize(2);
        assertThat(ledger.get(0).createdBy()).isEqualTo("approver-wang");
        assertThat(ledger.get(0).reason()).isEqualTo("拨款原因");
        assertThat(ledger.get(0).paidBy()).isEqualTo("cashier-zhao");
        assertThat(ledger.get(1).createdBy()).isEqualTo("auditor-sun");
        assertThat(ledger.get(1).reason()).isEqualTo("追回原因");
    }
}
