package com.graduation.ragqa.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.rag")
public record RagProperties(String baseUrl, String serviceToken, Duration timeout) {
    public RagProperties {
        if (timeout == null) timeout = Duration.ofSeconds(30);
    }
}

