package com.graduation.ragqa.conversation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AskRequest(@NotBlank @Size(max = 2000) String content,
                         @jakarta.validation.constraints.Pattern(regexp = "default|extractive|generated") String answerMode) {
    public AskRequest(String content) { this(content, "default"); }
    public String normalizedAnswerMode() { return answerMode == null ? "default" : answerMode; }
}
