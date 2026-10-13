package com.graduation.ragqa.document;

import com.graduation.ragqa.config.RagProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.List;

@Component
public class RagIndexClient {

    private final RestClient restClient;
    private final RagProperties properties;

    public RagIndexClient(RestClient ragRestClient, RagProperties properties) {
        this.restClient = ragRestClient;
        this.properties = properties;
    }

    public void indexDocument(Long knowledgeBaseId, Long versionId, String filename, String[] chunks) {
        try {
            restClient.post()
                    .uri("/internal/v1/knowledge-bases/{knowledgeBaseId}/documents/index", knowledgeBaseId)
                    .headers(this::setServiceToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new IndexRequest(versionId.toString(), filename,
                            List.of(chunks), 600, 100, "software-engineering"))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException exception) {
            throw new RagUnavailableException("RAG indexing service is unavailable", exception);
        }
    }

    private void setServiceToken(HttpHeaders headers) {
        if (properties.serviceToken() != null && !properties.serviceToken().isBlank()) {
            headers.setBearerAuth(properties.serviceToken());
        }
    }

    public record IndexRequest(
            @com.fasterxml.jackson.annotation.JsonProperty("document_version_id") String documentVersionId,
            @com.fasterxml.jackson.annotation.JsonProperty("document_name") String documentName, List<String> chunks,
            @com.fasterxml.jackson.annotation.JsonProperty("chunk_size") int chunkSize,
            @com.fasterxml.jackson.annotation.JsonProperty("chunk_overlap") int chunkOverlap, String language) {
    }

    public static class RagUnavailableException extends RuntimeException {
        public RagUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
