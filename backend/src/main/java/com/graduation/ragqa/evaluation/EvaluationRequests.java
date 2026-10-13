package com.graduation.ragqa.evaluation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class EvaluationRequests {
    private EvaluationRequests() { }

    public record QuestionRequest(@NotBlank @Size(max = 32) String questionCode,
                                  @NotBlank @Size(max = 4000) String question,
                                  String referenceAnswer, String referenceDocument,
                                  String referenceSection, String referencePages,
                                  String evidenceText, @NotBlank String questionType,
                                  Boolean shouldRefuse) { }

    public record RunRequest(@NotBlank @Size(max = 128) String name,
                             @NotBlank String retrievalMethod,
                             @NotBlank String configSnapshotJson) { }
}
