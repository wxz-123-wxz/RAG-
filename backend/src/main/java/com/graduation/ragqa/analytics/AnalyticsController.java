package com.graduation.ragqa.analytics;

import com.graduation.ragqa.common.ApiResponse;
import com.graduation.ragqa.knowledgebase.KnowledgeBaseAccessService;
import com.graduation.ragqa.security.UserPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1/knowledge-bases/{knowledgeBaseId}/analytics")
public class AnalyticsController {
    private final AnalyticsMapper mapper;
    private final KnowledgeBaseAccessService accessService;

    public AnalyticsController(AnalyticsMapper mapper, KnowledgeBaseAccessService accessService) {
        this.mapper = mapper;
        this.accessService = accessService;
    }

    @GetMapping
    public ApiResponse<AnalyticsSnapshot> summary(@PathVariable Long knowledgeBaseId,
                                                   @RequestParam(defaultValue = "30") int days,
                                                   @AuthenticationPrincipal UserPrincipal user) {
        accessService.requireManager(knowledgeBaseId, user.id());
        int boundedDays = Math.max(1, Math.min(days, 365));
        LocalDateTime since = LocalDateTime.now().minusDays(boundedDays);
        AnalyticsMapper.MetricRow metrics = mapper.summary(knowledgeBaseId, since);
        return ApiResponse.success(new AnalyticsSnapshot(boundedDays, metrics.getAnswerCount(), metrics.getRefusalCount(),
                Math.round(metrics.getMeanLatencyMs()), mapper.popularQuestions(knowledgeBaseId, since),
                mapper.refusedQuestions(knowledgeBaseId, since),
                mapper.citedDocuments(knowledgeBaseId, since)));
    }

    public record AnalyticsSnapshot(int days, long answerCount, long refusalCount, long meanLatencyMs,
                                    List<AnalyticsMapper.CountRow> popularQuestions,
                                    List<AnalyticsMapper.CountRow> refusedQuestions,
                                    List<AnalyticsMapper.DocumentCountRow> citedDocuments) { }
}
