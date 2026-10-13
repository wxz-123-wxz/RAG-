package com.graduation.ragqa.conversation;

import jakarta.validation.constraints.Size;

public record FeedbackRequest(Boolean helpful, Boolean citationError, Boolean incomplete,
                              Boolean suspectedError, @Size(max = 1000) String comment) { }
