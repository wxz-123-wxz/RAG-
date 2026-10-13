package com.graduation.ragqa.document;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class IngestionStartupRecovery implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(IngestionStartupRecovery.class);
    private final IngestionTaskMapper taskMapper;
    private final IngestionScheduler scheduler;

    public IngestionStartupRecovery(IngestionTaskMapper taskMapper, IngestionScheduler scheduler) {
        this.taskMapper = taskMapper;
        this.scheduler = scheduler;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (Long interruptedId : taskMapper.findInterruptedIds()) {
            Long versionId = taskMapper.findVersionId(interruptedId).orElse(null);
            taskMapper.markFailed(interruptedId, "RECOVERY", "PROCESS_INTERRUPTED",
                    "服务重启时任务被中断，请重试");
            if (versionId != null) {
                log.info("Marked interrupted ingestion task {} as failed", interruptedId);
            }
        }
        for (Long taskId : taskMapper.findQueuedIds()) {
            log.info("Resuming queued ingestion task {}", taskId);
            scheduler.schedule(taskId);
        }
    }
}
