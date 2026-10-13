package com.graduation.ragqa.conversation;

public record RegenerateRequest(
        @jakarta.validation.constraints.Pattern(regexp = "default|extractive|generated") String answerMode) {
    public String normalizedAnswerMode() { return answerMode == null ? "default" : answerMode; }
}
