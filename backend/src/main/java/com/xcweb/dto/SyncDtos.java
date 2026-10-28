package com.xcweb.dto;

import java.util.List;

public class SyncDtos {

    public static class ConversationSyncDto {
        private String id;
        private String title;
        private String model;
        private Long createdAt;
        private Long updatedAt;
        private List<MessageSyncDto> messages;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public Long getCreatedAt() {
            return createdAt;
        }

        public void setCreatedAt(Long createdAt) {
            this.createdAt = createdAt;
        }

        public Long getUpdatedAt() {
            return updatedAt;
        }

        public void setUpdatedAt(Long updatedAt) {
            this.updatedAt = updatedAt;
        }

        public List<MessageSyncDto> getMessages() {
            return messages;
        }

        public void setMessages(List<MessageSyncDto> messages) {
            this.messages = messages;
        }
    }

    public static class MessageSyncDto {
        private String id;
        private String conversationId;
        private String role;
        private String content;
        private String thinkingContent;
        private String attachmentsJson;
        private String versionsJson;
        private Long createdAt;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getConversationId() {
            return conversationId;
        }

        public void setConversationId(String conversationId) {
            this.conversationId = conversationId;
        }

        public String getRole() {
            return role;
        }

        public void setRole(String role) {
            this.role = role;
        }

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }

        public String getThinkingContent() {
            return thinkingContent;
        }

        public void setThinkingContent(String thinkingContent) {
            this.thinkingContent = thinkingContent;
        }

        public String getAttachmentsJson() {
            return attachmentsJson;
        }

        public void setAttachmentsJson(String attachmentsJson) {
            this.attachmentsJson = attachmentsJson;
        }

        public String getVersionsJson() {
            return versionsJson;
        }

        public void setVersionsJson(String versionsJson) {
            this.versionsJson = versionsJson;
        }

        public Long getCreatedAt() {
            return createdAt;
        }

        public void setCreatedAt(Long createdAt) {
            this.createdAt = createdAt;
        }
    }
}
