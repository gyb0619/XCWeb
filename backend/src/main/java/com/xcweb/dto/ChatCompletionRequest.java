package com.xcweb.dto;

import java.util.List;

public class ChatCompletionRequest {

    private String conversationId;
    private String model;
    private List<ChatMessageDto> messages;
    private Boolean enableThinking;
    private Boolean enableAgent;
    private List<RagChunkDto> ragContext;
    private Double temperature;

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public List<ChatMessageDto> getMessages() {
        return messages;
    }

    public void setMessages(List<ChatMessageDto> messages) {
        this.messages = messages;
    }

    public Boolean getEnableThinking() {
        return enableThinking;
    }

    public void setEnableThinking(Boolean enableThinking) {
        this.enableThinking = enableThinking;
    }

    public Boolean getEnableAgent() {
        return enableAgent;
    }

    public void setEnableAgent(Boolean enableAgent) {
        this.enableAgent = enableAgent;
    }

    public List<RagChunkDto> getRagContext() {
        return ragContext;
    }

    public void setRagContext(List<RagChunkDto> ragContext) {
        this.ragContext = ragContext;
    }

    public Double getTemperature() {
        return temperature;
    }

    public void setTemperature(Double temperature) {
        this.temperature = temperature;
    }

    public static class ChatMessageDto {
        private String role;
        private String content;

        public ChatMessageDto() {
        }

        public ChatMessageDto(String role, String content) {
            this.role = role;
            this.content = content;
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
    }

    public static class RagChunkDto {
        private Integer index;
        private String docName;
        private String chunkId;
        private String content;
        private Double score;

        public Integer getIndex() {
            return index;
        }

        public void setIndex(Integer index) {
            this.index = index;
        }

        public String getDocName() {
            return docName;
        }

        public void setDocName(String docName) {
            this.docName = docName;
        }

        public String getChunkId() {
            return chunkId;
        }

        public void setChunkId(String chunkId) {
            this.chunkId = chunkId;
        }

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }

        public Double getScore() {
            return score;
        }

        public void setScore(Double score) {
            this.score = score;
        }
    }
}
