package com.graduation.ragqa.document;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Optional;

@Mapper
public interface DocumentMapper {

    @Select("SELECT current_version_id FROM documents WHERE id=#{documentId} AND knowledge_base_id=#{kbId} AND deleted_at IS NULL")
    Optional<Long> findCurrentVersion(@Param("kbId") Long kbId, @Param("documentId") Long documentId);

    @Select("SELECT parse_status FROM document_versions WHERE id=#{versionId}")
    Optional<String> findVersionStatus(Long versionId);

    @Select("SELECT CAST(t.id AS CHAR) FROM ingestion_tasks t JOIN documents d ON d.current_version_id=t.document_version_id WHERE d.id=#{documentId} AND d.knowledge_base_id=#{kbId} AND d.deleted_at IS NULL ORDER BY t.created_at DESC,t.id DESC LIMIT 1")
    Optional<String> findLatestTaskId(@Param("kbId") Long kbId, @Param("documentId") Long documentId);

    @Select("""
            SELECT d.id
            FROM documents d
            JOIN document_versions v ON v.document_id = d.id
            WHERE d.knowledge_base_id = #{kbId} AND d.deleted_at IS NULL AND v.sha256 = #{hash}
            LIMIT 1
            """)
    Optional<Long> findDuplicate(@Param("kbId") Long knowledgeBaseId, @Param("hash") String sha256);

    @Insert("INSERT INTO documents (id, knowledge_base_id, title, category, status, created_by) VALUES (#{id}, #{kbId}, #{title}, #{category}, 'DRAFT', #{createdBy})")
    int insertDocument(@Param("id") Long id, @Param("kbId") Long kbId, @Param("title") String title,
                       @Param("category") String category, @Param("createdBy") Long createdBy);

    @Insert("""
            INSERT INTO document_versions
              (id, document_id, version_no, original_filename, storage_key, media_type,
               extension, file_size, sha256, parse_status, created_by)
            VALUES (#{id}, #{documentId}, 1, #{filename}, #{storageKey}, #{mediaType},
                    #{extension}, #{fileSize}, #{sha256}, 'UPLOADED', #{createdBy})
            """)
    int insertVersion(@Param("id") Long id, @Param("documentId") Long documentId,
                      @Param("filename") String filename, @Param("storageKey") String storageKey,
                      @Param("mediaType") String mediaType, @Param("extension") String extension,
                      @Param("fileSize") long fileSize, @Param("sha256") String sha256,
                      @Param("createdBy") Long createdBy);

    @Update("UPDATE documents SET current_version_id=#{versionId}, updated_at=CURRENT_TIMESTAMP(3) WHERE id=#{documentId}")
    int setCurrentVersion(@Param("documentId") Long documentId, @Param("versionId") Long versionId);

    @Update("UPDATE document_versions SET parse_status=#{status} WHERE id=#{versionId}")
    int updateVersionStatus(@Param("versionId") Long versionId, @Param("status") String status);

    @Update("UPDATE documents SET status=#{status}, updated_at=CURRENT_TIMESTAMP(3) WHERE current_version_id=#{versionId}")
    int updateDocumentStatusByVersion(@Param("versionId") Long versionId, @Param("status") String status);

    @Select("SELECT status FROM documents WHERE current_version_id=#{versionId} AND deleted_at IS NULL")
    Optional<String> findDocumentStatusByVersion(@Param("versionId") Long versionId);

    @Select("SELECT storage_key FROM document_versions WHERE id=#{versionId}")
    Optional<String> findStorageKey(Long versionId);

    @Select("SELECT original_filename FROM document_versions WHERE id=#{versionId}")
    Optional<String> findFilename(Long versionId);

    @Select("SELECT COUNT(*) FROM knowledge_base_members WHERE knowledge_base_id=#{knowledgeBaseId} AND user_id=#{userId}")
    int isMember(@Param("knowledgeBaseId") Long knowledgeBaseId, @Param("userId") Long userId);

    @Select("SELECT COUNT(*) FROM documents WHERE knowledge_base_id=#{knowledgeBaseId} AND status='PUBLISHED' AND deleted_at IS NULL")
    int countPublished(Long knowledgeBaseId);

    @Select("SELECT id FROM document_chunks WHERE id=#{id} AND document_version_id=#{versionId}")
    Optional<Long> findChunkId(@Param("id") Long id, @Param("versionId") Long versionId);

    @Select("SELECT heading_path FROM document_chunks WHERE id=#{id}")
    Optional<String> findHeading(Long id);

    @Select("SELECT page_start FROM document_chunks WHERE id=#{id}")
    Optional<Integer> findPageStart(Long id);

    @Select("SELECT page_end FROM document_chunks WHERE id=#{id}")
    Optional<Integer> findPageEnd(Long id);

    @Select("SELECT content FROM document_chunks WHERE id=#{id}")
    Optional<String> findChunkContent(Long id);

    @Select("SELECT d.title FROM documents d JOIN document_versions v ON v.document_id=d.id WHERE v.id=#{versionId}")
    Optional<String> findDocumentTitleByVersion(Long versionId);

    @Select("SELECT id FROM document_chunks WHERE document_version_id=#{versionId} AND chunk_no=#{chunkNo}")
    Optional<Long> findChunkIdByNumber(@Param("versionId") Long versionId, @Param("chunkNo") Integer chunkNo);

    @Select("SELECT COUNT(*) FROM documents WHERE knowledge_base_id=#{knowledgeBaseId} AND deleted_at IS NULL")
    int countDocuments(Long knowledgeBaseId);

    @Select("""
            SELECT CAST(d.id AS CHAR) AS id, d.title, d.category, d.status,
                   v.original_filename AS filename, v.parse_status AS parseStatus,
                   v.file_size AS fileSize,
                   DATE_FORMAT(d.created_at, '%Y-%m-%dT%H:%i:%s') AS createdAt
            FROM documents d
            LEFT JOIN document_versions v ON v.id = d.current_version_id
            WHERE d.knowledge_base_id=#{knowledgeBaseId} AND d.deleted_at IS NULL
              AND (#{includeDrafts}=TRUE OR d.status='PUBLISHED')
            ORDER BY d.updated_at DESC
            """)
    List<DocumentController.DocumentListRow> findByKnowledgeBase(
            @Param("knowledgeBaseId") Long knowledgeBaseId,
            @Param("includeDrafts") boolean includeDrafts
    );
}
