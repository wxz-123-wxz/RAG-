package com.graduation.ragqa.document;

import jakarta.validation.constraints.Size;

public record DocumentUploadRequest(
        @Size(max = 255) String title,
        @Size(max = 64) String category
) {
}
