package com.graduation.ragqa.document;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.graduation.ragqa.common.BusinessException;
import com.graduation.ragqa.knowledgebase.KnowledgeBaseAccessService;
import com.graduation.ragqa.knowledgebase.KnowledgeBaseMapper;
import com.graduation.ragqa.security.UserPrincipal;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class DocumentService {

    private static final long MAX_SIZE = 50L * 1024 * 1024;
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("pdf", "docx", "md", "markdown", "txt");

    private final KnowledgeBaseAccessService accessService;
    private final DocumentMapper documentMapper;
    private final IngestionTaskMapper taskMapper;
    private final DocumentStorageService storageService;
    private final IngestionScheduler ingestionScheduler;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final IngestionService ingestionService;

    public DocumentService(KnowledgeBaseAccessService accessService, DocumentMapper documentMapper,
                           IngestionTaskMapper taskMapper, DocumentStorageService storageService,
                           IngestionScheduler ingestionScheduler, KnowledgeBaseMapper knowledgeBaseMapper,
                           IngestionService ingestionService) {
        this.accessService = accessService;
        this.documentMapper = documentMapper;
        this.taskMapper = taskMapper;
        this.storageService = storageService;
        this.ingestionScheduler = ingestionScheduler;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.ingestionService = ingestionService;
    }

    @Transactional
    public DocumentUploadResult upload(Long knowledgeBaseId, UserPrincipal user,
                                       MultipartFile file, DocumentUploadRequest request) {
        accessService.requireManager(knowledgeBaseId, user.id());
        validate(file);
        String originalName = safeFilename(file.getOriginalFilename());
        String extension = extensionOf(originalName);
        String sha256 = sha256(file);
        if (documentMapper.findDuplicate(knowledgeBaseId, sha256).isPresent()) {
            throw BusinessException.conflict("DOCUMENT_DUPLICATE", "该知识库中已存在内容相同的文档");
        }

        long documentId = IdWorker.getId();
        long versionId = IdWorker.getId();
        long taskId = IdWorker.getId();
        String title = request.title() == null || request.title().isBlank()
                ? stripExtension(originalName)
                : request.title().trim();
        String storageKey = storageService.store(knowledgeBaseId, documentId, file);
        try {
            documentMapper.insertDocument(documentId, knowledgeBaseId, title,
                    request.category() == null ? null : request.category().trim(), user.id());
            documentMapper.insertVersion(versionId, documentId, originalName, storageKey,
                    mediaType(extension), extension, file.getSize(), sha256, user.id());
            documentMapper.setCurrentVersion(documentId, versionId);
            taskMapper.insert(taskId, versionId, 1, MDC.get("traceId"));
            taskMapper.insertEvent(IdWorker.getId(), taskId, "QUEUED", "QUEUED", 0, "等待处理");
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        ingestionScheduler.schedule(taskId);
                    }
                });
            }
        } catch (RuntimeException exception) {
            storageService.deleteQuietly(storageKey);
            throw exception;
        }
        if (knowledgeBaseMapper.countDocuments(knowledgeBaseId) == 1) {
            if ("PUBLISHED".equals(knowledgeBaseMapper.findStatus(knowledgeBaseId).orElse("DRAFT"))) {
                knowledgeBaseMapper.updateStatus(knowledgeBaseId, "DRAFT");
            }
        }
        return new DocumentUploadResult(
                Long.toString(documentId), Long.toString(versionId), Long.toString(taskId), "QUEUED");
    }

    @Transactional
    public void publish(Long kbId, Long documentId, UserPrincipal user) {
        accessService.requireManager(kbId, user.id());
        Long versionId = documentMapper.findCurrentVersion(kbId, documentId).orElseThrow(() ->
                new BusinessException("RESOURCE_NOT_FOUND", "文档不存在", HttpStatus.NOT_FOUND));
        if (!"READY".equals(documentMapper.findVersionStatus(versionId).orElse(""))) {
            throw BusinessException.conflict("DOCUMENT_NOT_READY", "文档完成索引后才能发布");
        }
        documentMapper.updateDocumentStatusByVersion(versionId, "PUBLISHED");
    }

    @Transactional
    public void offline(Long kbId, Long documentId, UserPrincipal user) {
        accessService.requireManager(kbId, user.id());
        Long versionId = documentMapper.findCurrentVersion(kbId, documentId).orElseThrow(() ->
                new BusinessException("RESOURCE_NOT_FOUND", "文档不存在", HttpStatus.NOT_FOUND));
        String status = documentMapper.findDocumentStatusByVersion(versionId).orElse("");
        if (!"PUBLISHED".equals(status)) {
            throw BusinessException.conflict("DOCUMENT_NOT_PUBLISHED", "只有已发布文档才能下架");
        }
        documentMapper.updateDocumentStatusByVersion(versionId, "OFFLINE");
    }

    private static void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("FILE_EMPTY", "请选择非空文件", HttpStatus.BAD_REQUEST);
        }
        if (file.getSize() > MAX_SIZE) {
            throw new BusinessException("FILE_TOO_LARGE", "单个文件不能超过 50 MB", HttpStatus.BAD_REQUEST);
        }
        String name = safeFilename(file.getOriginalFilename());
        String extension = extensionOf(name);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new BusinessException("FILE_TYPE_UNSUPPORTED", "仅支持 PDF、DOCX、Markdown 和 TXT 文件", HttpStatus.BAD_REQUEST);
        }
    }

    private static String safeFilename(String originalName) {
        if (originalName == null || originalName.isBlank()) return "document.txt";
        String normalized = originalName.replace('\\', '/');
        String name = normalized.substring(normalized.lastIndexOf('/') + 1)
                .replaceAll("[\\p{Cntrl}]", "_")
                .trim();
        if (name.isBlank() || name.equals(".") || name.equals("..")) {
            throw new BusinessException("INVALID_FILENAME", "文件名无效", HttpStatus.BAD_REQUEST);
        }
        if (name.length() > 255) name = name.substring(name.length() - 255);
        return name;
    }

    private static String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot <= 0 ? filename : filename.substring(0, dot);
    }

    private static String mediaType(String extension) {
        return switch (extension) {
            case "pdf" -> "application/pdf";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "md", "markdown" -> "text/markdown";
            default -> "text/plain";
        };
    }

    private static String sha256(MultipartFile file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = file.getInputStream()) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException exception) {
            throw new BusinessException("FILE_READ_FAILED", "无法读取上传文件", HttpStatus.BAD_REQUEST);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
