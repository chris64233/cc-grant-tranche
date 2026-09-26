package com.chris64233.granttranche.web;

import com.chris64233.granttranche.domain.GrantProject;
import com.chris64233.granttranche.dto.CreateProjectRequest;
import com.chris64233.granttranche.dto.FundRecordView;
import com.chris64233.granttranche.dto.LiftSuspensionRequest;
import com.chris64233.granttranche.dto.PayRequest;
import com.chris64233.granttranche.dto.ProjectBalance;
import com.chris64233.granttranche.dto.RecoveryRequest;
import com.chris64233.granttranche.dto.RevokeRequest;
import com.chris64233.granttranche.dto.ReviewRequest;
import com.chris64233.granttranche.dto.SubmitDeliverableRequest;
import com.chris64233.granttranche.dto.SuspendRequest;
import com.chris64233.granttranche.dto.SuspensionView;
import com.chris64233.granttranche.dto.TrancheView;
import com.chris64233.granttranche.service.GrantService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.Map;

/** 分期拨款管理接口。 */
@RestController
@RequestMapping("/api/projects")
public class GrantController {

    private final GrantService grantService;

    public GrantController(GrantService grantService) {
        this.grantService = grantService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<Map<String, Object>> createProject(@Valid @RequestBody CreateProjectRequest request) {
        GrantProject project = grantService.createProject(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequestUri()
                .path("/{id}").buildAndExpand(project.getId()).toUri();
        return ResponseEntity.created(location).body(Map.of(
                "id", project.getId(),
                "projectCode", project.getProjectCode(),
                "approvedAmount", project.getApprovedAmount()));
    }

    /** 项目余额。 */
    @GetMapping("/{projectId}/balance")
    public ProjectBalance balance(@PathVariable Long projectId) {
        return grantService.getBalance(projectId);
    }

    /** 期次与成果证据。 */
    @GetMapping("/{projectId}/tranches")
    public List<TrancheView> tranches(@PathVariable Long projectId) {
        return grantService.listTranches(projectId);
    }

    /** 合规状态（暂停/解除记录，新的在前）。 */
    @GetMapping("/{projectId}/compliance")
    public List<SuspensionView> compliance(@PathVariable Long projectId) {
        return grantService.listSuspensions(projectId);
    }

    /** 资金台账（拨款、追回全量记录）。 */
    @GetMapping("/{projectId}/fund-records")
    public List<FundRecordView> fundRecords(@PathVariable Long projectId) {
        return grantService.listFundRecords(projectId);
    }

    @PostMapping("/tranches/{trancheId}/submit")
    public TrancheView submit(@PathVariable Long trancheId,
                              @Valid @RequestBody SubmitDeliverableRequest request) {
        return GrantService.toTrancheView(
                grantService.submitDeliverable(trancheId, request.evidence(), request.submittedBy()));
    }

    @PostMapping("/tranches/{trancheId}/review")
    public TrancheView review(@PathVariable Long trancheId, @Valid @RequestBody ReviewRequest request) {
        return GrantService.toTrancheView(
                grantService.review(trancheId, request.approved(), request.reviewedBy(), request.comment()));
    }

    /** 批准拨款（幂等：相同 businessNo 重复提交返回同一笔拨款）。 */
    @PostMapping("/tranches/{trancheId}/disburse")
    public FundRecordView disburse(@PathVariable Long trancheId,
                                   @Valid @RequestBody com.chris64233.granttranche.dto.DisburseRequest request) {
        return GrantService.toFundRecordView(grantService.disburse(
                trancheId, request.businessNo(), request.approvedBy(), request.reason()));
    }

    @PostMapping("/fund-records/{fundRecordId}/pay")
    public FundRecordView pay(@PathVariable Long fundRecordId, @Valid @RequestBody PayRequest request) {
        return GrantService.toFundRecordView(grantService.markPaid(fundRecordId, request.paidBy()));
    }

    /** 撤销尚未支付的拨款并恢复额度。 */
    @PostMapping("/fund-records/{fundRecordId}/revoke")
    public FundRecordView revoke(@PathVariable Long fundRecordId, @Valid @RequestBody RevokeRequest request) {
        return GrantService.toFundRecordView(
                grantService.revoke(fundRecordId, request.revokedBy(), request.reason()));
    }

    @PostMapping("/{projectId}/suspensions")
    @ResponseStatus(HttpStatus.CREATED)
    public SuspensionView suspend(@PathVariable Long projectId, @Valid @RequestBody SuspendRequest request) {
        var s = grantService.suspend(projectId, request.reason(), request.raisedBy());
        return new SuspensionView(s.getId(), s.isActive(), s.getReason(), s.getRaisedBy(), s.getRaisedAt(),
                s.getLiftedReason(), s.getLiftedBy(), s.getLiftedAt());
    }

    @PostMapping("/suspensions/{suspensionId}/lift")
    public SuspensionView lift(@PathVariable Long suspensionId, @Valid @RequestBody LiftSuspensionRequest request) {
        var s = grantService.liftSuspension(suspensionId, request.liftedBy(), request.reason());
        return new SuspensionView(s.getId(), s.isActive(), s.getReason(), s.getRaisedBy(), s.getRaisedAt(),
                s.getLiftedReason(), s.getLiftedBy(), s.getLiftedAt());
    }

    /** 对已支付拨款登记追回（幂等：相同 businessNo 返回同一笔追回记录）。 */
    @PostMapping("/fund-records/{fundRecordId}/recoveries")
    @ResponseStatus(HttpStatus.CREATED)
    public FundRecordView recover(@PathVariable Long fundRecordId,
                                  @Valid @RequestBody RecoveryRequest request) {
        return GrantService.toFundRecordView(grantService.recover(
                fundRecordId, request.businessNo(), request.amount(),
                request.recoveredBy(), request.reason()));
    }
}
