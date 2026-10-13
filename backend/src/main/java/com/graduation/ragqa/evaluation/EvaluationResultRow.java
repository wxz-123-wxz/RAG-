package com.graduation.ragqa.evaluation;

import java.time.LocalDateTime;

public class EvaluationResultRow {
    private Long id;
    private Long evaluationRunId;
    private Long evaluationQuestionId;
    private String status;
    private String generatedAnswer;
    private String retrievedChunkIdsJson;
    private String citationSnapshotJson;
    private Double recallAt5;
    private Double reciprocalRank;
    private Boolean refusalCorrect;
    private Integer citationAccuracy;
    private Integer latencyMs;
    private String errorCode;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getEvaluationRunId() { return evaluationRunId; }
    public void setEvaluationRunId(Long value) { this.evaluationRunId = value; }
    public Long getEvaluationQuestionId() { return evaluationQuestionId; }
    public void setEvaluationQuestionId(Long value) { this.evaluationQuestionId = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { this.status = value; }
    public String getGeneratedAnswer() { return generatedAnswer; }
    public void setGeneratedAnswer(String value) { this.generatedAnswer = value; }
    public String getRetrievedChunkIdsJson() { return retrievedChunkIdsJson; }
    public void setRetrievedChunkIdsJson(String value) { this.retrievedChunkIdsJson = value; }
    public String getCitationSnapshotJson() { return citationSnapshotJson; }
    public void setCitationSnapshotJson(String value) { this.citationSnapshotJson = value; }
    public Double getRecallAt5() { return recallAt5; }
    public void setRecallAt5(Double value) { this.recallAt5 = value; }
    public Double getReciprocalRank() { return reciprocalRank; }
    public void setReciprocalRank(Double value) { this.reciprocalRank = value; }
    public Boolean getRefusalCorrect() { return refusalCorrect; }
    public void setRefusalCorrect(Boolean value) { this.refusalCorrect = value; }
    public Integer getCitationAccuracy() { return citationAccuracy; }
    public void setCitationAccuracy(Integer value) { this.citationAccuracy = value; }
    public Integer getLatencyMs() { return latencyMs; }
    public void setLatencyMs(Integer value) { this.latencyMs = value; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String value) { this.errorCode = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime value) { this.createdAt = value; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime value) { this.updatedAt = value; }
}
