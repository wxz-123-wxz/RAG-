package com.graduation.ragqa.conversation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ConversationRequest(
        @NotBlank @Size(max = 64) String knowledgeBaseId,
        @Size(max = 128) String title
) { }
