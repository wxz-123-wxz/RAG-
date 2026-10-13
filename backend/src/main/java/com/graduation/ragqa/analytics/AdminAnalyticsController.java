package com.graduation.ragqa.analytics;

import com.graduation.ragqa.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/v1/admin/analytics")
public class AdminAnalyticsController {
    private final AnalyticsMapper mapper;

    public AdminAnalyticsController(AnalyticsMapper mapper) {
        this.mapper = mapper;
    }

    @GetMapping
    public ApiResponse<AnalyticsController.AnalyticsSnapshot> summary(
            @RequestParam(defaultValue = "30") int days) {
        int boundedDays = Math.max(1, Math.min(days, 365));
        LocalDateTime since = LocalDateTime.now().minusDays(boundedDays);
        AnalyticsMapper.MetricRow metrics = mapper.summaryAll(since);
        return ApiResponse.success(new AnalyticsController.AnalyticsSnapshot(
                boundedDays,
                metrics.getAnswerCount(),
                metrics.getRefusalCount(),
                Math.round(metrics.getMeanLatencyMs()),
                mapper.popularQuestionsAll(since),
                mapper.refusedQuestionsAll(since),
                mapper.citedDocumentsAll(since)));
    }
}
