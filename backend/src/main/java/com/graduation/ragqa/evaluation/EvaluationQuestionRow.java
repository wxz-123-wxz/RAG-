package com.graduation.ragqa.evaluation;

public class EvaluationQuestionRow {
    private Long id;
    private Long knowledgeBaseId;
    private String questionCode;
    private String question;
    private String referenceAnswer;
    private String referenceDocument;
    private String referenceSection;
    private String referencePages;
    private String evidenceText;
    private String questionType;
    private Boolean shouldRefuse;
    private String reviewStatus;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getKnowledgeBaseId() { return knowledgeBaseId; }
    public void setKnowledgeBaseId(Long knowledgeBaseId) { this.knowledgeBaseId = knowledgeBaseId; }
    public String getQuestionCode() { return questionCode; }
    public void setQuestionCode(String questionCode) { this.questionCode = questionCode; }
    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }
    public String getReferenceAnswer() { return referenceAnswer; }
    public void setReferenceAnswer(String referenceAnswer) { this.referenceAnswer = referenceAnswer; }
    public String getReferenceDocument() { return referenceDocument; }
    public void setReferenceDocument(String referenceDocument) { this.referenceDocument = referenceDocument; }
    public String getReferenceSection() { return referenceSection; }
    public void setReferenceSection(String referenceSection) { this.referenceSection = referenceSection; }
    public String getReferencePages() { return referencePages; }
    public void setReferencePages(String referencePages) { this.referencePages = referencePages; }
    public String getEvidenceText() { return evidenceText; }
    public void setEvidenceText(String evidenceText) { this.evidenceText = evidenceText; }
    public String getQuestionType() { return questionType; }
    public void setQuestionType(String questionType) { this.questionType = questionType; }
    public Boolean getShouldRefuse() { return shouldRefuse; }
    public void setShouldRefuse(Boolean shouldRefuse) { this.shouldRefuse = shouldRefuse; }
    public String getReviewStatus() { return reviewStatus; }
    public void setReviewStatus(String reviewStatus) { this.reviewStatus = reviewStatus; }
}
