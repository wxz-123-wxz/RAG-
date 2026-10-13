package com.graduation.ragqa.document;

import com.graduation.ragqa.common.ApiResponse;
import com.graduation.ragqa.security.UserPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/ingestion-tasks")
public class IngestionTaskController {

    private final IngestionService ingestionService;

    public IngestionTaskController(IngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @GetMapping("/{taskId}")
    public ApiResponse<IngestionTaskRow> get(
            @PathVariable Long taskId,
            @AuthenticationPrincipal UserPrincipal user
    ) {
        return ApiResponse.success(ingestionService.getTask(taskId, user));
    }

    @PostMapping("/{taskId}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<Map<String, String>> retry(
            @PathVariable Long taskId,
            @AuthenticationPrincipal UserPrincipal user
    ) {
        Long newTaskId = ingestionService.enqueueRetry(taskId, user);
        return ApiResponse.success(Map.of("taskId", newTaskId.toString(), "status", "QUEUED"));
    }
}
