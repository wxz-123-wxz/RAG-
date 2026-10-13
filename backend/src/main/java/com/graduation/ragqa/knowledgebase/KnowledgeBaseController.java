package com.graduation.ragqa.knowledgebase;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.graduation.ragqa.common.ApiResponse;
import com.graduation.ragqa.common.BusinessException;
import com.graduation.ragqa.security.UserPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.List;

@RestController
@RequestMapping("/api/v1/knowledge-bases")
public class KnowledgeBaseController {

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeBaseAccessService accessService;

    public KnowledgeBaseController(KnowledgeBaseMapper knowledgeBaseMapper, KnowledgeBaseAccessService accessService) {
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.accessService = accessService;
    }

    @GetMapping
    public ApiResponse<List<KnowledgeBaseRow>> list(@AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.success(knowledgeBaseMapper.findVisible(user.id(), accessService.isSystemAdmin(user.id())));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<KnowledgeBaseRow> create(
            @Valid @RequestBody KnowledgeBaseCreateRequest request,
            @AuthenticationPrincipal UserPrincipal user
    ) {
        if (!accessService.isKnowledgeBaseAdmin(user.id())) {
            throw new BusinessException("ACCESS_DENIED", "没有知识库管理权限", HttpStatus.FORBIDDEN);
        }
        long id = IdWorker.getId();
        knowledgeBaseMapper.insert(id, request.code(), request.name().trim(), request.courseName().trim(),
                request.description(), request.normalizedVisibility(), user.id());
        return ApiResponse.success(accessService.requireVisible(id, user.id()));
    }

    @GetMapping("/{id}")
    public ApiResponse<KnowledgeBaseRow> get(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal user
    ) {
        return ApiResponse.success(accessService.requireVisible(id, user.id()));
    }

    @PostMapping("/{id}/publish")
    @Transactional
    public ApiResponse<KnowledgeBaseRow> publish(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal user
    ) {
        accessService.requireManager(id, user.id());
        if (knowledgeBaseMapper.countPublishedDocuments(id) == 0) {
            throw new BusinessException("KNOWLEDGE_BASE_EMPTY", "至少发布一份处理完成的文档后才能发布知识库", HttpStatus.CONFLICT);
        }
        knowledgeBaseMapper.updateStatus(id, "PUBLISHED");
        return ApiResponse.success(accessService.requireVisible(id, user.id()));
    }

    @PostMapping("/{id}/offline")
    public ApiResponse<KnowledgeBaseRow> offline(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal user
    ) {
        accessService.requireManager(id, user.id());
        knowledgeBaseMapper.updateStatus(id, "OFFLINE");
        return ApiResponse.success(accessService.requireVisible(id, user.id()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal user) {
        KnowledgeBaseRow row = accessService.requireVisible(id, user.id());
        accessService.requireManager(id, user.id());
        if ("PUBLISHED".equals(row.getStatus())) {
            throw new BusinessException("KNOWLEDGE_BASE_MUST_BE_OFFLINE", "请先下架知识库", HttpStatus.CONFLICT);
        }
        knowledgeBaseMapper.softDelete(id);
    }
}
