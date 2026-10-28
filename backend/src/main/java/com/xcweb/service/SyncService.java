package com.xcweb.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xcweb.dto.SyncDtos;
import com.xcweb.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class SyncService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public SyncService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public List<SyncDtos.ConversationSyncDto> getConversationsSince(Long userId, long since) {
        List<Map<String, Object>> convRows = jdbcTemplate.queryForList("""
            SELECT id, title, model, created_at, updated_at
            FROM conversations
            WHERE user_id = ? AND updated_at > ?
            ORDER BY updated_at DESC
            """, userId, since);

        List<SyncDtos.ConversationSyncDto> result = new ArrayList<>();
        for (Map<String, Object> row : convRows) {
            String convId = String.valueOf(row.get("id"));
            SyncDtos.ConversationSyncDto dto = mapConversationRow(row, getMessagesForConversation(convId));
            result.add(dto);
        }
        return result;
    }

    @Transactional
    public Map<String, Object> upsertConversationWithArbitration(Long userId, SyncDtos.ConversationSyncDto incoming) {
        if (incoming == null || incoming.getId() == null || incoming.getId().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CONVERSATION", "会话 ID 不能为空");
        }
        long now = System.currentTimeMillis();
        long incomingUpdated = incoming.getUpdatedAt() != null ? incoming.getUpdatedAt() : now;
        long incomingCreated = incoming.getCreatedAt() != null ? incoming.getCreatedAt() : incomingUpdated;
        String title = incoming.getTitle() != null && !incoming.getTitle().isBlank() ? incoming.getTitle() : "新对话";
        String model = incoming.getModel() != null && !incoming.getModel().isBlank()
                ? incoming.getModel()
                : "deepseek-ai/DeepSeek-R1-Distill-Qwen-7B";

        List<Map<String, Object>> existingRows = jdbcTemplate.queryForList(
                "SELECT id, user_id, title, model, created_at, updated_at FROM conversations WHERE id = ?",
                incoming.getId()
        );

        if (!existingRows.isEmpty()) {
            Map<String, Object> existing = existingRows.get(0);
            long ownerId = ((Number) existing.get("user_id")).longValue();
            if (ownerId != userId) {
                throw new ApiException(HttpStatus.FORBIDDEN, "CONVERSATION_OWNERSHIP_ERROR", "无权修改其他用户的会话");
            }
            long serverUpdatedAt = ((Number) existing.get("updated_at")).longValue();
            if (serverUpdatedAt > incomingUpdated) {
                SyncDtos.ConversationSyncDto serverDto = mapConversationRow(existing, getMessagesForConversation(incoming.getId()));
                Map<String, Object> conflictResp = new LinkedHashMap<>();
                conflictResp.put("status", "server_newer");
                conflictResp.put("conversation", serverDto);
                return conflictResp;
            }

            jdbcTemplate.update("""
                UPDATE conversations
                SET title = ?, model = ?, updated_at = ?
                WHERE id = ? AND user_id = ?
                """, title, model, incomingUpdated, incoming.getId(), userId);
        } else {
            jdbcTemplate.update("""
                INSERT INTO conversations (id, user_id, title, model, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, incoming.getId(), userId, title, model, incomingCreated, incomingUpdated);
        }

        if (incoming.getMessages() != null) {
            jdbcTemplate.update("DELETE FROM messages WHERE conversation_id = ?", incoming.getId());
            for (SyncDtos.MessageSyncDto msg : incoming.getMessages()) {
                if (msg.getId() == null || msg.getId().isBlank()) {
                    continue;
                }
                long msgCreated = msg.getCreatedAt() != null ? msg.getCreatedAt() : now;
                jdbcTemplate.update("""
                    INSERT INTO messages (id, conversation_id, role, content, thinking_content, attachments_json, versions_json, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                        msg.getId(),
                        incoming.getId(),
                        msg.getRole() != null ? msg.getRole() : "user",
                        msg.getContent() != null ? msg.getContent() : "",
                        msg.getThinkingContent(),
                        msg.getAttachmentsJson(),
                        msg.getVersionsJson(),
                        msgCreated
                );
            }
        }

        List<Map<String, Object>> updatedRow = jdbcTemplate.queryForList(
                "SELECT id, title, model, created_at, updated_at FROM conversations WHERE id = ?",
                incoming.getId()
        );
        SyncDtos.ConversationSyncDto savedDto = mapConversationRow(updatedRow.get(0), getMessagesForConversation(incoming.getId()));
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("status", "synced");
        resp.put("conversation", savedDto);
        return resp;
    }

    @Transactional
    public void deleteConversation(Long userId, String conversationId) {
        jdbcTemplate.update("DELETE FROM messages WHERE conversation_id = ? AND conversation_id IN (SELECT id FROM conversations WHERE user_id = ?)",
                conversationId, userId);
        jdbcTemplate.update("DELETE FROM conversations WHERE id = ? AND user_id = ?", conversationId, userId);
    }

    public Map<String, Object> getUserSettings(Long userId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT settings_json, updated_at FROM user_settings WHERE user_id = ?",
                userId
        );
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("userId", userId);
        if (rows.isEmpty()) {
            Map<String, Object> defaultSettings = Map.of(
                    "theme", "dark",
                    "systemPrompt", "你是 XCWeb 智能认知助手，请提供严谨、结构化、富有洞察力的解答。",
                    "enableThinking", true,
                    "enableAgent", true
            );
            resp.put("settings", defaultSettings);
            resp.put("settingsJson", toJson(defaultSettings));
            resp.put("updatedAt", 0L);
            return resp;
        }
        Map<String, Object> row = rows.get(0);
        String settingsJson = String.valueOf(row.get("settings_json"));
        long updatedAt = ((Number) row.get("updated_at")).longValue();
        resp.put("settings", parseJsonMap(settingsJson));
        resp.put("settingsJson", settingsJson);
        resp.put("updatedAt", updatedAt);
        return resp;
    }

    public Map<String, Object> saveUserSettings(Long userId, Map<String, Object> payload) {
        long now = System.currentTimeMillis();
        long incomingUpdated = payload != null && payload.get("updatedAt") instanceof Number n ? n.longValue() : now;

        Object settingsObj = payload != null && payload.containsKey("settings") ? payload.get("settings") : payload;
        String settingsJson;
        if (payload != null && payload.get("settingsJson") instanceof String s && !s.isBlank()) {
            settingsJson = s;
        } else {
            settingsJson = toJson(settingsObj != null ? settingsObj : Map.of());
        }

        jdbcTemplate.update("""
            INSERT INTO user_settings (user_id, settings_json, updated_at)
            VALUES (?, ?, ?)
            ON CONFLICT(user_id)
            DO UPDATE SET settings_json = excluded.settings_json, updated_at = excluded.updated_at
            """, userId, settingsJson, incomingUpdated);

        return getUserSettings(userId);
    }

    private List<SyncDtos.MessageSyncDto> getMessagesForConversation(String conversationId) {
        List<Map<String, Object>> msgRows = jdbcTemplate.queryForList("""
            SELECT id, conversation_id, role, content, thinking_content, attachments_json, versions_json, created_at
            FROM messages
            WHERE conversation_id = ?
            ORDER BY created_at ASC
            """, conversationId);

        List<SyncDtos.MessageSyncDto> messages = new ArrayList<>();
        for (Map<String, Object> r : msgRows) {
            SyncDtos.MessageSyncDto m = new SyncDtos.MessageSyncDto();
            m.setId(String.valueOf(r.get("id")));
            m.setConversationId(String.valueOf(r.get("conversation_id")));
            m.setRole(String.valueOf(r.get("role")));
            m.setContent(r.get("content") != null ? String.valueOf(r.get("content")) : "");
            m.setThinkingContent(r.get("thinking_content") != null ? String.valueOf(r.get("thinking_content")) : null);
            m.setAttachmentsJson(r.get("attachments_json") != null ? String.valueOf(r.get("attachments_json")) : "[]");
            m.setVersionsJson(r.get("versions_json") != null ? String.valueOf(r.get("versions_json")) : "[]");
            m.setCreatedAt(r.get("created_at") instanceof Number n ? n.longValue() : 0L);
            messages.add(m);
        }
        return messages;
    }

    private SyncDtos.ConversationSyncDto mapConversationRow(Map<String, Object> row, List<SyncDtos.MessageSyncDto> messages) {
        SyncDtos.ConversationSyncDto dto = new SyncDtos.ConversationSyncDto();
        dto.setId(String.valueOf(row.get("id")));
        dto.setTitle(String.valueOf(row.get("title")));
        dto.setModel(String.valueOf(row.get("model")));
        dto.setCreatedAt(row.get("created_at") instanceof Number n ? n.longValue() : 0L);
        dto.setUpdatedAt(row.get("updated_at") instanceof Number n ? n.longValue() : 0L);
        dto.setMessages(messages);
        return dto;
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }

    private Map<String, Object> parseJsonMap(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }
}
