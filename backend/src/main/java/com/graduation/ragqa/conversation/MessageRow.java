package com.graduation.ragqa.conversation;

import java.time.LocalDateTime;

public class MessageRow {
    private Long id;
    private Long conversationId;
    private Long parentMessageId;
    private String role;
    private String content;
    private String answerStatus;
    private Integer versionNo;
    private Integer latencyMs;
    private LocalDateTime createdAt;
    private String modelName;
    private String answerOrigin;
    private String evidenceState;

    public String getModelName() { return modelName; }
    public void setModelName(String value) { modelName = value; }
    public String getAnswerOrigin() { return answerOrigin; }
    public void setAnswerOrigin(String value) { answerOrigin = value; }
    public String getEvidenceState() { return evidenceState; }
    public void setEvidenceState(String value) { evidenceState = value; }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getConversationId() { return conversationId; }
    public void setConversationId(Long conversationId) { this.conversationId = conversationId; }
    public Long getParentMessageId() { return parentMessageId; }
    public void setParentMessageId(Long parentMessageId) { this.parentMessageId = parentMessageId; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getAnswerStatus() { return answerStatus; }
    public void setAnswerStatus(String answerStatus) { this.answerStatus = answerStatus; }
    public Integer getVersionNo() { return versionNo; }
    public void setVersionNo(Integer versionNo) { this.versionNo = versionNo; }
    public Integer getLatencyMs() { return latencyMs; }
    public void setLatencyMs(Integer latencyMs) { this.latencyMs = latencyMs; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
