package com.xcweb;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xcweb.security.AuthPrincipal;
import com.xcweb.service.QuotaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:sqlite:target/test-xcweb.db?journal_mode=WAL&busy_timeout=5000",
        "xcweb.security.admin-username=admin_test",
        "xcweb.security.admin-password=AdminTestPassword!2026",
        "xcweb.security.dev-username=dev_test",
        "xcweb.security.dev-password=DevTestPassword!2026"
})
class XcWebBackendIntegrationTests {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private QuotaService quotaService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
        jdbcTemplate.update("DELETE FROM quota_logs WHERE identifier NOT LIKE 'seed:%'");
        jdbcTemplate.update("DELETE FROM guest_sessions");
        jdbcTemplate.update("DELETE FROM messages");
        jdbcTemplate.update("DELETE FROM conversations");
        jdbcTemplate.update("DELETE FROM users WHERE role = 'user'");
    }

    @Test
    @DisplayName("1. 游客三重校验：篡改令牌返回 401，指纹缺失或不匹配返回 403")
    void testGuestTripleCheckSecurity() throws Exception {
        String fp = "fp_triple_check_hash_000111222333444555";
        MvcResult issueRes = mockMvc.perform(post("/api/auth/guest")
                        .header("X-Forwarded-For", "10.0.1.10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("fingerprintHash", fp))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("guest"))
                .andReturn();

        Map<String, Object> guestAuth = objectMapper.readValue(
                issueRes.getResponse().getContentAsString(StandardCharsets.UTF_8),
                new TypeReference<>() {}
        );
        String token = String.valueOf(guestAuth.get("token"));

        // 1a. Tampered token signature -> 401
        String tamperedToken = token.substring(0, token.length() - 4) + "XXXX";
        mockMvc.perform(get("/api/quota/status")
                        .header("Authorization", "Bearer " + tamperedToken)
                        .header("X-Fingerprint", fp))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));

        // 1b. Mismatched X-Fingerprint header -> 403
        mockMvc.perform(get("/api/quota/status")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Fingerprint", "fp_wrong_another_device_999999"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FINGERPRINT_MISMATCH"));

        // 1c. Missing X-Fingerprint header -> 403
        mockMvc.perform(get("/api/quota/status")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FINGERPRINT_MISMATCH"));

        // 1d. Valid token + matching X-Fingerprint -> 200 OK
        mockMvc.perform(get("/api/quota/status")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Fingerprint", fp))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("guest"))
                .andExpect(jsonPath("$.limit").value(10));
    }

    @Test
    @DisplayName("2. 游客匿名令牌每日签发防刷上限（同指纹第 6 次签发返回 429）")
    void testGuestTokenIssueDailyLimit() throws Exception {
        String fp = "fp_token_spam_test_abcdef1234567890";
        String ip = "10.0.2.55";

        for (int i = 1; i <= 5; i++) {
            mockMvc.perform(post("/api/auth/guest")
                            .header("X-Forwarded-For", ip)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("fingerprintHash", fp))))
                    .andExpect(status().isOk());
        }

        // 6th attempt on same fingerprint/IP -> 429 TOKEN_ISSUE_LIMIT_EXCEEDED
        mockMvc.perform(post("/api/auth/guest")
                        .header("X-Forwarded-For", ip)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("fingerprintHash", fp))))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TOKEN_ISSUE_LIMIT_EXCEEDED"));
    }

    @Test
    @DisplayName("3. 上下文超 4K Tokens 返回 413 CONTEXT_TOO_LONG 且不消耗对话配额")
    void testGuestContextLimit413WithoutQuotaConsumption() throws Exception {
        String fp = "fp_context_4k_check_1234567890abcdef";
        String ip = "10.0.3.20";

        MvcResult issueRes = mockMvc.perform(post("/api/auth/guest")
                        .header("X-Forwarded-For", ip)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("fingerprintHash", fp))))
                .andExpect(status().isOk())
                .andReturn();

        String token = String.valueOf(objectMapper.readValue(
                issueRes.getResponse().getContentAsString(StandardCharsets.UTF_8),
                new TypeReference<Map<String, Object>>() {}
        ).get("token"));

        String hugePrompt = "超长上下文测试内容".repeat(1200); // > 4000 estimated tokens
        Map<String, Object> chatReq = Map.of(
                "model", "deepseek-ai/DeepSeek-R1-Distill-Qwen-7B",
                "messages", List.of(Map.of("role", "user", "content", hugePrompt))
        );

        mockMvc.perform(post("/api/chat/completions")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Fingerprint", fp)
                        .header("X-Forwarded-For", ip)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(chatReq)))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("CONTEXT_TOO_LONG"))
                .andExpect(jsonPath("$.limit").value(4000));

        // Verify used count is still 0
        mockMvc.perform(get("/api/quota/status")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Fingerprint", fp)
                        .header("X-Forwarded-For", ip))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.used").value(0));
    }

    @Test
    @DisplayName("4. 游客配额拦截与多维防刷：前 10 次流式成功，第 11 次返回 429；换新令牌后仍被同指纹拦截")
    void testGuest10CallsAnd11thBlockedAndFingerprintPersistence() throws Exception {
        String fp = "fp_quota_10_limit_test_9876543210abcdef";
        String ip1 = "10.0.4.101";
        String ip2 = "10.0.4.102";

        // Issue Token A & Token B upfront for the same device fingerprint
        String tokenA = issueGuestTokenHelper(fp, ip1);
        String tokenB = issueGuestTokenHelper(fp, ip2);

        Map<String, Object> chatReq = Map.of(
                "model", "deepseek-ai/DeepSeek-R1-Distill-Qwen-7B",
                "enableThinking", true,
                "enableAgent", true,
                "ragContext", List.of(
                        Map.of("index", 1, "docName", "spec.md", "chunkId", "c1", "content", "XCWeb 架构设计规范", "score", 0.95),
                        Map.of("index", 2, "docName", "spec.md", "chunkId", "c2", "content", "SQLite WAL 并发配置", "score", 0.89)
                ),
                "messages", List.of(Map.of("role", "user", "content", "请分析架构趋势并生成对比表格"))
        );

        // Execute 10 allowed calls with Token A
        for (int i = 1; i <= 10; i++) {
            MvcResult asyncStart = mockMvc.perform(post("/api/chat/completions")
                            .header("Authorization", "Bearer " + tokenA)
                            .header("X-Fingerprint", fp)
                            .header("X-Forwarded-For", ip1)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(chatReq)))
                    .andExpect(status().isOk())
                    .andExpect(request().asyncStarted())
                    .andReturn();

            MvcResult sseRes = mockMvc.perform(asyncDispatch(asyncStart))
                    .andExpect(status().isOk())
                    .andReturn();

            String sseBody = sseRes.getResponse().getContentAsString(StandardCharsets.UTF_8);
            if (i == 1) {
                assertThat(sseBody).contains("event:meta");
                assertThat(sseBody).contains("\"mock\":true");
                assertThat(sseBody).contains("event:agent_step");
                assertThat(sseBody).contains("event:thinking");
                assertThat(sseBody).contains("event:content");
                assertThat(sseBody).contains("【引用来源 [1]】");
                assertThat(sseBody).contains("【引用来源 [2]】");
                assertThat(sseBody).contains("event:usage");
                assertThat(sseBody).contains("event:done");
            }
            Thread.sleep(30);
        }

        // 11th call with Token A -> 429 QUOTA_EXCEEDED
        mockMvc.perform(post("/api/chat/completions")
                        .header("Authorization", "Bearer " + tokenA)
                        .header("X-Fingerprint", fp)
                        .header("X-Forwarded-For", ip1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(chatReq)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("QUOTA_EXCEEDED"))
                .andExpect(jsonPath("$.role").value("guest"))
                .andExpect(jsonPath("$.used").value(10))
                .andExpect(jsonPath("$.limit").value(10));

        // Attempt bypass by switching to Token B with the same fingerprint -> still 429 QUOTA_EXCEEDED!
        mockMvc.perform(post("/api/chat/completions")
                        .header("Authorization", "Bearer " + tokenB)
                        .header("X-Fingerprint", fp)
                        .header("X-Forwarded-For", ip2)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(chatReq)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("QUOTA_EXCEEDED"));

        // Verify IP dimension limit (30/day): simulate IP reaching 30 calls and verify new guest on same IP is blocked with 429
        String sharedIp = "10.0.4.250";
        String newFpOnSharedIp = "fp_new_device_on_shared_ip_1122334455";
        String tokenC = issueGuestTokenHelper(newFpOnSharedIp, sharedIp);
        jdbcTemplate.update("""
            INSERT INTO quota_logs (identifier, role, date_str, call_count, token_count)
            VALUES (?, 'guest', (SELECT date_str FROM quota_logs LIMIT 1), 30, 0)
            ON CONFLICT(identifier, date_str) DO UPDATE SET call_count = 30
            """, "ip:" + sharedIp);

        mockMvc.perform(post("/api/chat/completions")
                        .header("Authorization", "Bearer " + tokenC)
                        .header("X-Fingerprint", newFpOnSharedIp)
                        .header("X-Forwarded-For", sharedIp)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(chatReq)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("QUOTA_EXCEEDED"))
                .andExpect(jsonPath("$.used").value(30))
                .andExpect(jsonPath("$.limit").value(30));
    }

    @Test
    @DisplayName("5. 模型管理：允许所有用户修改内置模型配置、API Key 与端点，严格经用户同意授权后读取系统环境变量")
    void testAdminModelManagementAndSecurityBoundaries() throws Exception {
        String adminToken = loginHelper("admin_test", "AdminTestPassword!2026");

        // 5a. Admin can view all preset models
        mockMvc.perform(get("/api/admin/models")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.models.length()").value(5));

        // 5b. Can set glm-4-flash as default model
        mockMvc.perform(put("/api/admin/models/glm-4-flash")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("enabled", true, "isDefault", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("glm-4-flash"))
                .andExpect(jsonPath("$.isDefault").value(true));

        // Restore default to deepseek-ai/DeepSeek-R1-Distill-Qwen-7B (testing slash in modelId path!)
        mockMvc.perform(put("/api/admin/models/deepseek-ai/DeepSeek-R1-Distill-Qwen-7B")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("enabled", true, "isDefault", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("deepseek-ai/DeepSeek-R1-Distill-Qwen-7B"))
                .andExpect(jsonPath("$.isDefault").value(true));

        // 5c. All users can configure custom apiKey or endpoint for built-in model
        mockMvc.perform(put("/api/models/glm-4-flash")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "enabled", true,
                                "apiKey", "sk-user-custom-key-12345678",
                                "baseUrl", "https://custom-endpoint.example.com/v1"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("glm-4-flash"))
                .andExpect(jsonPath("$.hasApiKey").value(true));

        // 5d. System environment variable import requires explicit user consent
        // Without consent -> 400 Bad Request (CONSENT_REQUIRED)
        mockMvc.perform(post("/api/models/import-env")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("consent", false))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CONSENT_REQUIRED"));

        // With explicit user consent -> 200 OK
        mockMvc.perform(post("/api/models/import-env")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("consent", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.consentGranted").value(true));

        // 5e. Admin quota overview dashboard
        mockMvc.perform(get("/api/admin/quota/overview")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dailyTrend.length()").value(7));
    }

    @Test
    @DisplayName("6. 普通用户注册、云端双向增量同步（updatedAt 冲突仲裁）与开发者脱敏看板校验")
    void testUserSyncConflictArbitrationAndDevConsoleRedaction() throws Exception {
        // Register new user
        MvcResult regRes = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "sync_alice",
                                "email", "sync_alice@example.com",
                                "password", "AlicePass123!",
                                "role", "admin" // Should be ignored and forced to 'user'
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.role").value("user"))
                .andReturn();

        String userToken = String.valueOf(objectMapper.readValue(
                regRes.getResponse().getContentAsString(StandardCharsets.UTF_8),
                new TypeReference<Map<String, Object>>() {}
        ).get("token"));

        // Normal user cannot access /api/admin/models -> 403
        mockMvc.perform(get("/api/admin/models")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());

        // Sync conversation with updatedAt = 2000
        Map<String, Object> convNewer = Map.of(
                "id", "conv_sync_test_01",
                "title", "初次云端同步会话",
                "model", "deepseek-ai/DeepSeek-R1-Distill-Qwen-7B",
                "createdAt", 1000L,
                "updatedAt", 2000L,
                "messages", List.of(Map.of(
                        "id", "msg_sync_01",
                        "conversationId", "conv_sync_test_01",
                        "role", "user",
                        "content", "测试增量同步",
                        "createdAt", 1000L
                ))
        );

        mockMvc.perform(post("/api/sync/conversations")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(convNewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("synced"));

        // Stale client pushes updatedAt = 1500 (< 2000) -> server_newer conflict arbitration
        Map<String, Object> convStale = Map.of(
                "id", "conv_sync_test_01",
                "title", "过期客户端标题",
                "model", "deepseek-ai/DeepSeek-R1-Distill-Qwen-7B",
                "createdAt", 1000L,
                "updatedAt", 1500L,
                "messages", List.of()
        );

        mockMvc.perform(post("/api/sync/conversations")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(convStale)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("server_newer"))
                .andExpect(jsonPath("$.conversation.title").value("初次云端同步会话"));

        // Dev Console verification
        String devToken = loginHelper("dev_test", "DevTestPassword!2026");

        mockMvc.perform(get("/api/dev/metrics")
                        .header("Authorization", "Bearer " + devToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenHeatmap.length()").value(168)); // 7 days * 24 hours

        MvcResult devReqRes = mockMvc.perform(get("/api/dev/requests?limit=20")
                        .header("Authorization", "Bearer " + devToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.redacted").value(true))
                .andReturn();

        String devReqJson = devReqRes.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(devReqJson).contains("[REDACTED]");
        assertThat(devReqJson).doesNotContain("AdminTestPassword!2026");
        assertThat(devReqJson).doesNotContain("DevTestPassword!2026");
    }

    @Test
    @DisplayName("7. Token 计量核对与普通用户 16K 上下文校验：Mock 计入次数不计 Token，真实 usage 精准累加至 quota_logs.token_count")
    void testTokenUsageAccountingAndMockTokenExclusionAndUser16KContext() throws Exception {
        // Register a normal user (50 calls/day, 16,000 context limit)
        MvcResult regRes = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "quota_bob",
                                "email", "quota_bob@example.com",
                                "password", "BobSecurePass!2026"
                        ))))
                .andExpect(status().isOk())
                .andReturn();

        Map<String, Object> regMap = objectMapper.readValue(
                regRes.getResponse().getContentAsString(StandardCharsets.UTF_8),
                new TypeReference<>() {}
        );
        String userToken = String.valueOf(regMap.get("token"));
        @SuppressWarnings("unchecked")
        Map<String, Object> userObj = (Map<String, Object>) regMap.get("user");
        long userId = ((Number) userObj.get("id")).longValue();
        String userIdentifier = "user:" + userId;
        String today = quotaService.todayStr();

        // 7a. User submitting > 16,000 Tokens returns 413 CONTEXT_TOO_LONG without deducting call_count
        String over16kPrompt = "长文本上下文压力测试数据段落".repeat(2200); // > 16,000 estimated tokens
        Map<String, Object> over16kReq = Map.of(
                "model", "deepseek-ai/DeepSeek-R1-Distill-Qwen-7B",
                "messages", List.of(Map.of("role", "user", "content", over16kPrompt))
        );

        mockMvc.perform(post("/api/chat/completions")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(over16kReq)))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("CONTEXT_TOO_LONG"))
                .andExpect(jsonPath("$.limit").value(16000));

        assertThat(quotaService.getCallCount(userIdentifier, today)).isEqualTo(0);

        // 7b. Execute a Mock fallback streaming call: increments call_count to 1, but token_count remains 0 (PRD 2.4)
        Map<String, Object> normalChatReq = Map.of(
                "model", "deepseek-ai/DeepSeek-R1-Distill-Qwen-7B",
                "enableThinking", true,
                "messages", List.of(Map.of("role", "user", "content", "请简要说明配额计量规则"))
        );

        MvcResult asyncStart = mockMvc.perform(post("/api/chat/completions")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(normalChatReq)))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();

        MvcResult sseRes = mockMvc.perform(asyncDispatch(asyncStart))
                .andExpect(status().isOk())
                .andReturn();

        String sseBody = sseRes.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(sseBody).contains("event:meta");
        assertThat(sseBody).contains("\"mock\":true");
        assertThat(sseBody).contains("event:thinking");
        assertThat(sseBody).contains("event:content");

        assertThat(quotaService.getCallCount(userIdentifier, today)).isEqualTo(1);
        assertThat(quotaService.getTokenCount(userIdentifier, today)).isEqualTo(0);

        // 7c. Verify non-mock upstream usage accounting: quota_logs.token_count matches exact upstream usage
        AuthPrincipal userPrincipal = new AuthPrincipal(
                userId, "quota_bob", "quota_bob@example.com", null, null, "user", System.currentTimeMillis() + 3600_000L
        );
        quotaService.recordTokenUsage(userPrincipal, "10.0.8.88", 385, false);
        quotaService.recordTokenUsage(userPrincipal, "10.0.8.88", 615, false);

        Integer dbTokenCount = jdbcTemplate.queryForObject(
                "SELECT token_count FROM quota_logs WHERE identifier = ? AND date_str = ?",
                Integer.class,
                userIdentifier,
                today
        );
        assertThat(dbTokenCount).isEqualTo(1000);

        mockMvc.perform(get("/api/quota/status")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.used").value(1))
                .andExpect(jsonPath("$.limit").value(50))
                .andExpect(jsonPath("$.tokensUsedToday").value(1000));
    }

    private String issueGuestTokenHelper(String fp, String ip) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/auth/guest")
                        .header("X-Forwarded-For", ip)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("fingerprintHash", fp))))
                .andExpect(status().isOk())
                .andReturn();
        Map<String, Object> map = objectMapper.readValue(
                res.getResponse().getContentAsString(StandardCharsets.UTF_8),
                new TypeReference<>() {}
        );
        return String.valueOf(map.get("token"));
    }

    private String loginHelper(String username, String password) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username,
                                "password", password
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        Map<String, Object> map = objectMapper.readValue(
                res.getResponse().getContentAsString(StandardCharsets.UTF_8),
                new TypeReference<>() {}
        );
        return String.valueOf(map.get("token"));
    }
}
