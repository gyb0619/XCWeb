package com.xcweb.service;

import com.xcweb.config.XcWebProperties;
import com.xcweb.exception.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ModelService {

    private static final Logger log = LoggerFactory.getLogger(ModelService.class);

    private final XcWebProperties properties;
    private final JdbcTemplate jdbcTemplate;

    public ModelService(XcWebProperties properties, JdbcTemplate jdbcTemplate) {
        this.properties = properties;
        this.jdbcTemplate = jdbcTemplate;
    }

    public static class EffectiveModelConfig {
        private final String modelId;
        private final String name;
        private final String provider;
        private final String baseUrl;
        private final String apiKey;
        private final boolean isMock;
        private final boolean supportsThinking;

        public EffectiveModelConfig(String modelId, String name, String provider, String baseUrl, String apiKey, boolean isMock, boolean supportsThinking) {
            this.modelId = modelId;
            this.name = name;
            this.provider = provider;
            this.baseUrl = baseUrl;
            this.apiKey = apiKey;
            this.isMock = isMock;
            this.supportsThinking = supportsThinking;
        }

        public String getModelId() {
            return modelId;
        }

        public String getName() {
            return name;
        }

        public String getProvider() {
            return provider;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public boolean isMock() {
            return isMock;
        }

        public boolean isSupportsThinking() {
            return supportsThinking;
        }
    }

    public static String maskApiKey(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String trimmed = raw.trim();
        if (trimmed.length() <= 8) {
            return "****";
        }
        return trimmed.substring(0, 3) + "****" + trimmed.substring(trimmed.length() - 4);
    }

    public boolean isProviderKeyConfigured(String provider) {
        if ("BuiltInMock".equalsIgnoreCase(provider)) {
            return true;
        }
        XcWebProperties.ProviderConfig cfg = getProviderConfig(provider);
        return cfg != null && cfg.getApiKey() != null && !cfg.getApiKey().isBlank();
    }

    public boolean isModelOrProviderKeyConfigured(String modelId, String provider) {
        if ("BuiltInMock".equalsIgnoreCase(provider) || "mock-cognitive-core".equalsIgnoreCase(modelId)) {
            return true;
        }
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT api_key FROM model_states WHERE model_id = ?",
                    modelId
            );
            if (!rows.isEmpty()) {
                Object k = rows.get(0).get("api_key");
                if (k != null && !String.valueOf(k).trim().isEmpty()) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            // fallback
        }
        return isProviderKeyConfigured(provider);
    }

    public XcWebProperties.ProviderConfig getProviderConfig(String provider) {
        if (provider == null) {
            return null;
        }
        Map<String, XcWebProperties.ProviderConfig> providers = properties.getLlm().getProviders();
        if (providers == null) {
            return null;
        }
        String lower = provider.toLowerCase();
        if (lower.contains("silicon")) {
            return providers.get("siliconflow");
        }
        if (lower.contains("zhipu") || lower.contains("bigmodel")) {
            return providers.get("zhipu");
        }
        if (lower.contains("groq")) {
            return providers.get("groq");
        }
        return providers.get(lower);
    }

    public EffectiveModelConfig getEffectiveModelConfig(String modelId) {
        String targetId = (modelId != null && !modelId.isBlank()) ? modelId.trim() : getDefaultModelId();
        XcWebProperties.PresetModel preset = findPresetModel(targetId);

        String name = preset != null ? preset.getName() : targetId;
        String provider = preset != null ? preset.getProvider() : "Custom";
        boolean supportsThinking = preset != null && preset.isSupportsThinking();
        boolean isMock = preset != null && preset.isMock();

        String baseUrl = "";
        String apiKey = "";

        XcWebProperties.ProviderConfig providerCfg = getProviderConfig(provider);
        if (providerCfg != null) {
            if (providerCfg.getBaseUrl() != null) baseUrl = providerCfg.getBaseUrl();
            if (providerCfg.getApiKey() != null) apiKey = providerCfg.getApiKey();
        }

        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT name, provider, base_url, api_key, supports_thinking, is_mock FROM model_states WHERE model_id = ?",
                    targetId
            );
            if (!rows.isEmpty()) {
                Map<String, Object> r = rows.get(0);
                if (r.get("name") != null && !String.valueOf(r.get("name")).isBlank()) {
                    name = String.valueOf(r.get("name"));
                }
                if (r.get("provider") != null && !String.valueOf(r.get("provider")).isBlank()) {
                    provider = String.valueOf(r.get("provider"));
                }
                if (r.get("base_url") != null && !String.valueOf(r.get("base_url")).isBlank()) {
                    baseUrl = String.valueOf(r.get("base_url"));
                }
                if (r.get("api_key") != null && !String.valueOf(r.get("api_key")).isBlank()) {
                    apiKey = String.valueOf(r.get("api_key"));
                }
                if (r.get("supports_thinking") != null) {
                    supportsThinking = ((Number) r.get("supports_thinking")).intValue() != 0;
                }
                if (r.get("is_mock") != null) {
                    isMock = ((Number) r.get("is_mock")).intValue() != 0;
                }
            }
        } catch (Exception ignored) {
            // use fallback values
        }

        return new EffectiveModelConfig(targetId, name, provider, baseUrl, apiKey, isMock, supportsThinking);
    }

    public boolean areAllExternalKeysMissing() {
        for (Map<String, Object> m : getAllPresetModelsWithState(false)) {
            if (!Boolean.TRUE.equals(m.get("isMock")) && Boolean.TRUE.equals(m.get("keyConfigured"))) {
                return false;
            }
        }
        return true;
    }

    public List<Map<String, Object>> getAllPresetModelsWithState() {
        return getAllPresetModelsWithState(false);
    }

    public List<Map<String, Object>> getAllPresetModelsWithState(boolean includeMaskedKeys) {
        Map<String, Map<String, Object>> stateMap = new HashMap<>();
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT model_id, name, provider, base_url, api_key, description, supports_thinking, enabled, is_default, is_mock FROM model_states"
            );
            for (Map<String, Object> row : rows) {
                stateMap.put(String.valueOf(row.get("model_id")), row);
            }
        } catch (Exception e) {
            log.warn("Failed to query full model_states, will fallback: {}", e.getMessage());
        }

        List<Map<String, Object>> result = new ArrayList<>();
        List<XcWebProperties.PresetModel> presetModels = properties.getLlm().getModels();
        if (presetModels == null) {
            presetModels = List.of();
        }

        // Add all preset models
        for (XcWebProperties.PresetModel preset : presetModels) {
            Map<String, Object> state = stateMap.get(preset.getId());
            boolean enabled = state != null && state.get("enabled") != null
                    ? ((Number) state.get("enabled")).intValue() != 0
                    : preset.isEnabled();
            boolean isDefault = state != null && state.get("is_default") != null
                    ? ((Number) state.get("is_default")).intValue() != 0
                    : preset.isDefault();

            String name = state != null && state.get("name") != null && !String.valueOf(state.get("name")).isBlank()
                    ? String.valueOf(state.get("name"))
                    : preset.getName();
            String provider = state != null && state.get("provider") != null && !String.valueOf(state.get("provider")).isBlank()
                    ? String.valueOf(state.get("provider"))
                    : preset.getProvider();
            String description = state != null && state.get("description") != null
                    ? String.valueOf(state.get("description"))
                    : preset.getDescription();
            boolean supportsThinking = state != null && state.get("supports_thinking") != null
                    ? ((Number) state.get("supports_thinking")).intValue() != 0
                    : preset.isSupportsThinking();
            boolean isMock = state != null && state.get("is_mock") != null
                    ? ((Number) state.get("is_mock")).intValue() != 0
                    : preset.isMock();

            String rawKey = state != null && state.get("api_key") != null ? String.valueOf(state.get("api_key")) : "";
            if (rawKey.isBlank()) {
                XcWebProperties.ProviderConfig pCfg = getProviderConfig(provider);
                if (pCfg != null && pCfg.getApiKey() != null) {
                    rawKey = pCfg.getApiKey();
                }
            }

            String baseUrl = state != null && state.get("base_url") != null ? String.valueOf(state.get("base_url")) : "";
            if (baseUrl.isBlank()) {
                XcWebProperties.ProviderConfig pCfg = getProviderConfig(provider);
                if (pCfg != null && pCfg.getBaseUrl() != null) {
                    baseUrl = pCfg.getBaseUrl();
                }
            }

            boolean keyConfigured = isMock || !rawKey.isBlank();

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", preset.getId());
            item.put("name", name);
            item.put("provider", provider);
            item.put("description", description);
            item.put("baseUrl", baseUrl);
            item.put("supportsThinking", supportsThinking);
            item.put("enabled", enabled);
            item.put("isDefault", isDefault);
            item.put("isMock", isMock);
            item.put("keyConfigured", keyConfigured);
            item.put("hasApiKey", !rawKey.isBlank());
            if (includeMaskedKeys) {
                item.put("apiKey", maskApiKey(rawKey));
            }
            result.add(item);
        }

        // Also add any custom models that exist in SQLite but are not in application.yml
        for (Map.Entry<String, Map<String, Object>> entry : stateMap.entrySet()) {
            String mid = entry.getKey();
            boolean existsInPreset = presetModels.stream().anyMatch(p -> p.getId().equalsIgnoreCase(mid));
            if (!existsInPreset) {
                Map<String, Object> s = entry.getValue();
                String rawKey = s.get("api_key") != null ? String.valueOf(s.get("api_key")) : "";
                boolean isMock = s.get("is_mock") != null && ((Number) s.get("is_mock")).intValue() != 0;
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", mid);
                item.put("name", s.getOrDefault("name", mid));
                item.put("provider", s.getOrDefault("provider", "Custom"));
                item.put("description", s.getOrDefault("description", ""));
                item.put("baseUrl", s.getOrDefault("base_url", ""));
                item.put("supportsThinking", s.get("supports_thinking") != null && ((Number) s.get("supports_thinking")).intValue() != 0);
                item.put("enabled", s.get("enabled") != null && ((Number) s.get("enabled")).intValue() != 0);
                item.put("isDefault", s.get("is_default") != null && ((Number) s.get("is_default")).intValue() != 0);
                item.put("isMock", isMock);
                item.put("keyConfigured", isMock || !rawKey.isBlank());
                item.put("hasApiKey", !rawKey.isBlank());
                if (includeMaskedKeys) {
                    item.put("apiKey", maskApiKey(rawKey));
                }
                result.add(item);
            }
        }

        return result;
    }

    public String getDefaultModelId() {
        List<Map<String, Object>> all = getAllPresetModelsWithState(false);
        for (Map<String, Object> m : all) {
            if (Boolean.TRUE.equals(m.get("isDefault")) && Boolean.TRUE.equals(m.get("enabled"))) {
                return String.valueOf(m.get("id"));
            }
        }
        return properties.getLlm().getDefaultModel();
    }

    public Map<String, Object> getPublicModelsResponse() {
        List<Map<String, Object>> all = getAllPresetModelsWithState(true);
        boolean allMissing = areAllExternalKeysMissing();

        List<Map<String, Object>> visible = new ArrayList<>();
        for (Map<String, Object> m : all) {
            if (!Boolean.TRUE.equals(m.get("enabled"))) {
                continue;
            }
            boolean isMock = Boolean.TRUE.equals(m.get("isMock"));
            boolean keyConfigured = Boolean.TRUE.equals(m.get("keyConfigured"));
            if (allMissing || isMock || keyConfigured) {
                visible.add(m);
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("defaultModel", getDefaultModelId());
        response.put("allExternalKeysMissing", allMissing);
        response.put("models", visible);
        return response;
    }

    public Map<String, Object> getAdminModelsResponse() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("defaultModel", getDefaultModelId());
        response.put("allExternalKeysMissing", areAllExternalKeysMissing());
        response.put("models", getAllPresetModelsWithState(true));
        return response;
    }

    public XcWebProperties.PresetModel findPresetModel(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return null;
        }
        List<XcWebProperties.PresetModel> list = properties.getLlm().getModels();
        if (list == null) return null;
        for (XcWebProperties.PresetModel m : list) {
            if (m.getId().equalsIgnoreCase(modelId.trim())) {
                return m;
            }
        }
        return null;
    }

    /**
     * Backward-compatible alias for AdminController & existing tests.
     */
    public synchronized Map<String, Object> updateModelState(String rawModelId, Map<String, Object> body) {
        return updateModelConfig(rawModelId, body);
    }

    /**
     * Allows all users to modify built-in models and configure custom endpoints & API keys.
     */
    public synchronized Map<String, Object> updateModelConfig(String rawModelId, Map<String, Object> body) {
        String targetModelId = rawModelId;
        if ((targetModelId == null || targetModelId.isBlank()) && body != null) {
            Object fromBody = body.getOrDefault("modelId", body.get("id"));
            if (fromBody != null) {
                targetModelId = String.valueOf(fromBody);
            }
        }
        if (targetModelId != null) {
            targetModelId = URLDecoder.decode(targetModelId, StandardCharsets.UTF_8).trim();
            if (targetModelId.startsWith("/")) {
                targetModelId = targetModelId.substring(1);
            }
        }

        if (targetModelId == null || targetModelId.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MISSING_MODEL_ID", "必须提供模型标识 (modelId 或 id)");
        }

        XcWebProperties.PresetModel preset = findPresetModel(targetModelId);

        String newName = body != null && body.get("name") instanceof String s ? s.trim() : null;
        String newProvider = body != null && body.get("provider") instanceof String s ? s.trim() : null;
        String newBaseUrl = body != null && body.get("baseUrl") instanceof String s ? s.trim() : null;
        if (newBaseUrl == null && body != null && body.get("base_url") instanceof String s) {
            newBaseUrl = s.trim();
        }
        String newApiKey = body != null && body.get("apiKey") instanceof String s ? s.trim() : null;
        if (newApiKey == null && body != null && body.get("api_key") instanceof String s) {
            newApiKey = s.trim();
        }
        String newDesc = body != null && body.get("description") instanceof String s ? s.trim() : null;

        Boolean enabled = body != null && body.get("enabled") instanceof Boolean b ? b : null;
        Boolean supportsThinking = body != null && body.get("supportsThinking") instanceof Boolean b ? b : null;
        if (supportsThinking == null && body != null && body.get("supports_thinking") instanceof Boolean b) {
            supportsThinking = b;
        }

        Boolean isDefault = null;
        if (body != null) {
            if (body.get("isDefault") instanceof Boolean b) {
                isDefault = b;
            } else if (body.get("default") instanceof Boolean b) {
                isDefault = b;
            }
        }

        // Verify if row exists
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM model_states WHERE model_id = ?",
                Integer.class,
                targetModelId
        );

        if (count == null || count == 0) {
            // Insert new model row
            String initialName = newName != null ? newName : (preset != null ? preset.getName() : targetModelId);
            String initialProvider = newProvider != null ? newProvider : (preset != null ? preset.getProvider() : "Custom");
            String initialBaseUrl = newBaseUrl != null ? newBaseUrl : "";
            String initialApiKey = (newApiKey != null && !newApiKey.contains("****")) ? newApiKey : "";
            String initialDesc = newDesc != null ? newDesc : (preset != null ? preset.getDescription() : "");
            boolean initialThinking = supportsThinking != null ? supportsThinking : (preset != null && preset.isSupportsThinking());
            boolean initialEnabled = enabled != null ? enabled : true;
            boolean initialDefault = Boolean.TRUE.equals(isDefault);
            boolean initialMock = preset != null && preset.isMock();

            jdbcTemplate.update("""
                INSERT INTO model_states (model_id, name, provider, base_url, api_key, description, supports_thinking, enabled, is_default, is_mock)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                targetModelId, initialName, initialProvider, initialBaseUrl, initialApiKey, initialDesc,
                initialThinking ? 1 : 0, initialEnabled ? 1 : 0, initialDefault ? 1 : 0, initialMock ? 1 : 0
            );
        } else {
            // Update existing row
            if (newName != null && !newName.isBlank()) {
                jdbcTemplate.update("UPDATE model_states SET name = ?, updated_at = CURRENT_TIMESTAMP WHERE model_id = ?", newName, targetModelId);
            }
            if (newProvider != null && !newProvider.isBlank()) {
                jdbcTemplate.update("UPDATE model_states SET provider = ?, updated_at = CURRENT_TIMESTAMP WHERE model_id = ?", newProvider, targetModelId);
            }
            if (newBaseUrl != null) {
                jdbcTemplate.update("UPDATE model_states SET base_url = ?, updated_at = CURRENT_TIMESTAMP WHERE model_id = ?", newBaseUrl, targetModelId);
            }
            if (newDesc != null) {
                jdbcTemplate.update("UPDATE model_states SET description = ?, updated_at = CURRENT_TIMESTAMP WHERE model_id = ?", newDesc, targetModelId);
            }
            if (supportsThinking != null) {
                jdbcTemplate.update("UPDATE model_states SET supports_thinking = ?, updated_at = CURRENT_TIMESTAMP WHERE model_id = ?", supportsThinking ? 1 : 0, targetModelId);
            }
            if (enabled != null) {
                jdbcTemplate.update("UPDATE model_states SET enabled = ?, updated_at = CURRENT_TIMESTAMP WHERE model_id = ?", enabled ? 1 : 0, targetModelId);
            }
            // Only update API key if not masked and not empty
            if (newApiKey != null && !newApiKey.isBlank() && !newApiKey.contains("****")) {
                jdbcTemplate.update("UPDATE model_states SET api_key = ?, updated_at = CURRENT_TIMESTAMP WHERE model_id = ?", newApiKey, targetModelId);
            } else if (Boolean.TRUE.equals(body != null ? body.get("clearApiKey") : false)) {
                jdbcTemplate.update("UPDATE model_states SET api_key = '', updated_at = CURRENT_TIMESTAMP WHERE model_id = ?", targetModelId);
            }
        }

        // Handle default model status
        if (Boolean.TRUE.equals(isDefault)) {
            jdbcTemplate.update("UPDATE model_states SET is_default = 0, updated_at = CURRENT_TIMESTAMP");
            jdbcTemplate.update("UPDATE model_states SET is_default = 1, enabled = 1, updated_at = CURRENT_TIMESTAMP WHERE model_id = ?", targetModelId);
        } else if (Boolean.FALSE.equals(isDefault)) {
            jdbcTemplate.update("UPDATE model_states SET is_default = 0, updated_at = CURRENT_TIMESTAMP WHERE model_id = ?", targetModelId);
        }

        // Synchronize in-memory provider config if relevant
        if (newProvider != null || (newApiKey != null && !newApiKey.contains("****")) || newBaseUrl != null) {
            String pName = newProvider != null ? newProvider : (preset != null ? preset.getProvider() : null);
            XcWebProperties.ProviderConfig pCfg = getProviderConfig(pName);
            if (pCfg != null) {
                if (newApiKey != null && !newApiKey.isBlank() && !newApiKey.contains("****")) {
                    pCfg.setApiKey(newApiKey);
                }
                if (newBaseUrl != null && !newBaseUrl.isBlank()) {
                    pCfg.setBaseUrl(newBaseUrl);
                }
            }
        }

        for (Map<String, Object> m : getAllPresetModelsWithState(true)) {
            if (targetModelId.equalsIgnoreCase(String.valueOf(m.get("id")))) {
                return m;
            }
        }

        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("id", targetModelId);
        fallback.put("enabled", enabled != null ? enabled : true);
        fallback.put("isDefault", Boolean.TRUE.equals(isDefault));
        return fallback;
    }

    /**
     * Reads system environment variables after explicit user consent and populates AI model configurations.
     */
    public synchronized Map<String, Object> importSystemEnvironmentVariables(boolean userConsented) {
        if (!userConsented) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "CONSENT_REQUIRED",
                    "安全策略：读取系统环境变量必须获得用户的明确授权与确认同意。"
            );
        }

        Map<String, String> env = System.getenv();
        List<String> detectedVariables = new ArrayList<>();
        int updatedCount = 0;

        // 1. SiliconFlow API Key & Base URL
        String siliconKey = env.getOrDefault("SILICONFLOW_API_KEY", env.get("SILICON_FLOW_API_KEY"));
        String siliconBaseUrl = env.get("SILICONFLOW_BASE_URL");
        if (siliconKey != null && !siliconKey.isBlank()) {
            detectedVariables.add("SILICONFLOW_API_KEY");
            int rows = jdbcTemplate.update(
                    "UPDATE model_states SET api_key = ?, updated_at = CURRENT_TIMESTAMP WHERE provider LIKE '%Silicon%'",
                    siliconKey.trim()
            );
            updatedCount += rows;
            XcWebProperties.ProviderConfig pCfg = getProviderConfig("siliconflow");
            if (pCfg != null) {
                pCfg.setApiKey(siliconKey.trim());
            }
        }
        if (siliconBaseUrl != null && !siliconBaseUrl.isBlank()) {
            detectedVariables.add("SILICONFLOW_BASE_URL");
            jdbcTemplate.update(
                    "UPDATE model_states SET base_url = ?, updated_at = CURRENT_TIMESTAMP WHERE provider LIKE '%Silicon%'",
                    siliconBaseUrl.trim()
            );
            XcWebProperties.ProviderConfig pCfg = getProviderConfig("siliconflow");
            if (pCfg != null) {
                pCfg.setBaseUrl(siliconBaseUrl.trim());
            }
        }

        // 2. Zhipu / BigModel API Key
        String zhipuKey = env.getOrDefault("ZHIPU_API_KEY", env.getOrDefault("ZHIPUAI_API_KEY", env.get("BIGMODEL_API_KEY")));
        if (zhipuKey != null && !zhipuKey.isBlank()) {
            String varName = env.containsKey("ZHIPU_API_KEY") ? "ZHIPU_API_KEY" : (env.containsKey("BIGMODEL_API_KEY") ? "BIGMODEL_API_KEY" : "ZHIPUAI_API_KEY");
            detectedVariables.add(varName);
            int rows = jdbcTemplate.update(
                    "UPDATE model_states SET api_key = ?, updated_at = CURRENT_TIMESTAMP WHERE model_id = 'glm-4-flash' OR provider LIKE '%Zhipu%' OR provider LIKE '%BigModel%'",
                    zhipuKey.trim()
            );
            updatedCount += rows;
            XcWebProperties.ProviderConfig pCfg = getProviderConfig("zhipu");
            if (pCfg != null) {
                pCfg.setApiKey(zhipuKey.trim());
            }
        }

        // 3. Groq API Key
        String groqKey = env.get("GROQ_API_KEY");
        if (groqKey != null && !groqKey.isBlank()) {
            detectedVariables.add("GROQ_API_KEY");
            int rows = jdbcTemplate.update(
                    "UPDATE model_states SET api_key = ?, updated_at = CURRENT_TIMESTAMP WHERE model_id = 'llama-3.3-70b-versatile' OR provider LIKE '%Groq%'",
                    groqKey.trim()
            );
            updatedCount += rows;
            XcWebProperties.ProviderConfig pCfg = getProviderConfig("groq");
            if (pCfg != null) {
                pCfg.setApiKey(groqKey.trim());
            }
        }

        // 4. OpenAI / DeepSeek API Key
        String deepseekKey = env.get("DEEPSEEK_API_KEY");
        String deepseekBaseUrl = env.get("DEEPSEEK_BASE_URL");
        if (deepseekKey != null && !deepseekKey.isBlank()) {
            detectedVariables.add("DEEPSEEK_API_KEY");
            int rows = jdbcTemplate.update(
                    "UPDATE model_states SET api_key = ?, updated_at = CURRENT_TIMESTAMP WHERE model_id LIKE '%deepseek%' AND (api_key IS NULL OR api_key = '')",
                    deepseekKey.trim()
            );
            updatedCount += rows;
            if (deepseekBaseUrl != null && !deepseekBaseUrl.isBlank()) {
                detectedVariables.add("DEEPSEEK_BASE_URL");
                jdbcTemplate.update(
                        "UPDATE model_states SET base_url = ?, updated_at = CURRENT_TIMESTAMP WHERE model_id LIKE '%deepseek%'",
                        deepseekBaseUrl.trim()
                );
            }
        }

        String openaiKey = env.get("OPENAI_API_KEY");
        if (openaiKey != null && !openaiKey.isBlank()) {
            detectedVariables.add("OPENAI_API_KEY");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("consentGranted", true);
        result.put("detectedVariables", detectedVariables);
        result.put("updatedCount", updatedCount);
        result.put("message", detectedVariables.isEmpty()
                ? "授权成功：未在当前系统环境变量中检测到 AI 配置项（如 SILICONFLOW_API_KEY、ZHIPU_API_KEY 等）"
                : "授权成功：已从系统环境变量中读取并配置 " + detectedVariables.size() + " 个环境变量 (" + String.join(", ", detectedVariables) + ")");
        result.put("models", getAllPresetModelsWithState(true));
        return result;
    }
}
