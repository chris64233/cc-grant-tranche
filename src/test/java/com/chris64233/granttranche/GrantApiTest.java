package com.chris64233.granttranche;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class GrantApiTest {

    @Autowired
    MockMvc mvc;

    @Test
    void 创建项目并走通拨款全流程() throws Exception {
        String project = mvc.perform(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "重点研发计划",
                                  "approvedAmount": 1000,
                                  "tranches": [
                                    {"sequence": 1, "plannedAmount": 400,
                                     "requiredDeliverable": "中期报告", "budgetCondition": "设备费不超30%"},
                                    {"sequence": 2, "plannedAmount": 600,
                                     "requiredDeliverable": "结题报告", "budgetCondition": "劳务费合规"}
                                  ]
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.approvedAmount").value(1000))
                .andExpect(jsonPath("$.disbursedAmount").value(0))
                .andReturn().getResponse().getContentAsString();
        long projectId = Long.parseLong(project.replaceAll(".*\"id\":(\\d+).*", "$1"));

        mvc.perform(post("/api/projects/{p}/tranches/1/deliverables", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"evidence\": \"中期报告.pdf\", \"operator\": \"张三\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"));

        mvc.perform(post("/api/projects/{p}/tranches/1/acceptance", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approved\": true, \"operator\": \"李验收\", \"reason\": \"达标\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));

        // 未拨款前余额为全额
        mvc.perform(get("/api/projects/{p}/balance", projectId))
                .andExpect(jsonPath("$.remainingAmount").value(1000));

        mvc.perform(post("/api/projects/{p}/tranches/1/disbursements", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessNo\": \"BN-API-1\", \"operator\": \"王财务\", \"reason\": \"首期\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(400))
                .andExpect(jsonPath("$.status").value("APPROVED"));

        // 幂等：同一业务号重复提交返回同一拨款
        mvc.perform(post("/api/projects/{p}/tranches/1/disbursements", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessNo\": \"BN-API-1\", \"operator\": \"王财务\", \"reason\": \"首期\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.businessNo").value("BN-API-1"));

        mvc.perform(get("/api/projects/{p}/balance", projectId))
                .andExpect(jsonPath("$.disbursedAmount").value(400))
                .andExpect(jsonPath("$.remainingAmount").value(600));

        mvc.perform(get("/api/projects/{p}/fund-records", projectId))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].type").value("DISBURSEMENT"));

        // 前置期次未拨款时第二期不得拨款（此处第一期已拨，第二期未验收仍不得拨）
        mvc.perform(post("/api/projects/{p}/tranches/2/disbursements", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessNo\": \"BN-API-2\", \"operator\": \"王财务\", \"reason\": \"二期\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("未验收通过")));
    }

    @Test
    void 期次总额超过批准金额返回冲突() throws Exception {
        mvc.perform(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "超支项目",
                                  "approvedAmount": 100,
                                  "tranches": [
                                    {"sequence": 1, "plannedAmount": 200,
                                     "requiredDeliverable": "报告", "budgetCondition": "无"}
                                  ]
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("不得超过项目批准金额")));
    }
}
