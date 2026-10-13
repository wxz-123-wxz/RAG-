package com.graduation.ragqa.common;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class CapabilitiesController {
    private final RestClient rag;
    public CapabilitiesController(RestClient ragRestClient) { this.rag = ragRestClient; }

    @GetMapping("/api/v1/system/capabilities")
    public ApiResponse<Map<String, Object>> capabilities() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("answerMode", "generated");
        result.put("embeddingProvider", "unavailable");
        result.put("reranker", "unavailable");
        result.put("chatConfigured", false);
        result.put("chatModel", "");
        result.put("ragAvailable", false);
        try {
            var status = rag.get().uri("/health/ready").retrieve().body(Map.class);
            if (status != null) {
                for (String key : new String[]{"answerMode", "embeddingProvider", "reranker", "chatConfigured", "chatModel"}) {
                    if (status.containsKey(key)) result.put(key, status.get(key));
                }
                result.put("ragAvailable", true);
            }
        } catch (RestClientException ignored) {
            // The workspace remains usable while the independently deployed RAG service is offline.
        }
        return ApiResponse.success(result);
    }
}
