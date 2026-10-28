package com.xcweb.controller;

import com.xcweb.service.ModelService;
import com.xcweb.service.QuotaService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final ModelService modelService;
    private final QuotaService quotaService;
    private final JdbcTemplate jdbcTemplate;

    public AdminController(ModelService modelService,
                           QuotaService quotaService,
                           JdbcTemplate jdbcTemplate) {
        this.modelService = modelService;
        this.quotaService = quotaService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/models")
    public Map<String, Object> getAdminModels() {
        return modelService.getAdminModelsResponse();
    }

    @PutMapping("/models")
    public Map<String, Object> updateModelFromBody(@RequestBody Map<String, Object> body) {
        return modelService.updateModelState(null, body);
    }

    @PutMapping("/models/{*modelId}")
    public Map<String, Object> updateModelByPath(@PathVariable("modelId") String modelId,
                                                 @RequestBody(required = false) Map<String, Object> body) {
        return modelService.updateModelState(modelId, body);
    }

    @GetMapping("/quota/overview")
    public Map<String, Object> getQuotaOverview() {
        String today = quotaService.todayStr();

        Integer totalCallsToday = jdbcTemplate.queryForObject("""
            SELECT COALESCE(SUM(call_count), 0)
            FROM quota_logs
            WHERE date_str = ?
              AND identifier NOT LIKE 'fp:%'
              AND identifier NOT LIKE 'ip:%'
              AND identifier NOT LIKE 'token_%'
            """, Integer.class, today);

        Integer totalTokensToday = jdbcTemplate.queryForObject("""
            SELECT COALESCE(SUM(token_count), 0)
            FROM quota_logs
            WHERE date_str = ?
              AND identifier NOT LIKE 'fp:%'
              AND identifier NOT LIKE 'ip:%'
              AND identifier NOT LIKE 'token_%'
            """, Integer.class, today);

        Integer activeUsersToday = jdbcTemplate.queryForObject("""
            SELECT COUNT(DISTINCT identifier)
            FROM quota_logs
            WHERE date_str = ? AND role IN ('user', 'admin', 'dev') AND call_count > 0
            """, Integer.class, today);

        Integer activeGuestsToday = jdbcTemplate.queryForObject("""
            SELECT COUNT(DISTINCT identifier)
            FROM quota_logs
            WHERE date_str = ? AND role = 'guest'
              AND (identifier LIKE 'guest:%' OR identifier LIKE 'seed:guest%')
              AND call_count > 0
            """, Integer.class, today);

        List<Map<String, Object>> roleDist = jdbcTemplate.queryForList("""
            SELECT role,
                   COALESCE(SUM(call_count), 0) AS calls,
                   COALESCE(SUM(token_count), 0) AS tokens
            FROM quota_logs
            WHERE date_str = ?
              AND identifier NOT LIKE 'fp:%'
              AND identifier NOT LIKE 'ip:%'
              AND identifier NOT LIKE 'token_%'
            GROUP BY role
            """, today);

        List<Map<String, Object>> topConsumers = jdbcTemplate.queryForList("""
            SELECT identifier, role, call_count AS callCount, token_count AS tokenCount
            FROM quota_logs
            WHERE date_str = ?
              AND identifier NOT LIKE 'token_%'
            ORDER BY call_count DESC, token_count DESC
            LIMIT 10
            """, today);

        LocalDate todayDate = LocalDate.now(quotaService.getZoneId());
        List<Map<String, Object>> dailyTrend = new ArrayList<>();
        for (int i = 6; i >= 0; i--) {
            String dStr = todayDate.minusDays(i).format(DateTimeFormatter.ISO_LOCAL_DATE);
            Map<String, Object> dayRow = jdbcTemplate.queryForMap("""
                SELECT COALESCE(SUM(call_count), 0) AS calls,
                       COALESCE(SUM(token_count), 0) AS tokens
                FROM quota_logs
                WHERE date_str = ?
                  AND identifier NOT LIKE 'fp:%'
                  AND identifier NOT LIKE 'ip:%'
                  AND identifier NOT LIKE 'token_%'
                """, dStr);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("date", dStr);
            item.put("calls", ((Number) dayRow.get("calls")).intValue());
            item.put("tokens", ((Number) dayRow.get("tokens")).intValue());
            dailyTrend.add(item);
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("todayDate", today);
        resp.put("timezone", quotaService.getZoneId().getId());
        resp.put("resetAt", quotaService.resetAtIso());
        resp.put("totalCallsToday", totalCallsToday != null ? totalCallsToday : 0);
        resp.put("totalTokensToday", totalTokensToday != null ? totalTokensToday : 0);
        resp.put("activeUsersToday", activeUsersToday != null ? activeUsersToday : 0);
        resp.put("activeGuestsToday", activeGuestsToday != null ? activeGuestsToday : 0);
        resp.put("roleDistribution", roleDist);
        resp.put("topConsumers", topConsumers);
        resp.put("dailyTrend", dailyTrend);
        return resp;
    }
}
