package com.graduation.ragqa.analytics;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AnalyticsMapper {
    @Select("""
            SELECT COUNT(*) AS answerCount,
                   COALESCE(SUM(CASE WHEN m.answer_status='REFUSED' THEN 1 ELSE 0 END),0) AS refusalCount,
                   COALESCE(AVG(m.latency_ms),0) AS meanLatencyMs
            FROM messages m JOIN conversations c ON c.id=m.conversation_id
            WHERE c.deleted_at IS NULL AND m.role='ASSISTANT'
              AND m.created_at >= #{since} AND m.answer_origin <> 'GENERAL'
            """)
    MetricRow summaryAll(@Param("since") LocalDateTime since);

    @Select("""
            SELECT COUNT(*) AS answerCount,
                   COALESCE(SUM(CASE WHEN m.answer_status='REFUSED' THEN 1 ELSE 0 END),0) AS refusalCount,
                   COALESCE(AVG(m.latency_ms),0) AS meanLatencyMs
            FROM messages m JOIN conversations c ON c.id=m.conversation_id
            WHERE c.knowledge_base_id=#{kbId} AND c.deleted_at IS NULL AND m.role='ASSISTANT'
              AND m.created_at >= #{since} AND m.answer_origin <> 'GENERAL'
            """)
    MetricRow summary(@Param("kbId") Long kbId, @Param("since") LocalDateTime since);

    @Select("""
            SELECT q.content AS question,COUNT(*) AS count
            FROM messages q JOIN messages m ON m.parent_message_id=q.id AND m.role='ASSISTANT'
              JOIN conversations c ON c.id=q.conversation_id
            WHERE c.knowledge_base_id=#{kbId} AND c.deleted_at IS NULL AND q.role='USER'
              AND q.created_at >= #{since} AND m.answer_origin <> 'GENERAL'
            GROUP BY q.content ORDER BY count DESC
            """)
    List<CountRow> popularQuestions(@Param("kbId") Long kbId, @Param("since") LocalDateTime since);

    @Select("""
            SELECT q.content AS question,COUNT(*) AS count
            FROM messages q JOIN messages m ON m.parent_message_id=q.id AND m.role='ASSISTANT'
              JOIN conversations c ON c.id=q.conversation_id
            WHERE c.deleted_at IS NULL AND q.role='USER'
              AND q.created_at >= #{since} AND m.answer_origin <> 'GENERAL'
            GROUP BY q.content ORDER BY count DESC
            """)
    List<CountRow> popularQuestionsAll(@Param("since") LocalDateTime since);

    @Select("""
            SELECT q.content AS question,
                   COUNT(DISTINCT a.id) AS count,
                   COUNT(DISTINCT f.id) AS feedbackCount
            FROM messages q JOIN messages a ON a.parent_message_id=q.id AND a.role='ASSISTANT'
              JOIN conversations c ON c.id=q.conversation_id
              LEFT JOIN feedback f ON f.message_id=a.id
            WHERE c.knowledge_base_id=#{kbId} AND c.deleted_at IS NULL AND q.role='USER'
              AND a.answer_status='REFUSED' AND a.created_at >= #{since}
            GROUP BY q.content ORDER BY count DESC
            """)
    List<CountRow> refusedQuestions(@Param("kbId") Long kbId, @Param("since") LocalDateTime since);

    @Select("""
            SELECT q.content AS question,
                   COUNT(DISTINCT a.id) AS count,
                   COUNT(DISTINCT f.id) AS feedbackCount
            FROM messages q JOIN messages a ON a.parent_message_id=q.id AND a.role='ASSISTANT'
              JOIN conversations c ON c.id=q.conversation_id
              LEFT JOIN feedback f ON f.message_id=a.id
            WHERE c.deleted_at IS NULL AND q.role='USER'
              AND a.answer_status='REFUSED' AND a.created_at >= #{since}
            GROUP BY q.content ORDER BY count DESC
            """)
    List<CountRow> refusedQuestionsAll(@Param("since") LocalDateTime since);

    @Select("""
            SELECT c.document_title_snapshot AS documentTitle,COUNT(*) AS count
            FROM answer_citations c JOIN messages m ON m.id=c.message_id
              JOIN conversations v ON v.id=m.conversation_id
            WHERE v.knowledge_base_id=#{kbId} AND v.deleted_at IS NULL AND m.created_at >= #{since}
            GROUP BY c.document_title_snapshot ORDER BY count DESC
            """)
    List<DocumentCountRow> citedDocuments(@Param("kbId") Long kbId, @Param("since") LocalDateTime since);

    @Select("""
            SELECT c.document_title_snapshot AS documentTitle,COUNT(*) AS count
            FROM answer_citations c JOIN messages m ON m.id=c.message_id
              JOIN conversations v ON v.id=m.conversation_id
            WHERE v.deleted_at IS NULL AND m.created_at >= #{since}
            GROUP BY c.document_title_snapshot ORDER BY count DESC
            """)
    List<DocumentCountRow> citedDocumentsAll(@Param("since") LocalDateTime since);

    class MetricRow {
        private long answerCount;
        private long refusalCount;
        private double meanLatencyMs;
        public long getAnswerCount() { return answerCount; }
        public void setAnswerCount(long value) { answerCount = value; }
        public long getRefusalCount() { return refusalCount; }
        public void setRefusalCount(long value) { refusalCount = value; }
        public double getMeanLatencyMs() { return meanLatencyMs; }
        public void setMeanLatencyMs(double value) { meanLatencyMs = value; }
    }

    class CountRow {
        private String question;
        private long count;
        private long feedbackCount;
        public String getQuestion() { return question; }
        public void setQuestion(String value) { question = value; }
        public long getCount() { return count; }
        public void setCount(long value) { count = value; }
        public long getFeedbackCount() { return feedbackCount; }
        public void setFeedbackCount(long value) { feedbackCount = value; }
    }

    class DocumentCountRow {
        private String documentTitle;
        private long count;
        public String getDocumentTitle() { return documentTitle; }
        public void setDocumentTitle(String value) { documentTitle = value; }
        public long getCount() { return count; }
        public void setCount(long value) { count = value; }
    }
}
