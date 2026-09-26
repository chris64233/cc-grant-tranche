package com.chris64233.granttranche.web;

import com.chris64233.granttranche.domain.ComplianceHold;
import com.chris64233.granttranche.domain.Disbursement;
import com.chris64233.granttranche.domain.DisbursementStatus;
import com.chris64233.granttranche.domain.FundRecord;
import com.chris64233.granttranche.domain.FundRecordType;
import com.chris64233.granttranche.domain.GrantProject;
import com.chris64233.granttranche.domain.Tranche;
import com.chris64233.granttranche.domain.TrancheStatus;
import com.chris64233.granttranche.service.GrantService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/projects")
public class GrantController {

    private final GrantService service;

    public GrantController(GrantService service) {
        this.service = service;
    }

    // ---------- 请求 ----------

    public record TrancheSpecRequest(int sequence,
                                     @NotNull @Positive BigDecimal plannedAmount,
                                     @NotBlank String requiredDeliverable,
                                     @NotBlank String budgetCondition) {
    }

    public record CreateProjectRequest(@NotBlank String name,
                                       @NotNull @Positive BigDecimal approvedAmount,
                                       @NotEmpty List<@Valid TrancheSpecRequest> tranches) {
    }

    public record SubmitDeliverableRequest(@NotBlank String evidence, @NotBlank String operator) {
    }

    public record AcceptanceRequest(boolean approved, @NotBlank String operator, @NotBlank String reason) {
    }

    public record HoldRequest(@NotBlank String reason, @NotBlank String operator) {
    }

    public record LiftHoldRequest(@NotBlank String operator, @NotBlank String reason) {
    }

    public record ApproveDisbursementRequest(@NotBlank String businessNo,
                                             @Positive BigDecimal amount,
                                             @NotBlank String operator,
                                             @NotBlank String reason) {
    }

    public record OperatorRequest(@NotBlank String operator) {
    }

    public record OperatorReasonRequest(@NotBlank String operator, @NotBlank String reason) {
    }

    public record ClawbackRequest(@NotBlank String businessNo,
                                  @NotNull @Positive BigDecimal amount,
                                  @NotBlank String operator,
                                  @NotBlank String reason) {
    }

    // ---------- 响应 ----------

    public record ProjectView(Long id, String name, BigDecimal approvedAmount,
                              BigDecimal disbursedAmount, BigDecimal remainingAmount) {
        static ProjectView of(GrantProject p) {
            return new ProjectView(p.getId(), p.getName(), p.getApprovedAmount(),
                    p.getDisbursedAmount(), p.getRemainingAmount());
        }
    }

    public record TrancheView(Long id, int sequence, BigDecimal plannedAmount,
                              String requiredDeliverable, String budgetCondition,
                              TrancheStatus status, String deliverableEvidence,
                              String submittedBy, Instant submittedAt,
                              String acceptanceDecidedBy, String acceptanceReason,
                              Instant acceptanceDecidedAt) {
        static TrancheView of(Tranche t) {
            return new TrancheView(t.getId(), t.getSequence(), t.getPlannedAmount(),
                    t.getRequiredDeliverable(), t.getBudgetCondition(), t.getStatus(),
                    t.getDeliverableEvidence(), t.getSubmittedBy(), t.getSubmittedAt(),
                    t.getAcceptanceDecidedBy(), t.getAcceptanceReason(), t.getAcceptanceDecidedAt());
        }
    }

    public record HoldView(Long id, boolean active, String reason, String createdBy, Instant createdAt,
                           String liftedBy, String liftReason, Instant liftedAt) {
        static HoldView of(ComplianceHold h) {
            return new HoldView(h.getId(), h.isActive(), h.getReason(), h.getCreatedBy(), h.getCreatedAt(),
                    h.getLiftedBy(), h.getLiftReason(), h.getLiftedAt());
        }
    }

    public record DisbursementView(Long id, Long trancheId, String businessNo, BigDecimal amount,
                                   DisbursementStatus status, String approvedBy, String approvalReason,
                                   Instant createdAt, String paidBy, Instant paidAt,
                                   String revokedBy, String revokeReason, Instant revokedAt) {
        static DisbursementView of(Disbursement d) {
            return new DisbursementView(d.getId(), d.getTranche().getId(), d.getBusinessNo(), d.getAmount(),
                    d.getStatus(), d.getApprovedBy(), d.getApprovalReason(), d.getCreatedAt(),
                    d.getPaidBy(), d.getPaidAt(), d.getRevokedBy(), d.getRevokeReason(), d.getRevokedAt());
        }
    }

    public record FundRecordView(Long id, Long disbursementId, FundRecordType type, String businessNo,
                                 BigDecimal amount, String operator, String reason, Instant createdAt) {
        static FundRecordView of(FundRecord r) {
            return new FundRecordView(r.getId(), r.getDisbursement().getId(), r.getType(), r.getBusinessNo(),
                    r.getAmount(), r.getOperator(), r.getReason(), r.getCreatedAt());
        }
    }

    // ---------- 写端点 ----------

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectView createProject(@Valid @RequestBody CreateProjectRequest request) {
        var specs = request.tranches().stream()
                .map(t -> new GrantService.TrancheSpec(t.sequence(), t.plannedAmount(),
                        t.requiredDeliverable(), t.budgetCondition()))
                .toList();
        return ProjectView.of(service.createProject(request.name(), request.approvedAmount(), specs));
    }

    @PostMapping("/{projectId}/tranches/{sequence}/deliverables")
    public TrancheView submitDeliverable(@PathVariable Long projectId, @PathVariable int sequence,
                                         @Valid @RequestBody SubmitDeliverableRequest request) {
        return TrancheView.of(service.submitDeliverable(
                projectId, sequence, request.evidence(), request.operator()));
    }

    @PostMapping("/{projectId}/tranches/{sequence}/acceptance")
    public TrancheView decideAcceptance(@PathVariable Long projectId, @PathVariable int sequence,
                                        @Valid @RequestBody AcceptanceRequest request) {
        return TrancheView.of(service.decideAcceptance(
                projectId, sequence, request.approved(), request.operator(), request.reason()));
    }

    @PostMapping("/{projectId}/holds")
    @ResponseStatus(HttpStatus.CREATED)
    public HoldView createHold(@PathVariable Long projectId, @Valid @RequestBody HoldRequest request) {
        return HoldView.of(service.createHold(projectId, request.reason(), request.operator()));
    }

    @PostMapping("/{projectId}/holds/{holdId}/lift")
    public HoldView liftHold(@PathVariable Long projectId, @PathVariable Long holdId,
                             @Valid @RequestBody LiftHoldRequest request) {
        return HoldView.of(service.liftHold(projectId, holdId, request.operator(), request.reason()));
    }

    @PostMapping("/{projectId}/tranches/{sequence}/disbursements")
    public DisbursementView approveDisbursement(@PathVariable Long projectId, @PathVariable int sequence,
                                                @Valid @RequestBody ApproveDisbursementRequest request) {
        return DisbursementView.of(service.approveDisbursement(projectId, sequence,
                request.businessNo(), request.amount(), request.operator(), request.reason()));
    }

    @PostMapping("/{projectId}/disbursements/{disbursementId}/pay")
    public DisbursementView confirmPayment(@PathVariable Long projectId, @PathVariable Long disbursementId,
                                           @Valid @RequestBody OperatorRequest request) {
        return DisbursementView.of(service.confirmPayment(projectId, disbursementId, request.operator()));
    }

    @PostMapping("/{projectId}/disbursements/{disbursementId}/revoke")
    public DisbursementView revokeDisbursement(@PathVariable Long projectId, @PathVariable Long disbursementId,
                                               @Valid @RequestBody OperatorReasonRequest request) {
        return DisbursementView.of(service.revokeDisbursement(
                projectId, disbursementId, request.operator(), request.reason()));
    }

    @PostMapping("/{projectId}/disbursements/{disbursementId}/clawbacks")
    public FundRecordView clawback(@PathVariable Long projectId, @PathVariable Long disbursementId,
                                   @Valid @RequestBody ClawbackRequest request) {
        return FundRecordView.of(service.clawback(projectId, disbursementId,
                request.businessNo(), request.amount(), request.operator(), request.reason()));
    }

    // ---------- 查询端点 ----------

    /** 项目余额：批准总额、净累计拨款、剩余额度。 */
    @GetMapping("/{projectId}/balance")
    public ProjectView balance(@PathVariable Long projectId) {
        return ProjectView.of(service.getProject(projectId));
    }

    /** 期次证据与验收决定。 */
    @GetMapping("/{projectId}/tranches")
    public List<TrancheView> tranches(@PathVariable Long projectId) {
        return service.listTranches(projectId).stream().map(TrancheView::of).toList();
    }

    /** 合规状态（暂停记录及是否活动中）。 */
    @GetMapping("/{projectId}/compliance-holds")
    public List<HoldView> holds(@PathVariable Long projectId) {
        return service.listHolds(projectId).stream().map(HoldView::of).toList();
    }

    @GetMapping("/{projectId}/disbursements")
    public List<DisbursementView> disbursements(@PathVariable Long projectId) {
        return service.listDisbursements(projectId).stream().map(DisbursementView::of).toList();
    }

    /** 资金台账。 */
    @GetMapping("/{projectId}/fund-records")
    public List<FundRecordView> fundRecords(@PathVariable Long projectId) {
        return service.listFundRecords(projectId).stream().map(FundRecordView::of).toList();
    }
}
