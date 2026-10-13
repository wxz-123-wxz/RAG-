package com.graduation.ragqa.evaluation;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.graduation.ragqa.common.BusinessException;
import com.graduation.ragqa.conversation.RagAnswerClient;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class EvaluationService {
    private final EvaluationMapper mapper;
    private final RagAnswerClient ragClient;
    private final ObjectMapper objectMapper;

    public EvaluationService(EvaluationMapper mapper, RagAnswerClient ragClient, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.ragClient = ragClient;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public EvaluationRunRow createAndExecute(Long runId, String name, Long kbId, String method,
                                             String configSnapshot, Long userId) {
        List<EvaluationQuestionRow> questions = mapper.findQuestionsForRun(kbId);
        if (questions.isEmpty()) {
            throw new BusinessException("EMPTY_EVALUATION_SET", "题集为空，无法运行评测", HttpStatus.CONFLICT);
        }
        mapper.insertRun(runId, name, kbId, method, configSnapshot, questions.size(), userId);
        if (mapper.markStarted(runId, kbId) != 1) {
            throw new BusinessException("EVALUATION_START_FAILED", "评测任务无法启动", HttpStatus.CONFLICT);
        }

        List<String> versionIds = mapper.publishedVersionIds(kbId);
        if (versionIds.isEmpty()) {
            mapper.finishRun(runId, "FAILED", 0, json(Map.of("error", "NO_PUBLISHED_DOCUMENTS")));
            return mapper.findRun(runId, kbId).orElseThrow();
        }

        RunMetrics metrics = new RunMetrics();
        int completed = 0;
        for (EvaluationQuestionRow question : questions) {
            evaluateQuestion(runId, kbId, method, versionIds, question, metrics);
            completed++;
            mapper.updateProgress(runId, completed);
        }
        String status = metrics.failed == questions.size() ? "FAILED" : "COMPLETED";
        mapper.finishRun(runId, status, completed, json(metrics.toMap()));
        return mapper.findRun(runId, kbId).orElseThrow();
    }

    @Transactional(readOnly = true)
    public List<EvaluationResultRow> results(Long runId) {
        return mapper.findResults(runId);
    }

    private void evaluateQuestion(Long runId, Long kbId, String method, List<String> versionIds,
                                  EvaluationQuestionRow question, RunMetrics metrics) {
        long started = System.nanoTime();
        try {
            RagAnswerClient.RagAnswerResult response = ragClient.answer(new RagAnswerClient.RagAnswerRequest(
                    question.getQuestion(), question.getQuestion(), kbId.toString(), versionIds,
                    5, method, 60, 1.0, 1.0, List.of(), 0.12, 0.05, "extractive"));
            if (response == null || response.answer() == null || response.status() == null) {
                throw new IllegalStateException("INVALID_RAG_RESPONSE");
            }
            List<RagAnswerClient.Citation> citations = response.citations() == null ? List.of() : response.citations();
            int latency = response.latencyMs() == null
                    ? (int) Math.min((System.nanoTime() - started) / 1_000_000, Integer.MAX_VALUE)
                    : Math.max(0, response.latencyMs());
            Score score = score(question, response.status(), citations);
            mapper.upsertResult(IdWorker.getId(), runId, question.getId(), "COMPLETED", response.answer(),
                    json(citations.stream().map(RagAnswerClient.Citation::chunkId).toList()), json(citations),
                    score.recallAt5, score.reciprocalRank, score.refusalCorrect, score.citationAccuracy,
                    latency, null);
            metrics.add(score, latency);
        } catch (RuntimeException exception) {
            int latency = (int) Math.min((System.nanoTime() - started) / 1_000_000, Integer.MAX_VALUE);
            String code = exception instanceof BusinessException business ? business.getCode()
                    : exception instanceof com.graduation.ragqa.document.RagIndexClient.RagUnavailableException
                    ? "RAG_UNAVAILABLE" : "EVALUATION_ITEM_FAILED";
            mapper.upsertResult(IdWorker.getId(), runId, question.getId(), "FAILED", null,
                    "[]", "[]", null, null, null, null, latency, code);
            metrics.failed++;
        }
    }

    private Score score(EvaluationQuestionRow question, String responseStatus,
                        List<RagAnswerClient.Citation> citations) {
        boolean refused = "REFUSED".equalsIgnoreCase(responseStatus);
        boolean shouldRefuse = Boolean.TRUE.equals(question.getShouldRefuse());
        Boolean refusalCorrect = shouldRefuse ? refused : !refused;
        String expectedDocument = normalize(question.getReferenceDocument());
        String expectedEvidence = normalize(question.getEvidenceText());
        int firstRelevantRank = 0;
        int relevantCount = 0;
        if (!expectedDocument.isEmpty() || !expectedEvidence.isEmpty()) {
            for (int i = 0; i < Math.min(5, citations.size()); i++) {
                RagAnswerClient.Citation citation = citations.get(i);
                String document = normalize(citation.documentName());
                String quote = normalize(citation.quote());
                boolean documentMatch = !expectedDocument.isEmpty() &&
                        (document.contains(expectedDocument) || expectedDocument.contains(document));
                boolean evidenceMatch = !expectedEvidence.isEmpty() &&
                        (quote.contains(expectedEvidence) || expectedEvidence.contains(quote));
                if (documentMatch || evidenceMatch) {
                    relevantCount++;
                    if (firstRelevantRank == 0) firstRelevantRank = i + 1;
                }
            }
        }
        Double recall = expectedDocument.isEmpty() && expectedEvidence.isEmpty()
                ? null : (relevantCount > 0 ? 1.0 : 0.0);
        Double mrr = recall == null ? null : (firstRelevantRank == 0 ? 0.0 : 1.0 / firstRelevantRank);
        Integer citationAccuracy = citations.isEmpty() ? 0 :
                citations.stream().allMatch(c -> c.documentVersionId() != null && c.chunkNo() != null && c.quote() != null)
                        ? 1 : 0;
        return new Score(recall, mrr, refusalCorrect, citationAccuracy);
    }

    private String normalize(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT).replaceAll("\\s+", "").trim();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize evaluation result", exception);
        }
    }

    private record Score(Double recallAt5, Double reciprocalRank, Boolean refusalCorrect,
                         Integer citationAccuracy) { }

    private static final class RunMetrics {
        private int failed;
        private int answered;
        private int refusalCorrect;
        private int scoredRecall;
        private double recallSum;
        private int scoredMrr;
        private double mrrSum;
        private int citationValid;
        private long latencySum;

        void add(Score score, int latency) {
            answered++;
            if (Boolean.TRUE.equals(score.refusalCorrect)) refusalCorrect++;
            if (score.recallAt5 != null) { scoredRecall++; recallSum += score.recallAt5; }
            if (score.reciprocalRank != null) { scoredMrr++; mrrSum += score.reciprocalRank; }
            if (Integer.valueOf(1).equals(score.citationAccuracy)) citationValid++;
            latencySum += latency;
        }

        Map<String, Object> toMap() {
            return Map.of("completedCount", answered, "failedCount", failed,
                    "refusalAccuracy", ratio(refusalCorrect, answered),
                    "recallAt5", ratio(recallSum, scoredRecall), "mrr", ratio(mrrSum, scoredMrr),
                    "citationStructureAccuracy", ratio(citationValid, answered),
                    "meanLatencyMs", answered == 0 ? 0 : latencySum / answered,
                    "metricNotes", List.of("Recall@5/MRR use reference document-name or evidence-quote lexical matching.",
                            "Citation accuracy checks citation field completeness, not semantic support.",
                            "Answer correctness and completeness require human review."));
        }

        private double ratio(double numerator, int denominator) {
            return denominator == 0 ? 0.0 : Math.round(numerator / denominator * 10000.0) / 10000.0;
        }
    }
}
