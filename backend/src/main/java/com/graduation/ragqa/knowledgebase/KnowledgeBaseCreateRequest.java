package com.graduation.ragqa.knowledgebase;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record KnowledgeBaseCreateRequest(
        @NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9-]{2,63}") String code,
        @NotBlank @Size(max = 128) String name,
        @NotBlank @Size(max = 128) String courseName,
        @Size(max = 2000) String description,
        @Pattern(regexp = "PUBLIC|RESTRICTED") String visibility
) {
    public String normalizedVisibility() {
        return visibility == null || visibility.isBlank() ? "RESTRICTED" : visibility;
    }
}
