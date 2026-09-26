package com.chris64233.granttranche.web;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class GrantControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    private Map<String, Object> trancheSpec(int seq, String amount, String deliverable) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sequenceNo", seq);
        m.put("plannedAmount", amount);
        m.put("requiredDeliverable", deliverable);
        m.put("budgetConditions", "预算条件" + seq);
        return m;
    }

    @SuppressWarnings("unchecked")
    private long createProject(String code, String approved, List<Map<String, Object>> tranches) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("projectCode", code);
        body.put("title", "项目" + code);
        body.put("approvedAmount", approved);
        body.put("tranches", tranches);
        MvcResult result = mockMvc.perform(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    @Test
    void createsProjectAndRejectsTranchesExceedingApprovedAmount() throws Exception {
        mockMvc.perform(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "projectCode": "W-1",
                                  "title": "超额项目",
                                  "approvedAmount": 100,
                                  "tranches": [
                                    {"sequenceNo": 1, "plannedAmount": 80,
                                     "requiredDeliverable": "成果1", "budgetConditions": "条件1"},
                                    {"sequenceNo": 2, "plannedAmount": 21,
                                     "requiredDeliverable": "成果2", "budgetConditions": "条件2"}
                                  ]
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("超过项目批准金额")));
    }

    @Test
    void rejectsInvalidRequestBody() throws Exception {
        mockMvc.perform(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "projectCode": "",
                                  "title": "无效项目",
                                  "approvedAmount": 100,
                                  "tranches": []
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void fullFlowThroughHttpIsQueryable() throws Exception {
        long projectId = createProject("W-FULL", "100",
                List.of(trancheSpec(1, "40", "中期报告"), trancheSpec(2, "60", "结题报告")));

        // 初始余额。
        mockMvc.perform(get("/api/projects/{id}/balance", projectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approvedAmount").value(100))
                .andExpect(jsonPath("$.committedAmount").value(0))
                .andExpect(jsonPath("$.availableBalance").value(100));

        // 期次列表。
        MvcResult tranchesResult = mockMvc.perform(get("/api/projects/{id}/tranches", projectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andReturn();
        long trancheId = objectMapper.readTree(tranchesResult.getResponse().getContentAsString())
                .get(0).get("id").asLong();

        // 提交成果。
        mockMvc.perform(post("/api/projects/tranches/{id}/submit", trancheId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"evidence": "中期报告 PDF", "submittedBy": "pi-zhang"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.deliverableEvidence").value("中期报告 PDF"));

        // 未验收前拨款 -> 409。
        mockMvc.perform(post("/api/projects/tranches/{id}/disburse", trancheId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"businessNo": "W-BIZ-TOO-EARLY", "approvedBy": "a", "reason": "r"}
                                """))
                .andExpect(status().isConflict());

        // 验收通过。
        mockMvc.perform(post("/api/projects/tranches/{id}/review", trancheId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"approved": true, "reviewedBy": "reviewer-li", "comment": "通过"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));

        // 拨款。
        mockMvc.perform(post("/api/projects/tranches/{id}/disburse", trancheId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"businessNo": "W-BIZ-1", "approvedBy": "approver-wang", "reason": "第一期"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.businessNo").value("W-BIZ-1"))
                .andExpect(jsonPath("$.status").value("UNPAID"));

        // 幂等：同业务号重复提交返回同一笔。
        MvcResult second = mockMvc.perform(post("/api/projects/tranches/{id}/disburse", trancheId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"businessNo": "W-BIZ-1", "approvedBy": "approver-wang", "reason": "重复"}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        long firstRecordId = objectMapper.readTree(second.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(get("/api/projects/{id}/balance", projectId))
                .andExpect(jsonPath("$.committedAmount").value(40))
                .andExpect(jsonPath("$.availableBalance").value(60));

        // 台账。
        mockMvc.perform(get("/api/projects/{id}/fund-records", projectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].businessNo").value("W-BIZ-1"));

        // 撤销后额度恢复、台账保留 REVOKED 记录。
        mockMvc.perform(post("/api/projects/fund-records/{id}/revoke", firstRecordId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"revokedBy": "manager-qian", "reason": "预算调整"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));
        mockMvc.perform(get("/api/projects/{id}/balance", projectId))
                .andExpect(jsonPath("$.committedAmount").value(0))
                .andExpect(jsonPath("$.availableBalance").value(100));

        // 合规暂停期间拨款被拒绝。
        MvcResult suspensionResult = mockMvc.perform(post("/api/projects/{id}/suspensions", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason": "合规检查", "raisedBy": "officer-he"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        long suspensionId = objectMapper.readTree(suspensionResult.getResponse().getContentAsString())
                .get("id").asLong();
        mockMvc.perform(post("/api/projects/tranches/{id}/disburse", trancheId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"businessNo": "W-BIZ-2", "approvedBy": "approver-wang", "reason": "暂停中"}
                                """))
                .andExpect(status().isConflict());

        mockMvc.perform(get("/api/projects/{id}/compliance", projectId))
                .andExpect(jsonPath("$[0].active").value(true))
                .andExpect(jsonPath("$[0].raisedBy").value("officer-he"));

        // 解除后拨款成功。
        mockMvc.perform(post("/api/projects/suspensions/{id}/lift", suspensionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"liftedBy": "officer-he", "reason": "整改完成"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
        mockMvc.perform(post("/api/projects/tranches/{id}/disburse", trancheId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"businessNo": "W-BIZ-3", "approvedBy": "approver-wang", "reason": "解除后"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNPAID"));
    }

    @Test
    void unknownProjectReturns404() throws Exception {
        mockMvc.perform(get("/api/projects/99999/balance"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("项目不存在")));
    }
}
