package com.xcweb.service;

import com.xcweb.config.XcWebProperties;
import com.xcweb.exception.ApiException;
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
import java.util.Set;

@Service
public class ModelService {

    private static final Set<String> ALLOWED_MUTATION_FIELDS = Set.of("modelId", "id", "enabled", "isDefault", "default");
    private static final Set<String> FORBIDDEN_MUTATION_FIELDS = Set.of(
            "apiKey", "api_key", "key", "secret", "endpoint", "baseUrl", "base_url", "url", "provider"
    );

    private final XcWebProperties properties;
    private final JdbcTemplate jdbcTemplate;

    public ModelService(XcWebProperties properties, JdbcTemplate jdbcTemplate) {
        this.properties = properties;
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean isProviderKeyConfigured(String provider) {
        if ("BuiltInMock".equalsIgnoreCase(provider)) {
            return true;
        }
        XcWebProperties.ProviderConfig cfg = getProviderConfig(provider);
        return cfg != null && cfg.getApiKey() != null && !cfg.getApiKey().isBlank();
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

    public boolean areAllExternalKeysMissing() {
        for (XcWebProperties.PresetModel m : properties.getLlm().getModels()) {
            if (!m.isMock() && isProviderKeyConfigured(m.getProvider())) {
                return false;
            }
        }
        return true;
    }

    public List<Map<String, Object>> getAllPresetModelsWithState() {
        Map<String, Map<String, Object>> stateMap = new HashMap<>();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT model_id, enabled, is_default FROM model_states");
        for (Map<String, Object> row : rows) {
            stateMap.put(String.valueOf(row.get("model_id")), row);
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (XcWebProperties.PresetModel preset : properties.getLlm().getModels()) {
            Map<String, Object> state = stateMap.get(preset.getId());
            boolean enabled = state != null
                    ? ((Number) state.get("enabled")).intValue() != 0
                    : preset.isEnabled();
            boolean isDefault = state != null
                    ? ((Number) state.get("is_default")).intValue() != 0
                    : preset.isDefault();
            boolean keyConfigured = isProviderKeyConfigured(preset.getProvider());

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", preset.getId());
            item.put("name", preset.getName());
            item.put("provider", preset.getProvider());
            item.put("description", preset.getDescription());
            item.put("supportsThinking", preset.isSupportsThinking());
            item.put("enabled", enabled);
            item.put("isDefault", isDefault);
            item.put("isMock", preset.isMock());
            item.put("keyConfigured", keyConfigured);
            result.add(item);
        }
        return result;
    }

    public String getDefaultModelId() {
        List<Map<String, Object>> all = getAllPresetModelsWithState();
        for (Map<String, Object> m : all) {
            if (Boolean.TRUE.equals(m.get("isDefault")) && Boolean.TRUE.equals(m.get("enabled"))) {
                return String.valueOf(m.get("id"));
            }
        }
        return properties.getLlm().getDefaultModel();
    }

    public Map<String, Object> getPublicModelsResponse() {
        List<Map<String, Object>> all = getAllPresetModelsWithState();
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
        response.put("models", getAllPresetModelsWithState());
        return response;
    }

    public XcWebProperties.PresetModel findPresetModel(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return null;
        }
        for (XcWebProperties.PresetModel m : properties.getLlm().getModels()) {
            if (m.getId().equalsIgnoreCase(modelId.trim())) {
                return m;
            }
        }
        return null;
    }

    public synchronized Map<String, Object> updateModelState(String rawModelId, Map<String, Object> body) {
        if (body != null) {
            for (String key : body.keySet()) {
                if (FORBIDDEN_MUTATION_FIELDS.contains(key) || !ALLOWED_MUTATION_FIELDS.contains(key)) {
                    throw new ApiException(
                            HttpStatus.BAD_REQUEST,
                            "FORBIDDEN_MODEL_MUTATION",
                            "安全策略拒绝：管理员仅允许启停服务端预置模型或设置默认模型，严禁新增模型或传入自定义接口地址与 API Key（非法字段：" + key + "）"
                    );
                }
            }
        }

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

        XcWebProperties.PresetModel preset = findPresetModel(targetModelId);
        if (preset == null) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "UNKNOWN_PRESET_MODEL",
                    "未知的预置模型标识：" + targetModelId + "。系统禁止注册非预置的自定义模型。"
            );
        }

        Boolean enabled = body != null && body.get("enabled") instanceof Boolean b ? b : null;
        Boolean isDefault = null;
        if (body != null) {
            if (body.get("isDefault") instanceof Boolean b) {
                isDefault = b;
            } else if (body.get("default") instanceof Boolean b) {
                isDefault = b;
            }
        }

        if (Boolean.TRUE.equals(isDefault)) {
            jdbcTemplate.update("UPDATE model_states SET is_default = 0, updated_at = CURRENT_TIMESTAMP");
            jdbcTemplate.update(
                    "UPDATE model_states SET is_default = 1, enabled = 1, updated_at = CURRENT_TIMESTAMP WHERE model_id = ?",
                    preset.getId()
            );
        } else {
            if (enabled != null) {
                jdbcTemplate.update(
                        "UPDATE model_states SET enabled = ?, updated_at = CURRENT_TIMESTAMP WHERE model_id = ?",
                        enabled ? 1 : 0,
                        preset.getId()
                );
            }
            if (Boolean.FALSE.equals(isDefault)) {
                jdbcTemplate.update(
                        "UPDATE model_states SET is_default = 0, updated_at = CURRENT_TIMESTAMP WHERE model_id = ?",
                        preset.getId()
                );
            }
        }

        for (Map<String, Object> m : getAllPresetModelsWithState()) {
            if (preset.getId().equals(m.get("id"))) {
                return m;
            }
        }
        throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "STATE_UPDATE_ERROR", "更新模型状态失败");
    }
}
