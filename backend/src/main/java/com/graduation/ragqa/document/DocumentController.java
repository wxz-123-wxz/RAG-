package com.graduation.ragqa.document;

import com.graduation.ragqa.common.ApiResponse;
import com.graduation.ragqa.knowledgebase.KnowledgeBaseAccessService;
import com.graduation.ragqa.security.UserPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/v1/knowledge-bases/{knowledgeBaseId}/documents")
public class DocumentController {

    private final DocumentService documentService;
    private final KnowledgeBaseAccessService accessService;
    private final DocumentMapper documentMapper;

    public DocumentController(DocumentService documentService, KnowledgeBaseAccessService accessService,
                              DocumentMapper documentMapper) {
        this.documentService = documentService;
        this.accessService = accessService;
        this.documentMapper = documentMapper;
    }

    @PostMapping(consumes = "multipart/form-data")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<DocumentUploadResult> upload(
            @PathVariable Long knowledgeBaseId,
            @AuthenticationPrincipal UserPrincipal user,
            @RequestPart("file") MultipartFile file,
            @Valid @RequestPart(value = "metadata", required = false) DocumentUploadRequest metadata
    ) {
        DocumentUploadRequest request = metadata == null ? new DocumentUploadRequest(null, null) : metadata;
        return ApiResponse.success(documentService.upload(knowledgeBaseId, user, file, request));
    }

    @PostMapping("/{documentId}/publish")
    public ApiResponse<Void> publish(@PathVariable Long knowledgeBaseId, @PathVariable Long documentId,
                                     @AuthenticationPrincipal UserPrincipal user) {
        documentService.publish(knowledgeBaseId, documentId, user);
        return ApiResponse.success(null);
    }

    @PostMapping("/{documentId}/offline")
    public ApiResponse<Void> offline(@PathVariable Long knowledgeBaseId, @PathVariable Long documentId,
                                     @AuthenticationPrincipal UserPrincipal user) {
        documentService.offline(knowledgeBaseId, documentId, user);
        return ApiResponse.success(null);
    }

    @GetMapping("/{documentId}/latest-task")
    public ApiResponse<String> latestTask(@PathVariable Long knowledgeBaseId, @PathVariable Long documentId,
                                        @AuthenticationPrincipal UserPrincipal user) {
        accessService.requireManager(knowledgeBaseId, user.id());
        return ApiResponse.success(documentMapper.findLatestTaskId(knowledgeBaseId, documentId).orElse(null));
    }

    @GetMapping
    public ApiResponse<List<DocumentListRow>> list(
            @PathVariable Long knowledgeBaseId,
            @AuthenticationPrincipal UserPrincipal user
    ) {
        accessService.requireVisible(knowledgeBaseId, user.id());
        boolean manager = accessService.isManager(knowledgeBaseId, user.id());
        boolean includeDrafts = manager || documentMapper.countDocuments(knowledgeBaseId)
                == documentMapper.countPublished(knowledgeBaseId);
        return ApiResponse.success(documentMapper.findByKnowledgeBase(knowledgeBaseId, includeDrafts));
    }

    public record DocumentListRow(String id, String title, String category, String status,
                                  String filename, String parseStatus, Long fileSize,
                                  String createdAt) {
    }
}
