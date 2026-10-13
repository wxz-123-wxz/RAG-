package com.graduation.ragqa.knowledgebase;

import java.time.LocalDateTime;

public class KnowledgeBaseRow {

    private Long id;
    private String code;
    private String name;
    private String courseName;
    private String description;
    private String visibility;
    private String status;
    private LocalDateTime createdAt;
    private Long documentCount;
    private boolean manager;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getCourseName() { return courseName; }
    public void setCourseName(String courseName) { this.courseName = courseName; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getVisibility() { return visibility; }
    public void setVisibility(String visibility) { this.visibility = visibility; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public Long getDocumentCount() { return documentCount; }
    public void setDocumentCount(Long documentCount) { this.documentCount = documentCount; }
    public boolean isManager() { return manager; }
    public void setManager(boolean manager) { this.manager = manager; }
}
