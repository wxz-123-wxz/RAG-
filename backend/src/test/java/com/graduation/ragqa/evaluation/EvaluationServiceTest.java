package com.graduation.ragqa.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.graduation.ragqa.conversation.RagAnswerClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class EvaluationServiceTest {
    @Test
    void executesQuestionsAndPersistsMetrics() {
        EvaluationMapper mapper = mock(EvaluationMapper.class);
        RagAnswerClient rag = mock(RagAnswerClient.class);
        EvaluationQuestionRow question = new EvaluationQuestionRow();
        question.setId(21L);
        question.setQuestion("什么是等价类划分？");
        question.setReferenceDocument("testing.md");
        question.setEvidenceText("等价类划分");
        question.setShouldRefuse(false);
        EvaluationRunRow run = new EvaluationRunRow();
        run.setId(10L);
        run.setStatus("COMPLETED");
        when(mapper.findQuestionsForRun(7L)).thenReturn(List.of(question));
        when(mapper.markStarted(10L, 7L)).thenReturn(1);
        when(mapper.publishedVersionIds(7L)).thenReturn(List.of("301"));
        when(mapper.findRun(10L, 7L)).thenReturn(Optional.of(run));
        when(rag.answer(any())).thenReturn(new RagAnswerClient.RagAnswerResult(
                "ANSWERED", "答案 [1]", List.of(new RagAnswerClient.Citation(
                1, "hash", "301", "testing.md", 0, "等价类划分")),
                "什么是等价类划分？", 1, 12, "SUFFICIENT_EVIDENCE", "test"));

        EvaluationService service = new EvaluationService(mapper, rag, new ObjectMapper());
        EvaluationRunRow result = service.createAndExecute(10L, "run", 7L, "HYBRID", "{}", 1L);

        assertEquals("COMPLETED", result.getStatus());
        verify(mapper).upsertResult(any(), eq(10L), eq(21L), eq("COMPLETED"), anyString(), anyString(), anyString(),
                eq(1.0), eq(1.0), eq(true), eq(1), eq(12), eq(null));
        verify(mapper).finishRun(eq(10L), eq("COMPLETED"), eq(1), anyString());
    }

    @Test
    void refusesEmptyQuestionSet() {
        EvaluationMapper mapper = mock(EvaluationMapper.class);
        when(mapper.findQuestionsForRun(7L)).thenReturn(List.of());
        EvaluationService service = new EvaluationService(mapper, mock(RagAnswerClient.class), new ObjectMapper());

        assertThrows(RuntimeException.class,
                () -> service.createAndExecute(10L, "run", 7L, "HYBRID", "{}", 1L));
        verify(mapper, never()).insertRun(any(), anyString(), any(), anyString(), anyString(), anyInt(), any());
    }
}
