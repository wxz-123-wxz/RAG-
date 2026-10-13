package com.graduation.ragqa.conversation;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

public record GeneralAnswerRequest(
        @NotNull @AssertTrue(message = "请明确确认使用非知识库的模型通用知识回答") Boolean confirm) { }
