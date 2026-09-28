package com.chris64233.granttranche.web;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 非事务控制器测试（类上<b>没有</b> {@code @Transactional}），配合
 * {@code spring.jpa.open-in-view=false}，验证服务层已在事务内完成视图所需的懒加载初始化；
 * 专门覆盖预算调整幂等早返回路径（重复申请、重复确认）在事务提交后的 JSON 序列化。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BudgetAdjustmentControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    private Map<String, Object> trancheSpec(int seq, String amount) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sequenceNo", seq);
        m.put("plannedAmount", amount);
        m.put("requiredDeliverable", "成果" + seq);
        m.put("budgetConditions", "条件" + seq);
        return m;
    }

    @SuppressWarnings("unchecked")
    private long createProject(String code) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("projectCode", code);
        body.put("title", "项目" + code);
        body.put("approvedAmount", 100);
        body.put("tranches", List.of(trancheSpec(1, "40"), trancheSpec(2, "60")));
        MvcResult result = mockMvc.perform(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private String requestBody(long t1, long t2, int amount, String businessNo) {
        return """
                {"fromTrancheId": %d, "toTrancheId": %d, "amount": %d,
                 "businessNo": "%s", "reason": "后移预算", "requestedBy": "manager-qian"}
                """.formatted(t1, t2, amount, businessNo);
    }

    @Test
    void idempotentRequestAndConfirmSerializeOutsideTransaction() throws Exception {
        long projectId = createProject("NW-ADJ");
        MvcResult tranchesResult = mockMvc.perform(get("/api/projects/{id}/tranches", projectId))
                .andExpect(status().isOk()).andReturn();
        long t1 = objectMapper.readTree(tranchesResult.getResponse().getContentAsString()).get(0).get("id").asLong();
        long t2 = objectMapper.readTree(tranchesResult.getResponse().getContentAsString()).get(1).get("id").asLong();

        // 首次申请。
        MvcResult first = mockMvc.perform(post("/api/projects/adjustments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(t1, t2, 10, "NW-ADJ-1")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fromSequenceNo").value(1))
                .andExpect(jsonPath("$.toSequenceNo").value(2))
                .andExpect(jsonPath("$.status").value("PROPOSED"))
                .andReturn();
        long adjustmentId = objectMapper.readTree(first.getResponse().getContentAsString()).get("id").asLong();

        // 重复申请（幂等早返回）：事务外仍须能序列化期次序号，而不是 500 LazyInitializationException。
        mockMvc.perform(post("/api/projects/adjustments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(t1, t2, 10, "NW-ADJ-1")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(adjustmentId))
                .andExpect(jsonPath("$.fromSequenceNo").value(1))
                .andExpect(jsonPath("$.toSequenceNo").value(2));

        // 首次确认。
        mockMvc.perform(post("/api/projects/adjustments/{id}/confirm", adjustmentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"confirmedBy": "approver-wang"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.fromAmountAfter").value(30))
                .andExpect(jsonPath("$.toAmountAfter").value(70));

        // 重复确认（幂等早返回）：同样必须 200 且能在事务外序列化懒加载关联。
        mockMvc.perform(post("/api/projects/adjustments/{id}/confirm", adjustmentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"confirmedBy": "approver-wang"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(adjustmentId))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.fromSequenceNo").value(1))
                .andExpect(jsonPath("$.toSequenceNo").value(2));

        // 列表查询（独立只读事务，同样在事务内完成映射）。
        mockMvc.perform(get("/api/projects/{id}/adjustments", projectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].fromSequenceNo").value(1))
                .andExpect(jsonPath("$[0].toSequenceNo").value(2));
    }
}
