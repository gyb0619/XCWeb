package com.xcweb.controller;

import com.xcweb.service.QuotaService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/dev")
public class DevController {

    private final JdbcTemplate jdbcTemplate;
    private final QuotaService quotaService;

    public DevController(JdbcTemplate jdbcTemplate, QuotaService quotaService) {
        this.jdbcTemplate = jdbcTemplate;
        this.quotaService = quotaService;
    }

    @GetMapping("/metrics")
    public Map<String, Object> getMetrics() {
        ZoneId zoneId = quotaService.getZoneId();
        LocalDate today = LocalDate.now(zoneId);

        List<String> days = new ArrayList<>();
        for (int i = 6; i >= 0; i--) {
            days.add(today.minusDays(i).format(DateTimeFormatter.ISO_LOCAL_DATE));
        }

        int[][] heatmapGrid = new int[7][24];
        List<Map<String, Object>> logs = jdbcTemplate.queryForList("""
            SELECT model, is_mock, COALESCE(prompt_tokens, 0) AS pt,
                   COALESCE(completion_tokens, 0) AS ct,
                   COALESCE(latency_ms, 0) AS latency,
                   created_at
            FROM request_logs
            ORDER BY created_at DESC
            LIMIT 1000
            """);

        long totalRequests = logs.size();
        long mockCount = 0;
        long totalLatency = 0;
        long totalPromptTokens = 0;
        long totalCompletionTokens = 0;

        for (Map<String, Object> row : logs) {
            int pt = ((Number) row.get("pt")).intValue();
            int ct = ((Number) row.get("ct")).intValue();
            int latency = ((Number) row.get("latency")).intValue();
            int isMock = ((Number) row.get("is_mock")).intValue();
            long createdAt = ((Number) row.get("created_at")).longValue();

            totalPromptTokens += pt;
            totalCompletionTokens += ct;
            totalLatency += latency;
            if (isMock != 0) {
                mockCount++;
            }

            ZonedDateTime zdt = Instant.ofEpochMilli(createdAt).atZone(zoneId);
            String dStr = zdt.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE);
            int dayIdx = days.indexOf(dStr);
            if (dayIdx >= 0 && dayIdx < 7) {
                int hour = zdt.getHour();
                heatmapGrid[dayIdx][hour] += (pt + ct);
            }
        }

        // Convert [dayIndex, hourIndex, tokenValue] triples for ECharts heatmap
        List<List<Integer>> heatmapMatrix = new ArrayList<>();
        for (int d = 0; d < 7; d++) {
            for (int h = 0; h < 24; h++) {
                heatmapMatrix.add(List.of(h, d, heatmapGrid[d][h]));
            }
        }

        List<Map<String, Object>> modelStats = jdbcTemplate.queryForList("""
            SELECT model,
                   COUNT(1) AS requests,
                   COALESCE(SUM(prompt_tokens + completion_tokens), 0) AS totalTokens,
                   ROUND(AVG(latency_ms), 1) AS avgLatencyMs
            FROM request_logs
            GROUP BY model
            ORDER BY requests DESC
            """);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalRequests", totalRequests);
        summary.put("avgLatencyMs", totalRequests > 0 ? Math.round((double) totalLatency / totalRequests) : 0);
        summary.put("mockFallbackRate", totalRequests > 0 ? Math.round((mockCount * 1000.0) / totalRequests) / 10.0 : 0.0);
        summary.put("totalPromptTokens", totalPromptTokens);
        summary.put("totalCompletionTokens", totalCompletionTokens);
        summary.put("totalTokens", totalPromptTokens + totalCompletionTokens);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("summary", summary);
        resp.put("days", days);
        resp.put("tokenHeatmap", heatmapMatrix);
        resp.put("modelDistribution", modelStats);
        return resp;
    }

    @GetMapping("/requests")
    public Map<String, Object> getRedactedRequests(@RequestParam(name = "limit", defaultValue = "50") int limit) {
        int safeLimit = Math.max(1, Math.min(200, limit));
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            SELECT id, identifier, model, is_mock, prompt_tokens, completion_tokens, latency_ms, status, created_at
            FROM request_logs
            ORDER BY id DESC
            LIMIT ?
            """, safeLimit);

        List<Map<String, Object>> sanitized = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            int pt = r.get("prompt_tokens") instanceof Number n ? n.intValue() : 0;
            int ct = r.get("completion_tokens") instanceof Number n ? n.intValue() : 0;
            String rawIdentifier = String.valueOf(r.get("identifier"));

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", r.get("id"));
            item.put("identifier", redactIdentifier(rawIdentifier));
            item.put("model", r.get("model"));
            item.put("isMock", r.get("is_mock") instanceof Number n && n.intValue() != 0);
            item.put("promptTokens", pt);
            item.put("completionTokens", ct);
            item.put("totalTokens", pt + ct);
            item.put("latencyMs", r.get("latency_ms"));
            item.put("status", r.get("status"));
            item.put("createdAt", r.get("created_at"));
            item.put("redactedHeaders", Map.of(
                    "Authorization", "Bearer [REDACTED]",
                    "X-API-Key", "[REDACTED_SERVER_KEY]"
            ));
            sanitized.add(item);
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("requests", sanitized);
        resp.put("redacted", true);
        return resp;
    }

    private String redactIdentifier(String identifier) {
        if (identifier == null || identifier.length() <= 10) {
            return identifier;
        }
        if (identifier.startsWith("fp:")) {
            return identifier.substring(0, Math.min(11, identifier.length())) + "***";
        }
        return identifier;
    }
}
