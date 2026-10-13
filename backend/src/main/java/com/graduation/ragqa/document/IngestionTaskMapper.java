package com.graduation.ragqa.document;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Optional;

@Mapper
public interface IngestionTaskMapper {

    @Insert("""
            INSERT INTO ingestion_tasks (id, document_version_id, task_type, status, current_stage, attempt_no, trace_id)
            VALUES (#{id}, #{versionId}, 'INGEST', 'QUEUED', 'QUEUED', #{attemptNo}, #{traceId})
            """)
    int insert(@Param("id") Long id, @Param("versionId") Long versionId,
               @Param("attemptNo") int attemptNo, @Param("traceId") String traceId);

    @Insert("""
            INSERT INTO ingestion_task_events (id, ingestion_task_id, stage, status, progress_percent, detail)
            VALUES (#{id}, #{taskId}, #{stage}, #{status}, #{progress}, #{detail})
            """)
    int insertEvent(@Param("id") Long id, @Param("taskId") Long taskId,
                    @Param("stage") String stage, @Param("status") String status,
                    @Param("progress") int progress, @Param("detail") String detail);

    @Select("""
            SELECT t.id, t.document_version_id, t.status, t.current_stage, t.progress_percent,
                   t.error_code, t.error_message, t.started_at, t.finished_at, t.created_at
            FROM ingestion_tasks t
            JOIN document_versions v ON v.id = t.document_version_id
            JOIN documents d ON d.id = v.document_id
            WHERE t.id = #{taskId} AND d.knowledge_base_id = #{knowledgeBaseId}
            """)
    Optional<IngestionTaskRow> findForKnowledgeBase(
            @Param("taskId") Long taskId,
            @Param("knowledgeBaseId") Long knowledgeBaseId
    );

    @Select("""
            SELECT t.id, t.document_version_id, t.status, t.current_stage, t.progress_percent,
                   t.error_code, t.error_message, t.started_at, t.finished_at, t.created_at
            FROM ingestion_tasks t WHERE t.id = #{taskId}
            """)
    Optional<IngestionTaskRow> findById(Long taskId);

    @Select("""
            SELECT d.knowledge_base_id FROM ingestion_tasks t
            JOIN document_versions v ON v.id=t.document_version_id
            JOIN documents d ON d.id=v.document_id WHERE t.id=#{taskId}
            """)
    Optional<Long> findKnowledgeBaseId(Long taskId);

    @Select("SELECT COUNT(*) FROM ingestion_tasks WHERE status IN ('PARSING','CHUNKING','EMBEDDING','INDEXING')")
    int countProcessing();

    @Select("SELECT id FROM ingestion_tasks WHERE status IN ('PARSING','CHUNKING','EMBEDDING','INDEXING')")
    List<Long> findInterruptedIds();

    @Select("SELECT COUNT(*) FROM ingestion_tasks WHERE document_version_id = #{versionId} AND status IN ('QUEUED','PARSING','CHUNKING','EMBEDDING','INDEXING')")
    int countActive(Long versionId);

    @Update("UPDATE ingestion_tasks SET status='PARSING', current_stage='PARSING', progress_percent=1, started_at=CURRENT_TIMESTAMP(3), updated_at=CURRENT_TIMESTAMP(3) WHERE id=#{id} AND status='QUEUED'")
    int tryStart(Long id);

    @Select("SELECT COALESCE(MAX(attempt_no), 0) FROM ingestion_tasks WHERE document_version_id = #{versionId}")
    int maxAttempt(Long versionId);

    @Select("SELECT id FROM document_versions WHERE id = #{versionId}")
    Optional<Long> versionExists(Long versionId);

    @Update("UPDATE ingestion_tasks SET status=#{status}, current_stage=#{stage}, progress_percent=#{progress}, started_at=COALESCE(started_at,CURRENT_TIMESTAMP(3)), updated_at=CURRENT_TIMESTAMP(3) WHERE id=#{id}")
    int updateProgress(@Param("id") Long id, @Param("status") String status,
                       @Param("stage") String stage, @Param("progress") int progress);

    @Update("UPDATE ingestion_tasks SET status='FAILED', current_stage=#{stage}, error_code=#{errorCode}, error_message=#{errorMessage}, finished_at=CURRENT_TIMESTAMP(3), updated_at=CURRENT_TIMESTAMP(3) WHERE id=#{id}")
    int markFailed(@Param("id") Long id, @Param("stage") String stage,
                   @Param("errorCode") String errorCode, @Param("errorMessage") String errorMessage);

    @Update("UPDATE ingestion_tasks SET status='READY', current_stage='READY', progress_percent=100, finished_at=CURRENT_TIMESTAMP(3), updated_at=CURRENT_TIMESTAMP(3) WHERE id=#{id}")
    int complete(Long id);

    @Select("SELECT id FROM ingestion_tasks WHERE status = 'QUEUED' ORDER BY created_at")
    List<Long> findQueuedIds();

    @Select("SELECT document_version_id FROM ingestion_tasks WHERE id = #{id}")
    Optional<Long> findVersionId(Long id);
}
