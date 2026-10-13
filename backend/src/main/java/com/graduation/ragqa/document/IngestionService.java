package com.graduation.ragqa.document;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.graduation.ragqa.common.BusinessException;
import com.graduation.ragqa.knowledgebase.KnowledgeBaseAccessService;
import com.graduation.ragqa.knowledgebase.KnowledgeBaseMapper;
import com.graduation.ragqa.security.UserPrincipal;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

@Service
public class IngestionService {

    private final IngestionTaskMapper taskMapper;
    private final DocumentMapper documentMapper;
    private final KnowledgeBaseAccessService accessService;
    private final DocumentStorageService storageService;
    private final IngestionScheduler scheduler;
    private final DocumentChunkWriter chunkWriter;
    private final RagIndexClient ragIndexClient;
    private final KnowledgeBaseMapper knowledgeBaseMapper;

    public IngestionService(IngestionTaskMapper taskMapper, DocumentMapper documentMapper,
                            KnowledgeBaseAccessService accessService, DocumentStorageService storageService,
                            IngestionScheduler scheduler, DocumentChunkWriter chunkWriter,
                            RagIndexClient ragIndexClient, KnowledgeBaseMapper knowledgeBaseMapper) {
        this.taskMapper = taskMapper;
        this.documentMapper = documentMapper;
        this.accessService = accessService;
        this.storageService = storageService;
        this.scheduler = scheduler;
        this.chunkWriter = chunkWriter;
        this.ragIndexClient = ragIndexClient;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
    }

    public IngestionTaskRow getTask(Long taskId, UserPrincipal user) {
        IngestionTaskRow task = taskMapper.findById(taskId).orElseThrow(() -> notFound());
        Long kbId = taskMapper.findKnowledgeBaseId(taskId).orElseThrow(() -> notFound());
        accessService.requireManager(kbId, user.id());
        return task;
    }

    @Transactional
    public Long retry(Long taskId, UserPrincipal user) {
        IngestionTaskRow oldTask = taskMapper.findById(taskId).orElseThrow(() -> notFound());
        Long kbId = taskMapper.findKnowledgeBaseId(taskId).orElseThrow(() -> notFound());
        accessService.requireManager(kbId, user.id());
        if (!"FAILED".equals(oldTask.getStatus())) {
            throw BusinessException.conflict("TASK_NOT_FAILED", "只有失败任务可以重试");
        }
        Long versionId = oldTask.getDocumentVersionId();
        if (taskMapper.countActive(versionId) > 0) {
            throw BusinessException.conflict("TASK_ALREADY_RUNNING", "该文档版本已有活动任务");
        }
        long newTaskId = IdWorker.getId();
        taskMapper.insert(newTaskId, versionId, taskMapper.maxAttempt(versionId) + 1, MDC.get("traceId"));
            taskMapper.insertEvent(IdWorker.getId(), newTaskId, "QUEUED", "QUEUED", 0, "失败任务重新排队");
            Long scheduledId = newTaskId;
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    scheduler.schedule(scheduledId);
                }
            });
        } else {
            scheduler.schedule(scheduledId);
        }
        return newTaskId;
    }

    public Long enqueueRetry(Long taskId, UserPrincipal user) {
        return retry(taskId, user);
    }

    @Async("ingestionExecutor")
    public void processQueued(Long taskId) {
        Long versionId = taskMapper.findVersionId(taskId).orElse(null);
        if (versionId == null) return;
        try {
            if (taskMapper.tryStart(taskId) == 0) return;
            taskMapper.updateProgress(taskId, "PARSING", "PARSING", 10);
            event(taskId, "PARSING", "RUNNING", 10, "正在提取文本");
            String filename = documentMapper.findFilename(versionId).orElseThrow();
            String extension = extension(filename);
            if (!extension.equals("txt") && !extension.equals("md") && !extension.equals("markdown")) {
                throw new UnsupportedOperationException("PDF/DOCX parsing is not enabled yet");
            }
            String storageKey = documentMapper.findStorageKey(versionId).orElseThrow();
            byte[] bytes = Files.readAllBytes(storageService.resolve(storageKey));
            String text = new String(bytes, StandardCharsets.UTF_8);
            if (text.isBlank()) throw new IllegalArgumentException("Document contains no extractable text");

            String[] chunks = split(text, 600, 100);
            if (chunks.length == 0) throw new IllegalArgumentException("Document contains no usable text");
            taskMapper.updateProgress(taskId, "CHUNKING", "CHUNKING", 45);
            event(taskId, "CHUNKING", "RUNNING", 45, "正在按段落切片");
            chunkWriter.replace(versionId, filename, chunks);
            taskMapper.updateProgress(taskId, "EMBEDDING", "EMBEDDING", 65);
            event(taskId, "EMBEDDING", "RUNNING", 65, "正在调用哈希向量开发基线（非中文语义模型）");
            taskMapper.updateProgress(taskId, "INDEXING", "INDEXING", 85);
            event(taskId, "INDEXING", "RUNNING", 85, "正在写入全文检索索引");
            Long knowledgeBaseId = taskMapper.findKnowledgeBaseId(taskId).orElseThrow();
            taskMapper.updateProgress(taskId, "INDEXING", "INDEXING", 90);
            ragIndexClient.indexDocument(knowledgeBaseId, versionId, filename, chunks);
            if ("PUBLISHED".equals(knowledgeBaseMapper.findStatus(knowledgeBaseId).orElse("DRAFT"))) {
                documentMapper.updateDocumentStatusByVersion(versionId, "PUBLISHED");
            }
            taskMapper.complete(taskId);
            documentMapper.updateVersionStatus(versionId, "READY");
            event(taskId, "READY", "SUCCEEDED", 100, "文档处理完成");
        } catch (UnsupportedOperationException exception) {
            fail(taskId, versionId, "PARSER_NOT_CONFIGURED", "当前版本尚未接入 PDF/DOCX 解析器", "PARSING");
        } catch (IllegalArgumentException exception) {
            fail(taskId, versionId, "DOCUMENT_EMPTY", "文档没有可索引的有效文本", "PARSING");
        } catch (RagIndexClient.RagUnavailableException exception) {
            fail(taskId, versionId, "RAG_SERVICE_UNAVAILABLE", "RAG 检索服务不可用，请检查服务后重试", "INDEXING");
        } catch (Exception exception) {
            fail(taskId, versionId, "INGESTION_DEPENDENCY_UNAVAILABLE",
                    "文档处理服务尚未就绪，请检查 RAG 服务和模型配置", "EMBEDDING");
        } finally { MDC.remove("traceId"); }
    }

    private void fail(Long taskId, Long versionId, String code, String message, String stage) {
        taskMapper.markFailed(taskId, stage, code, message);
        documentMapper.updateVersionStatus(versionId, "FAILED");
        event(taskId, stage, "FAILED", 0, message);
    }

    private void event(Long taskId, String stage, String status, int progress, String detail) {
        taskMapper.insertEvent(IdWorker.getId(), taskId, stage, status, progress, detail);
    }

    private static String extension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase();
    }

    static String[] split(String text, int maxChars, int overlap) {
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (normalized.isEmpty()) return new String[0];
        if (maxChars < 1 || overlap < 0 || overlap >= maxChars) {
            throw new IllegalArgumentException("Invalid chunk parameters");
        }
        java.util.List<String> result = new java.util.ArrayList<>();
        int start = 0;
        while (start < normalized.length()) {
            int end = Math.min(start + maxChars, normalized.length());
            if (end < normalized.length()) {
                int boundary = normalized.lastIndexOf('\n', end);
                if (boundary > start + maxChars / 2) end = boundary;
            }
            String chunk = normalized.substring(start, end).trim();
            if (!chunk.isEmpty()) result.add(chunk);
            if (end >= normalized.length()) break;
            start = Math.max(start + 1, end - overlap);
        }
        return result.toArray(String[]::new);
    }

    private static BusinessException notFound() {
        return new BusinessException("RESOURCE_NOT_FOUND", "处理任务不存在或无权访问", HttpStatus.NOT_FOUND);
    }
}
