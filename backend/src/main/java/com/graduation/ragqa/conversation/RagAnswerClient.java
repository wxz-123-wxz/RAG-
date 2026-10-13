package com.graduation.ragqa.conversation;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.graduation.ragqa.config.RagProperties;
import com.graduation.ragqa.document.RagIndexClient.RagUnavailableException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;

@Component
public class RagAnswerClient {

    private final RestClient restClient;
    private final RagProperties properties;

    public RagAnswerClient(RestClient ragRestClient, RagProperties properties) {
        this.restClient = ragRestClient;
        this.properties = properties;
    }

    public RagAnswerResult answer(RagAnswerRequest request) {
        try {
            return restClient.post()
                    .uri("/internal/v1/answers/generate")
                    .headers(this::setServiceToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(RagAnswerResult.class);
        } catch (org.springframework.web.client.RestClientResponseException exception) {
            String body = exception.getResponseBodyAsString();
            for (String code : List.of("CHAT_MODEL_NOT_CONFIGURED", "CHAT_MODEL_TIMEOUT", "CHAT_MODEL_AUTH_FAILED",
                    "CHAT_MODEL_RATE_LIMITED", "CHAT_MODEL_INVALID_CITATION", "CHAT_MODEL_INVALID_RESPONSE",
                    "CHAT_MODEL_INVALID_REQUEST", "CHAT_MODEL_BALANCE_INSUFFICIENT", "CHAT_MODEL_ERROR")) {
                if (body.contains(code)) {
                    String message = switch (code) {
                        case "CHAT_MODEL_NOT_CONFIGURED" -> "生成模型尚未配置，请由管理员检查本地配置";
                        case "CHAT_MODEL_TIMEOUT" -> "生成模型响应超时，请稍后重试";
                        case "CHAT_MODEL_AUTH_FAILED" -> "模型凭据无效，请由管理员检查本地配置";
                        case "CHAT_MODEL_RATE_LIMITED" -> "模型接口限流，请稍后重试";
                        case "CHAT_MODEL_INVALID_CITATION" -> "模型回答缺少有效引用，已拒绝保存，请重试";
                        case "CHAT_MODEL_INVALID_RESPONSE" -> "模型未按依据核验格式返回结果，已拒绝保存，请重试";
                        case "CHAT_MODEL_INVALID_REQUEST" -> "模型接口参数不兼容，请由管理员核对模型名称与接口配置";
                        case "CHAT_MODEL_BALANCE_INSUFFICIENT" -> "模型服务账户余额不足，请由管理员检查账户";
                        default -> "生成模型调用失败，未生成可保存的回答";
                    };
                    throw new com.graduation.ragqa.common.BusinessException(code, message,
                            org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE);
                }
            }
            throw new com.graduation.ragqa.common.BusinessException("RAG_SERVICE_ERROR",
                    "RAG 服务调用失败，请检查服务日志", org.springframework.http.HttpStatus.BAD_GATEWAY);
        } catch (RestClientException exception) {
            throw new RagUnavailableException("RAG answer service is unavailable", exception);
        }
    }

    private void setServiceToken(HttpHeaders headers) {
        if (properties.serviceToken() != null && !properties.serviceToken().isBlank()) {
            headers.setBearerAuth(properties.serviceToken());
        }
    }

    public record HistoryEntry(String role, String content) { }

    public record RagAnswerRequest(
            String question,
            @com.fasterxml.jackson.annotation.JsonProperty("query") String query,
            @JsonProperty("knowledge_base_id") String knowledgeBaseId,
            @JsonProperty("document_version_ids") List<String> documentVersionIds,
            @JsonProperty("top_k") Integer topK,
            String method,
            @JsonProperty("rrf_k") Integer rrfK,
            @JsonProperty("vector_weight") Double vectorWeight,
            @JsonProperty("bm25_weight") Double bm25Weight,
            List<HistoryEntry> history,
            @JsonProperty("evidence_threshold") Double evidenceThreshold,
            @JsonProperty("retrieval_threshold") Double retrievalThreshold,
            @JsonProperty("answer_mode") String answerMode,
            @JsonProperty("knowledge_base_ids") List<String> knowledgeBaseIds
    ) {
        public RagAnswerRequest(String question, String query, String knowledgeBaseId,
                                List<String> documentVersionIds, Integer topK, String method,
                                Integer rrfK, Double vectorWeight, Double bm25Weight,
                                List<HistoryEntry> history, Double evidenceThreshold,
                                Double retrievalThreshold, String answerMode) {
            this(question, query, knowledgeBaseId, documentVersionIds, topK, method, rrfK,
                    vectorWeight, bm25Weight, history, evidenceThreshold, retrievalThreshold,
                    answerMode, List.of());
        }
    }

    public record Citation(
            Integer number,
            @JsonProperty("chunk_id") String chunkId,
            @JsonProperty("document_version_id") String documentVersionId,
            @JsonProperty("document_name") String documentName,
            @JsonProperty("chunk_no") Integer chunkNo,
            String quote
    ) { }

    public record RagAnswerResult(
            String status,
            String answer,
            List<Citation> citations,
            String query,
            @JsonProperty("evidence_count") Integer evidenceCount,
            @JsonProperty("latency_ms") Integer latencyMs,
            @JsonProperty("evidence_reason") String evidenceReason,
            String model,
            @JsonProperty("answer_origin") String answerOrigin,
            @JsonProperty("evidence_state") String evidenceState
    ) {
        public RagAnswerResult(String status, String answer, List<Citation> citations, String query,
                               Integer evidenceCount, Integer latencyMs, String evidenceReason, String model) {
            this(status, answer, citations, query, evidenceCount, latencyMs, evidenceReason, model,
                    "REFUSED".equals(status) ? "NONE" : "KNOWLEDGE_BASE",
                    "REFUSED".equals(status) ? "INSUFFICIENT" : "UNASSESSED");
        }
    }
}
