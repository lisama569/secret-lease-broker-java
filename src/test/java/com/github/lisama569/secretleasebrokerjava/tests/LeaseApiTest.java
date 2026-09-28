package com.github.lisama569.secretleasebrokerjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import com.github.lisama569.secretleasebrokerjava.tests.support.TestConfig;

import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REST 端到端：越权 403、参数 400、查无租约 404、过期/重复吊销 409，错误码可区分。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestConfig.class)
class LeaseApiTest {

    private static final Logger log = LoggerFactory.getLogger(LeaseApiTest.class);

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;

    private String uniquePath() {
        return "secret/api/db-" + System.nanoTime();
    }

    private void register(String id, Map<String, String> attrs) throws Exception {
        mvc.perform(post("/api/admin/callers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("callerId", id, "attributes", attrs))))
                .andExpect(status().isCreated());
    }

    private void allow(String pathPrefix, String... actions) throws Exception {
        // 策略是整体替换语义：一次性写入 ISSUE + RENEW 两条规则
        List<Map<String, Object>> rules = new java.util.ArrayList<>();
        for (String action : actions) {
            rules.add(Map.of("ruleId", "rule-" + action, "priority", 100,
                    "effect", "ALLOW", "action", action, "pathPrefix", pathPrefix,
                    "attributes", Map.of("role", "payments")));
        }
        mvc.perform(post("/api/admin/policy")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("rules", rules))))
                .andExpect(status().isOk());
    }

    private String issue(String caller, String path, long ttl) throws Exception {
        String body = om.writeValueAsString(
                Map.of("callerId", caller, "secretPath", path, "ttlMillis", ttl));
        String resp = mvc.perform(post("/api/leases").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode node = om.readTree(resp);
        log.info("[API] issue 响应: {}", resp);
        return node.get("leaseId").asText();
    }

    @Test
    void fullLifecycleOverHttpAndDistinctErrorCodes() throws Exception {
        String path = uniquePath();
        register("api-alice", Map.of("role", "payments"));
        register("api-bob", Map.of("role", "readonly"));
        allow("secret/api/", "ISSUE", "RENEW");

        // 越权 -> 403 POLICY_DENIED
        String deniedResp = mvc.perform(post("/api/leases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "callerId", "api-bob", "secretPath", path, "ttlMillis", 5000))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("POLICY_DENIED"))
                .andReturn().getResponse().getContentAsString();
        log.warn("[API] 越权响应: {}", deniedResp);

        // 参数非法 -> 400 INVALID_PARAMETER（TTL 超上限）
        mvc.perform(post("/api/leases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "callerId", "api-alice", "secretPath", path, "ttlMillis", 99_000_000))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));

        String leaseId = issue("api-alice", path, 5_000);

        // 查无租约 -> 404 LEASE_NOT_FOUND
        mvc.perform(get("/api/leases/no-such-lease"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("LEASE_NOT_FOUND"));

        // 续租成功
        mvc.perform(post("/api/leases/" + leaseId + "/renew")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("ttlMillis", 8_000))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.renewCount").value(1));

        // 取用材料：轮换前后版本可见
        mvc.perform(post("/api/leases/" + leaseId + "/material"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveredVersion").value(1));
        mvc.perform(post("/api/admin/secrets/rotate").param("path", path))
                .andExpect(status().isOk());
        // 旧租约仍交付 v1，但 latestVersion=2
        mvc.perform(post("/api/leases/" + leaseId + "/material"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveredVersion").value(1))
                .andExpect(jsonPath("$.latestVersion").value(2));

        // 吊销成功，随后重复吊销 -> 409 LEASE_ALREADY_REVOKED
        mvc.perform(post("/api/leases/" + leaseId + "/revoke")).andExpect(status().isOk());
        String dup = mvc.perform(post("/api/leases/" + leaseId + "/revoke"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("LEASE_ALREADY_REVOKED"))
                .andReturn().getResponse().getContentAsString();
        log.warn("[API] 重复吊销响应: {}", dup);

        // 吊销后续租 -> 409 LEASE_NOT_ACTIVE
        mvc.perform(post("/api/leases/" + leaseId + "/renew")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("ttlMillis", 5000))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("LEASE_NOT_ACTIVE"));

        // 审计里能复盘整租约
        mvc.perform(get("/api/admin/audit/leases/" + leaseId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].eventType").exists());
    }

    @Test
    void decisionEndpointShowsMatchedRule() throws Exception {
        String path = uniquePath();
        register("api-carol", Map.of("role", "payments"));
        allow("secret/api/", "ISSUE");
        String resp = mvc.perform(post("/api/admin/decisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "callerId", "api-carol", "secretPath", path, "action", "ISSUE"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(true))
                .andExpect(jsonPath("$.matchedRuleId").value("rule-ISSUE"))
                .andReturn().getResponse().getContentAsString();
        log.info("[API] 判定响应（含命中规则与依据）: {}", resp);
    }
}
