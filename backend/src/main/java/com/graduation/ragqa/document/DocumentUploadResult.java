package com.graduation.ragqa.document;

public record DocumentUploadResult(
        String documentId,
        String versionId,
        String taskId,
        String status
) {
}
