package com.graduation.ragqa.evaluation;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.graduation.ragqa.common.ApiResponse;
import com.graduation.ragqa.knowledgebase.KnowledgeBaseAccessService;
import com.graduation.ragqa.security.UserPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/knowledge-bases/{knowledgeBaseId}")
public class EvaluationController {
    private final EvaluationMapper mapper;
    private final EvaluationService evaluationService;
    private final KnowledgeBaseAccessService accessService;

    public EvaluationController(EvaluationMapper mapper, EvaluationService evaluationService,
                               KnowledgeBaseAccessService accessService) {
        this.mapper = mapper;
        this.evaluationService = evaluationService;
        this.accessService = accessService;
    }

    @GetMapping("/evaluation-questions")
    public ApiResponse<List<EvaluationQuestionRow>> questions(@PathVariable Long knowledgeBaseId,
                                                              @AuthenticationPrincipal UserPrincipal user) {
        accessService.requireManager(knowledgeBaseId, user.id());
        return ApiResponse.success(mapper.findQuestions(knowledgeBaseId));
    }

    @PostMapping("/evaluation-questions")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<String> addQuestion(@PathVariable Long knowledgeBaseId,
                                            @Valid @RequestBody EvaluationRequests.QuestionRequest request,
                                            @AuthenticationPrincipal UserPrincipal user) {
        accessService.requireManager(knowledgeBaseId, user.id());
        Long id = IdWorker.getId();
        mapper.insertQuestion(id, knowledgeBaseId, request.questionCode(), request.question(),
                request.referenceAnswer(), request.referenceDocument(), request.referenceSection(),
                request.referencePages(), request.evidenceText(), request.questionType(),
                Boolean.TRUE.equals(request.shouldRefuse()), user.id());
        return ApiResponse.success(id.toString());
    }

    @GetMapping("/evaluation-runs")
    public ApiResponse<List<EvaluationRunRow>> runs(@PathVariable Long knowledgeBaseId,
                                                    @AuthenticationPrincipal UserPrincipal user) {
        accessService.requireManager(knowledgeBaseId, user.id());
        return ApiResponse.success(mapper.findRuns(knowledgeBaseId));
    }

    @PostMapping("/evaluation-runs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<EvaluationRunRow> createRun(@PathVariable Long knowledgeBaseId,
                                          @Valid @RequestBody EvaluationRequests.RunRequest request,
                                          @AuthenticationPrincipal UserPrincipal user) {
        accessService.requireManager(knowledgeBaseId, user.id());
        Long id = IdWorker.getId();
        EvaluationRunRow run = evaluationService.createAndExecute(id, request.name(), knowledgeBaseId,
                request.retrievalMethod(), request.configSnapshotJson(), user.id());
        return ApiResponse.success(run);
    }

    @GetMapping("/evaluation-runs/{runId}")
    public ApiResponse<EvaluationRunRow> run(@PathVariable Long knowledgeBaseId, @PathVariable Long runId,
                                             @AuthenticationPrincipal UserPrincipal user) {
        accessService.requireManager(knowledgeBaseId, user.id());
        return ApiResponse.success(mapper.findRun(runId, knowledgeBaseId).orElseThrow(() ->
                new com.graduation.ragqa.common.BusinessException("RESOURCE_NOT_FOUND", "评测运行不存在", HttpStatus.NOT_FOUND)));
    }

    @GetMapping("/evaluation-runs/{runId}/results")
    public ApiResponse<List<EvaluationResultRow>> results(@PathVariable Long knowledgeBaseId,
                                                           @PathVariable Long runId,
                                                           @AuthenticationPrincipal UserPrincipal user) {
        accessService.requireManager(knowledgeBaseId, user.id());
        mapper.findRun(runId, knowledgeBaseId).orElseThrow(() ->
                new com.graduation.ragqa.common.BusinessException("RESOURCE_NOT_FOUND", "评测运行不存在", HttpStatus.NOT_FOUND));
        return ApiResponse.success(evaluationService.results(runId));
    }
}
