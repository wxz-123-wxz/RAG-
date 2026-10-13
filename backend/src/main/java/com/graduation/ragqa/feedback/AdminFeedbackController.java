package com.graduation.ragqa.feedback;

import com.graduation.ragqa.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/feedback")
public class AdminFeedbackController {
    private final AdminFeedbackMapper mapper;

    public AdminFeedbackController(AdminFeedbackMapper mapper) {
        this.mapper = mapper;
    }

    @GetMapping
    public ApiResponse<List<AdminFeedbackRow>> list(
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(required = false) Boolean helpful,
            @RequestParam(defaultValue = "200") int limit) {
        int boundedDays = Math.max(1, Math.min(days, 365));
        int boundedLimit = Math.max(1, Math.min(limit, 500));
        return ApiResponse.success(mapper.list(LocalDateTime.now().minusDays(boundedDays), helpful, boundedLimit));
    }
}
