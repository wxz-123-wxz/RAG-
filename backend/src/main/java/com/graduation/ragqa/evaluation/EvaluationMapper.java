package com.graduation.ragqa.evaluation;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Optional;

@Mapper
public interface EvaluationMapper {

    @Select("SELECT id,knowledge_base_id,question_code,question,reference_answer,reference_document,reference_section,reference_pages,evidence_text,question_type,should_refuse,review_status FROM evaluation_questions WHERE knowledge_base_id=#{kbId} AND deleted_at IS NULL ORDER BY question_code")
    List<EvaluationQuestionRow> findQuestions(Long kbId);

    @Insert("""
            INSERT INTO evaluation_questions
              (id,knowledge_base_id,question_code,question,reference_answer,reference_document,
               reference_section,reference_pages,evidence_text,question_type,should_refuse,review_status,created_by)
            VALUES (#{id},#{kbId},#{code},#{question},#{answer},#{document},#{section},#{pages},#{evidence},#{type},#{refuse},'DRAFT',#{createdBy})
            """)
    int insertQuestion(@Param("id") Long id, @Param("kbId") Long kbId, @Param("code") String code,
                       @Param("question") String question, @Param("answer") String answer,
                       @Param("document") String document, @Param("section") String section,
                       @Param("pages") String pages, @Param("evidence") String evidence,
                       @Param("type") String type, @Param("refuse") boolean refuse,
                       @Param("createdBy") Long createdBy);

    @Select("SELECT COUNT(*) FROM evaluation_questions WHERE knowledge_base_id=#{kbId} AND deleted_at IS NULL")
    int countQuestions(Long kbId);

    @Insert("INSERT INTO evaluation_runs (id,name,knowledge_base_id,status,retrieval_method,config_snapshot_json,question_count,created_by) VALUES (#{id},#{name},#{kbId},'QUEUED',#{method},#{config},#{count},#{createdBy})")
    int insertRun(@Param("id") Long id, @Param("name") String name, @Param("kbId") Long kbId,
                  @Param("method") String method, @Param("config") String config,
                  @Param("count") int count, @Param("createdBy") Long createdBy);

    @Select("SELECT id,name,knowledge_base_id,retrieval_method,status,config_snapshot_json,question_count,completed_count,metrics_json,started_at,finished_at,created_at FROM evaluation_runs WHERE knowledge_base_id=#{kbId} ORDER BY created_at DESC")
    List<EvaluationRunRow> findRuns(Long kbId);

    @Select("SELECT id,name,knowledge_base_id,retrieval_method,status,config_snapshot_json,question_count,completed_count,metrics_json,started_at,finished_at,created_at FROM evaluation_runs WHERE id=#{id} AND knowledge_base_id=#{kbId}")
    Optional<EvaluationRunRow> findRun(@Param("id") Long id, @Param("kbId") Long kbId);

    @Select("SELECT id,knowledge_base_id,question_code,question,reference_answer,reference_document,reference_section,reference_pages,evidence_text,question_type,should_refuse,review_status FROM evaluation_questions WHERE knowledge_base_id=#{kbId} AND deleted_at IS NULL ORDER BY question_code")
    List<EvaluationQuestionRow> findQuestionsForRun(Long kbId);

    @Select("SELECT CAST(dv.id AS CHAR) FROM document_versions dv JOIN documents d ON d.id=dv.document_id WHERE d.knowledge_base_id=#{kbId} AND dv.id=d.current_version_id AND d.status='PUBLISHED' AND dv.parse_status='READY' AND d.deleted_at IS NULL ORDER BY dv.id")
    List<String> publishedVersionIds(Long kbId);

    @Update("UPDATE evaluation_runs SET status='RUNNING', started_at=COALESCE(started_at,CURRENT_TIMESTAMP(3)) WHERE id=#{runId} AND knowledge_base_id=#{kbId} AND status IN ('QUEUED','FAILED')")
    int markStarted(@Param("runId") Long runId, @Param("kbId") Long kbId);

    @Update("UPDATE evaluation_runs SET completed_count=#{completedCount} WHERE id=#{runId}")
    int updateProgress(@Param("runId") Long runId, @Param("completedCount") int completedCount);

    @Insert("""
            INSERT INTO evaluation_results
              (id,evaluation_run_id,evaluation_question_id,status,generated_answer,retrieved_chunk_ids_json,
               citation_snapshot_json,recall_at_5,reciprocal_rank,refusal_correct,citation_accuracy,latency_ms,error_code)
            VALUES (#{id},#{runId},#{questionId},#{status},#{answer},CAST(#{chunks} AS JSON),CAST(#{citations} AS JSON),#{recall},#{mrr},#{refusal},#{citationAccuracy},#{latency},#{errorCode})
            ON DUPLICATE KEY UPDATE status=VALUES(status),generated_answer=VALUES(generated_answer),
              retrieved_chunk_ids_json=VALUES(retrieved_chunk_ids_json),citation_snapshot_json=VALUES(citation_snapshot_json),
              recall_at_5=VALUES(recall_at_5),reciprocal_rank=VALUES(reciprocal_rank),refusal_correct=VALUES(refusal_correct),
              citation_accuracy=VALUES(citation_accuracy),latency_ms=VALUES(latency_ms),error_code=VALUES(error_code),updated_at=CURRENT_TIMESTAMP(3)
            """)
    int upsertResult(@Param("id") Long id, @Param("runId") Long runId, @Param("questionId") Long questionId,
                     @Param("status") String status, @Param("answer") String answer, @Param("chunks") String chunks,
                     @Param("citations") String citations, @Param("recall") Double recall, @Param("mrr") Double mrr,
                     @Param("refusal") Boolean refusal, @Param("citationAccuracy") Integer citationAccuracy,
                     @Param("latency") Integer latency, @Param("errorCode") String errorCode);

    @Update("UPDATE evaluation_runs SET status=#{status},completed_count=#{completedCount},metrics_json=#{metrics},finished_at=CURRENT_TIMESTAMP(3) WHERE id=#{runId}")
    int finishRun(@Param("runId") Long runId, @Param("status") String status, @Param("completedCount") int completedCount,
                  @Param("metrics") String metrics);

    @Select("SELECT id,evaluation_run_id,evaluation_question_id,status,generated_answer,retrieved_chunk_ids_json,citation_snapshot_json,recall_at_5,reciprocal_rank,refusal_correct,citation_accuracy,latency_ms,error_code,created_at,updated_at FROM evaluation_results WHERE evaluation_run_id=#{runId} ORDER BY evaluation_question_id")
    List<EvaluationResultRow> findResults(Long runId);
}
