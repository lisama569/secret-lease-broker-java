package com.github.lisama569.secretleasebrokerjava.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 端到端：真实 Spring 容器 + MockMvc，验证 HTTP 层错误码可区分、
 * 策略更新生效、轮换/吊销语义与审计可追溯。
 * 短 TTL（1 秒）用真实时钟验证自然过期。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BrokerApiIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(BrokerApiIntegrationTest.class);

    @Autowired
    private MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void resetState() throws Exception {
        mvc.perform(post("/api/local/reset")).andExpect(status().isOk());
    }

    private JsonNode postJson(String url, String body) throws Exception {
        MvcResult result = mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
        log.info("[http] POST-HELPER {} req={} -> status={} body={}",
                url, body, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        return json.readTree(result.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("越权申请返回 403/POLICY_DENIED；outsider 全程默认拒绝")
    void unauthorizedIssueIsForbidden() throws Exception {
        mvc.perform(post("/api/leases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"callerId":"outsider","path":"secret.dev.db","ttlMillis":60000}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("POLICY_DENIED"))
                .andExpect(jsonPath("$.category").value("POLICY_DENIED"));
    }

    @Test
    @DisplayName("参数非法返回 400；未知租约返回 404")
    void invalidParamAndNotFoundAreDistinct() throws Exception {
        mvc.perform(post("/api/leases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"callerId":"app-reader","path":"","ttlMillis":60000}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("PATH_BLANK"));

        mvc.perform(post("/api/leases/not-a-uuid/renew")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"callerId":"app-reader","ttlMillis":60000}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAM"));

        mvc.perform(post("/api/leases/00000000-0000-0000-0000-000000000000/revoke")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"callerId":"app-reader","reason":"x"}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("LEASE_NOT_FOUND"));
    }

    @Test
    @DisplayName("完整生命周期：签发 -> 读 -> 吊销 -> 再读 410/LEASE_REVOKED -> 重复吊销 410")
    void fullLifecycleThroughHttp() throws Exception {
        JsonNode lease = postJson("/api/leases", """
                {"callerId":"app-reader","path":"secret.dev.http","ttlMillis":60000}
                """);
        String leaseId = lease.get("leaseId").asText();

        mvc.perform(get("/api/secrets/secret.dev.http")
                        .param("callerId", "app-reader")
                        .param("leaseId", leaseId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));

        mvc.perform(post("/api/leases/" + leaseId + "/revoke")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"callerId":"app-reader","reason":"e2e revoke"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));

        mvc.perform(get("/api/secrets/secret.dev.http")
                        .param("callerId", "app-reader")
                        .param("leaseId", leaseId))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.errorCode").value("LEASE_REVOKED"));

        mvc.perform(post("/api/leases/" + leaseId + "/revoke")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"callerId":"app-reader","reason":"again"}
                                """))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.errorCode").value("LEASE_ALREADY_REVOKED"));
    }

    @Test
    @DisplayName("自然过期：ttl=1s，睡眠后读返回 410/LEASE_EXPIRED")
    void expiryMakesLeaseUnusable() throws Exception {
        JsonNode lease = postJson("/api/leases", """
                {"callerId":"app-reader","path":"secret.dev.expiry","ttlMillis":1000}
                """);
        String leaseId = lease.get("leaseId").asText();

        Thread.sleep(1200);

        mvc.perform(get("/api/secrets/secret.dev.expiry")
                        .param("callerId", "app-reader")
                        .param("leaseId", leaseId))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.errorCode").value("LEASE_EXPIRED"));
    }

    @Test
    @DisplayName("策略更新后旧判定缓存失效：先允许，收紧策略后立即拒绝，审计可追溯")
    void policyUpdateInvalidatesOldDecisions() throws Exception {
        // 先确认 app-reader 当前被允许
        mvc.perform(post("/api/decide")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"callerId":"app-reader","path":"secret.dev.db","action":"ISSUE"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(true));

        // 记录当前版本，收紧策略后版本应严格递增
        MvcResult beforeSnap = mvc.perform(get("/api/policy")).andReturn();
        long beforeVersion = json.readTree(beforeSnap.getResponse().getContentAsString())
                .get("policyVersion").asLong();

        // 收紧：只保留对 legacy 团队的 DENY（app-reader 不再满足任何 ALLOW）
        MvcResult replaceResult = mvc.perform(post("/api/policy/replace")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"rules":[{"id":"deny-legacy","action":null,"pathPattern":"secret.dev.*",
                                "requiredAttributes":{"role":"app","team":"legacy"},
                                "effect":"DENY","priority":10}]}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        long afterVersion = json.readTree(replaceResult.getResponse().getContentAsString())
                .get("policyVersion").asLong();
        org.assertj.core.api.Assertions.assertThat(afterVersion).isGreaterThan(beforeVersion);

        mvc.perform(post("/api/decide")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"callerId":"app-reader","path":"secret.dev.db","action":"ISSUE"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(false))
                .andExpect(jsonPath("$.ruleId").value("DEFAULT_DENY"));

        mvc.perform(post("/api/leases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"callerId":"app-reader","path":"secret.dev.db","ttlMillis":60000}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("POLICY_DENIED"));

        mvc.perform(get("/api/audit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.type=='POLICY_REPLACE')]").exists());
    }

    @Test
    @DisplayName("HARD 轮换经 HTTP：旧租约读后 410；SOFT 轮换旧租约仍可读")
    void rotationModesOverHttp() throws Exception {
        JsonNode hardLease = postJson("/api/leases", """
                {"callerId":"app-reader","path":"secret.dev.rot-hard","ttlMillis":60000}
                """);
        String hardId = hardLease.get("leaseId").asText();
        mvc.perform(post("/api/secrets/secret.dev.rot-hard/rotate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"callerId":"ops-admin","value":"new-v2","mode":"HARD"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.newVersion").value(2))
                .andExpect(jsonPath("$.revokedLeaseCount").value(1));
        mvc.perform(get("/api/secrets/secret.dev.rot-hard")
                        .param("callerId", "app-reader").param("leaseId", hardId))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.errorCode").value("LEASE_REVOKED"));

        JsonNode softLease = postJson("/api/leases", """
                {"callerId":"app-reader","path":"secret.dev.rot-soft","ttlMillis":60000}
                """);
        String softId = softLease.get("leaseId").asText();
        mvc.perform(post("/api/secrets/secret.dev.rot-soft/rotate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"callerId":"ops-admin","value":"new-v2","mode":"SOFT"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revokedLeaseCount").value(0));
        mvc.perform(get("/api/secrets/secret.dev.rot-soft")
                        .param("callerId", "app-reader").param("leaseId", softId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
    }
}
