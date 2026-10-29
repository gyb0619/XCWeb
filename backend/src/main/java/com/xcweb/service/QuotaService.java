package com.xcweb.service;

import com.xcweb.config.XcWebProperties;
import com.xcweb.dto.ChatCompletionRequest;
import com.xcweb.exception.ApiException;
import com.xcweb.exception.ContextTooLongException;
import com.xcweb.exception.QuotaExceededException;
import com.xcweb.security.AuthPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class QuotaService {

    private final JdbcTemplate jdbcTemplate;
    private final XcWebProperties properties;

    public QuotaService(JdbcTemplate jdbcTemplate, XcWebProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    public ZoneId getZoneId() {
        try {
            return ZoneId.of(properties.getTimezone());
        } catch (Exception e) {
            return ZoneId.of("Asia/Shanghai");
        }
    }

    public String todayStr() {
        return LocalDate.now(getZoneId()).format(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    public ZonedDateTime nextResetDateTime() {
        ZoneId zone = getZoneId();
        return LocalDate.now(zone).plusDays(1).atStartOfDay(zone);
    }

    public String resetAtIso() {
        return nextResetDateTime().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    public long resetInSeconds() {
        ZoneId zone = getZoneId();
        ZonedDateTime now = ZonedDateTime.now(zone);
        ZonedDateTime next = nextResetDateTime();
        return Math.max(1L, Duration.between(now, next).getSeconds());
    }

    public int estimateContextTokens(ChatCompletionRequest request) {
        if (request == null) {
            return 0;
        }
        double total = 0.0;
        if (request.getMessages() != null) {
            for (ChatCompletionRequest.ChatMessageDto msg : request.getMessages()) {
                total += 4.0;
                if (msg != null && msg.getContent() != null) {
                    total += estimateStringTokens(msg.getContent());
                }
            }
        }
        if (request.getRagContext() != null) {
            for (ChatCompletionRequest.RagChunkDto chunk : request.getRagContext()) {
                if (chunk != null && chunk.getContent() != null) {
                    total += 6.0 + estimateStringTokens(chunk.getContent());
                }
            }
        }
        return (int) Math.ceil(total);
    }

    public double estimateStringTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0.0;
        }
        double count = 0.0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) {
                count += 0.75;
            } else if (Character.isWhitespace(c)) {
                count += 0.15;
            } else {
                count += 0.35;
            }
        }
        return Math.max(1.0, count);
    }

    public int getContextLimitForRole(String role) {
        if ("guest".equalsIgnoreCase(role)) {
            return properties.getQuota().getGuestContextLimit();
        }
        if ("user".equalsIgnoreCase(role)) {
            return properties.getQuota().getUserContextLimit();
        }
        return Integer.MAX_VALUE;
    }

    public void checkContextLimit(AuthPrincipal principal, ChatCompletionRequest request) {
        String role = principal.role().toLowerCase();
        int limit = getContextLimitForRole(role);
        int estimated = estimateContextTokens(request);
        if (limit != Integer.MAX_VALUE && estimated > limit) {
            String formattedLimit = String.format("%,d", limit);
            throw new ContextTooLongException(
                    role,
                    estimated,
                    limit,
                    "单次提交上下文超出当前角色上限（" + formattedLimit + " Tokens），不消耗对话次数。"
            );
        }
    }

    private boolean isLoopbackAddress(String ip) {
        if (ip == null || ip.isBlank()) return true;
        return ip.equals("127.0.0.1") ||
               ip.equals("0:0:0:0:0:0:0:1") ||
               ip.equals("::1") ||
               ip.equalsIgnoreCase("localhost");
    }

    public synchronized void checkAndIncrementTokenIssue(String fingerprintHash, String clientIp) {
        String date = todayStr();
        int configuredLimit = properties.getQuota().getTokenIssueDailyLimit();
        boolean isLocal = isLoopbackAddress(clientIp);
        int limit = isLocal ? Math.max(configuredLimit, 200) : configuredLimit;

        String fpId = "token_fp:" + fingerprintHash;
        String ipId = "token_ip:" + clientIp;

        int fpCount = getCallCount(fpId, date);
        int ipCount = isLocal ? 0 : getCallCount(ipId, date);

        if (fpCount >= limit || (!isLocal && ipCount >= limit)) {
            throw new ApiException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "TOKEN_ISSUE_LIMIT_EXCEEDED",
                    "今日当前设备或网络签发匿名令牌数量已达上限（" + limit + "/" + limit + "），请使用已有令牌或注册登录账号。"
            );
        }

        incrementCallCount(fpId, "guest_token", date);
        if (!isLocal) {
            incrementCallCount(ipId, "guest_token", date);
        }
    }

    public synchronized void checkAndAcquireQuota(AuthPrincipal principal, String clientIp) {
        String date = todayStr();
        String resetAt = resetAtIso();
        String role = principal.role().toLowerCase();

        if ("guest".equals(role)) {
            int guestLimit = properties.getQuota().getGuestDailyLimit();
            int ipLimit = properties.getQuota().getIpDailyLimit();

            String guestKey = "guest:" + principal.guestId();
            String fpKey = "fp:" + principal.fingerprintHash();
            String ipKey = "ip:" + clientIp;

            int guestUsed = getCallCount(guestKey, date);
            int fpUsed = getCallCount(fpKey, date);
            int ipUsed = getCallCount(ipKey, date);

            if (guestUsed >= guestLimit) {
                throw new QuotaExceededException(
                        "guest",
                        guestUsed,
                        guestLimit,
                        resetAt,
                        "今日游客体验额度已用尽（" + guestLimit + "/" + guestLimit + " 次）。注册登录即可获得 5 倍额度，并开启云端存档。"
                );
            }
            if (fpUsed >= guestLimit) {
                throw new QuotaExceededException(
                        "guest",
                        fpUsed,
                        guestLimit,
                        resetAt,
                        "今日游客体验额度已用尽（" + guestLimit + "/" + guestLimit + " 次）。注册登录即可获得 5 倍额度，并开启云端存档。"
                );
            }
            if (ipUsed >= ipLimit) {
                throw new QuotaExceededException(
                        "guest",
                        ipUsed,
                        ipLimit,
                        resetAt,
                        "当前共享网络 IP 今日游客体验额度已达上限（" + ipLimit + "/" + ipLimit + " 次）。注册登录即可享独立账号额度。"
                );
            }

            incrementCallCount(guestKey, "guest", date);
            incrementCallCount(fpKey, "guest", date);
            incrementCallCount(ipKey, "guest", date);
            return;
        }

        String userKey = "user:" + principal.userId();
        if ("user".equals(role)) {
            int userLimit = properties.getQuota().getUserDailyLimit();
            int used = getCallCount(userKey, date);
            if (used >= userLimit) {
                throw new QuotaExceededException(
                        "user",
                        used,
                        userLimit,
                        resetAt,
                        "今日免费对话配额已达上限（" + userLimit + "/" + userLimit + " 次），将于明日 00:00 自动重置。"
                );
            }
            incrementCallCount(userKey, "user", date);
            return;
        }

        // admin or dev: unlimited calls, still record telemetry
        incrementCallCount(userKey, role, date);
    }

    public synchronized void rollbackQuotaCall(AuthPrincipal principal, String clientIp) {
        String date = todayStr();
        if (principal.isGuest()) {
            decrementCallCount("guest:" + principal.guestId(), date);
            decrementCallCount("fp:" + principal.fingerprintHash(), date);
            decrementCallCount("ip:" + clientIp, date);
        } else {
            decrementCallCount("user:" + principal.userId(), date);
        }
    }

    public synchronized void recordTokenUsage(AuthPrincipal principal, String clientIp, int totalTokens, boolean isMock) {
        // Per PRD 2.4: Mock fallback responses count as a call but not towards token usage statistics
        if (isMock || totalTokens <= 0) {
            return;
        }
        String date = todayStr();
        String role = principal.role().toLowerCase();
        if (principal.isGuest()) {
            addTokens("guest:" + principal.guestId(), "guest", date, totalTokens);
            addTokens("fp:" + principal.fingerprintHash(), "guest", date, totalTokens);
            addTokens("ip:" + clientIp, "guest", date, totalTokens);
        } else {
            addTokens("user:" + principal.userId(), role, date, totalTokens);
        }
    }

    public Map<String, Object> getQuotaStatus(AuthPrincipal principal, String clientIp) {
        String date = todayStr();
        String role = principal.role().toLowerCase();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("role", role);

        if ("guest".equals(role)) {
            int guestLimit = properties.getQuota().getGuestDailyLimit();
            int ipLimit = properties.getQuota().getIpDailyLimit();
            String guestKey = "guest:" + principal.guestId();
            String fpKey = "fp:" + principal.fingerprintHash();
            String ipKey = "ip:" + clientIp;

            int guestUsed = getCallCount(guestKey, date);
            int fpUsed = getCallCount(fpKey, date);
            int ipUsed = getCallCount(ipKey, date);
            int tokensToday = Math.max(getTokenCount(guestKey, date), getTokenCount(fpKey, date));

            int effectiveUsed = Math.max(guestUsed, fpUsed);
            result.put("used", effectiveUsed);
            result.put("limit", guestLimit);
            result.put("unlimited", false);
            result.put("tokensUsedToday", tokensToday);
            result.put("contextTokenLimit", properties.getQuota().getGuestContextLimit());
            result.put("resetAt", resetAtIso());
            result.put("resetInSeconds", resetInSeconds());

            Map<String, Object> dimensions = new LinkedHashMap<>();
            dimensions.put("guestUsed", guestUsed);
            dimensions.put("guestLimit", guestLimit);
            dimensions.put("fingerprintUsed", fpUsed);
            dimensions.put("fingerprintLimit", guestLimit);
            dimensions.put("ipUsed", ipUsed);
            dimensions.put("ipLimit", ipLimit);
            result.put("dimensions", dimensions);
        } else if ("user".equals(role)) {
            int userLimit = properties.getQuota().getUserDailyLimit();
            String userKey = "user:" + principal.userId();
            int used = getCallCount(userKey, date);
            int tokensToday = getTokenCount(userKey, date);

            result.put("used", used);
            result.put("limit", userLimit);
            result.put("unlimited", false);
            result.put("tokensUsedToday", tokensToday);
            result.put("contextTokenLimit", properties.getQuota().getUserContextLimit());
            result.put("resetAt", resetAtIso());
            result.put("resetInSeconds", resetInSeconds());
        } else {
            String userKey = "user:" + principal.userId();
            int used = getCallCount(userKey, date);
            int tokensToday = getTokenCount(userKey, date);

            result.put("used", used);
            result.put("limit", -1);
            result.put("unlimited", true);
            result.put("tokensUsedToday", tokensToday);
            result.put("contextTokenLimit", -1);
            result.put("resetAt", resetAtIso());
            result.put("resetInSeconds", resetInSeconds());
        }
        return result;
    }

    public int getCallCount(String identifier, String dateStr) {
        List<Integer> list = jdbcTemplate.queryForList(
                "SELECT call_count FROM quota_logs WHERE identifier = ? AND date_str = ?",
                Integer.class,
                identifier,
                dateStr
        );
        return list.isEmpty() || list.get(0) == null ? 0 : list.get(0);
    }

    public int getTokenCount(String identifier, String dateStr) {
        List<Integer> list = jdbcTemplate.queryForList(
                "SELECT token_count FROM quota_logs WHERE identifier = ? AND date_str = ?",
                Integer.class,
                identifier,
                dateStr
        );
        return list.isEmpty() || list.get(0) == null ? 0 : list.get(0);
    }

    private void incrementCallCount(String identifier, String role, String dateStr) {
        jdbcTemplate.update("""
            INSERT INTO quota_logs (identifier, role, date_str, call_count, token_count, updated_at)
            VALUES (?, ?, ?, 1, 0, CURRENT_TIMESTAMP)
            ON CONFLICT(identifier, date_str)
            DO UPDATE SET call_count = call_count + 1, updated_at = CURRENT_TIMESTAMP
            """, identifier, role, dateStr);
    }

    private void decrementCallCount(String identifier, String dateStr) {
        jdbcTemplate.update("""
            UPDATE quota_logs
            SET call_count = CASE WHEN call_count > 0 THEN call_count - 1 ELSE 0 END,
                updated_at = CURRENT_TIMESTAMP
            WHERE identifier = ? AND date_str = ?
            """, identifier, dateStr);
    }

    private void addTokens(String identifier, String role, String dateStr, int tokens) {
        jdbcTemplate.update("""
            INSERT INTO quota_logs (identifier, role, date_str, call_count, token_count, updated_at)
            VALUES (?, ?, ?, 0, ?, CURRENT_TIMESTAMP)
            ON CONFLICT(identifier, date_str)
            DO UPDATE SET token_count = token_count + ?, updated_at = CURRENT_TIMESTAMP
            """, identifier, role, dateStr, tokens, tokens);
    }
}
