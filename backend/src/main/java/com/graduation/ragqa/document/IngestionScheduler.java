package com.graduation.ragqa.document;

import org.springframework.stereotype.Component;

@Component
public class IngestionScheduler {

    private final org.springframework.beans.factory.ObjectProvider<IngestionService> ingestionService;

    public IngestionScheduler(org.springframework.beans.factory.ObjectProvider<IngestionService> ingestionService) {
        this.ingestionService = ingestionService;
    }

    public void schedule(Long taskId) {
        ingestionService.getObject().processQueued(taskId);
    }
}
