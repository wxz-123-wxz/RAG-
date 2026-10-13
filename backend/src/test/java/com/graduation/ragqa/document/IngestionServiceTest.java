package com.graduation.ragqa.document;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IngestionServiceTest {

    @Test
    void splitNormalizesLineEndingsAndPreservesAllContent() {
        String source = "第一段\r\n第二段\r第三段\n" + "内容".repeat(140);

        String[] chunks = IngestionService.split(source, 80, 15);

        assertThat(chunks).isNotEmpty();
        assertThat(String.join("", chunks)).contains("第一段", "第二段", "第三段");
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.length()).isLessThanOrEqualTo(80));
    }

    @Test
    void splitRejectsOverlapEqualToWindow() {
        assertThatThrownBy(() -> IngestionService.split("text", 10, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
