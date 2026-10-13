package com.graduation.ragqa.common;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class HealthController {

    @GetMapping("/health/live")
    public ApiResponse<Map<String, String>> live() {
        return ApiResponse.success(Map.of("status", "UP"));
    }
}
