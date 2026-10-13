package com.graduation.ragqa.feedback;

import java.time.LocalDateTime;

public class AdminFeedbackRow {
    private Long id;
    private Long messageId;
    private Long knowledgeBaseId;
    private String knowledgeBaseName;
    private Long userId;
    private String username;
    private String displayName;
    private Boolean helpful;
    private Boolean citationError;
    private Boolean incomplete;
    private Boolean suspectedError;
    private String comment;
    private String question;
    private String answer;
    private String answerStatus;
    private String answerOrigin;
    private LocalDateTime submittedAt;

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public Long getMessageId() { return messageId; }
    public void setMessageId(Long value) { messageId = value; }
    public Long getKnowledgeBaseId() { return knowledgeBaseId; }
    public void setKnowledgeBaseId(Long value) { knowledgeBaseId = value; }
    public String getKnowledgeBaseName() { return knowledgeBaseName; }
    public void setKnowledgeBaseName(String value) { knowledgeBaseName = value; }
    public Long getUserId() { return userId; }
    public void setUserId(Long value) { userId = value; }
    public String getUsername() { return username; }
    public void setUsername(String value) { username = value; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String value) { displayName = value; }
    public Boolean getHelpful() { return helpful; }
    public void setHelpful(Boolean value) { helpful = value; }
    public Boolean getCitationError() { return citationError; }
    public void setCitationError(Boolean value) { citationError = value; }
    public Boolean getIncomplete() { return incomplete; }
    public void setIncomplete(Boolean value) { incomplete = value; }
    public Boolean getSuspectedError() { return suspectedError; }
    public void setSuspectedError(Boolean value) { suspectedError = value; }
    public String getComment() { return comment; }
    public void setComment(String value) { comment = value; }
    public String getQuestion() { return question; }
    public void setQuestion(String value) { question = value; }
    public String getAnswer() { return answer; }
    public void setAnswer(String value) { answer = value; }
    public String getAnswerStatus() { return answerStatus; }
    public void setAnswerStatus(String value) { answerStatus = value; }
    public String getAnswerOrigin() { return answerOrigin; }
    public void setAnswerOrigin(String value) { answerOrigin = value; }
    public LocalDateTime getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(LocalDateTime value) { submittedAt = value; }
}
