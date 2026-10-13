package com.graduation.ragqa.knowledgebase;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Optional;

@Mapper
public interface KnowledgeBaseMapper {

    @Select("""
            SELECT kb.id, kb.code, kb.name, kb.course_name, kb.description,
                   kb.visibility, kb.status, kb.created_at,
                   COUNT(DISTINCT CASE WHEN d.deleted_at IS NULL THEN d.id END) AS document_count,
                   MAX(CASE WHEN kb.created_by = #{userId} OR m.user_id = #{userId} OR #{isAdmin} = TRUE THEN 1 ELSE 0 END) AS manager
            FROM knowledge_bases kb
            LEFT JOIN documents d ON d.knowledge_base_id = kb.id
            LEFT JOIN knowledge_base_members m ON m.knowledge_base_id = kb.id
            WHERE kb.deleted_at IS NULL
              AND (kb.status = 'PUBLISHED' AND kb.visibility = 'PUBLIC'
                   OR kb.created_by = #{userId} OR m.user_id = #{userId} OR #{isAdmin} = TRUE)
            GROUP BY kb.id, kb.code, kb.name, kb.course_name, kb.description,
                     kb.visibility, kb.status, kb.created_at
            ORDER BY kb.updated_at DESC
            """)
    List<KnowledgeBaseRow> findVisible(@Param("userId") Long userId, @Param("isAdmin") boolean isAdmin);

    @Select("""
            SELECT kb.id, kb.code, kb.name, kb.course_name, kb.description,
                   kb.visibility, kb.status, kb.created_at,
                   COUNT(DISTINCT CASE WHEN d.deleted_at IS NULL THEN d.id END) AS document_count,
                   MAX(CASE WHEN kb.created_by = #{userId} OR m.user_id = #{userId} OR #{isAdmin} = TRUE THEN 1 ELSE 0 END) AS manager
            FROM knowledge_bases kb
            LEFT JOIN documents d ON d.knowledge_base_id = kb.id
            LEFT JOIN knowledge_base_members m ON m.knowledge_base_id = kb.id
            WHERE kb.id = #{id} AND kb.deleted_at IS NULL
              AND (kb.status = 'PUBLISHED' AND kb.visibility = 'PUBLIC'
                   OR kb.created_by = #{userId} OR m.user_id = #{userId} OR #{isAdmin} = TRUE)
            GROUP BY kb.id, kb.code, kb.name, kb.course_name, kb.description,
                     kb.visibility, kb.status, kb.created_at
            """)
    Optional<KnowledgeBaseRow> findVisibleById(
            @Param("id") Long id,
            @Param("userId") Long userId,
            @Param("isAdmin") boolean isAdmin
    );

    @Select("SELECT COUNT(*) FROM knowledge_bases WHERE id=#{id} AND created_by=#{userId} AND deleted_at IS NULL")
    int isCreator(@Param("id") Long id, @Param("userId") Long userId);

    @Select("SELECT COUNT(*) FROM knowledge_base_members WHERE knowledge_base_id=#{id} AND user_id=#{userId}")
    int isMember(@Param("id") Long id, @Param("userId") Long userId);

    @Insert("""
            INSERT INTO knowledge_bases
              (id, code, name, course_name, description, visibility, status, created_by)
            VALUES
              (#{id}, #{code}, #{name}, #{courseName}, #{description}, #{visibility}, 'DRAFT', #{createdBy})
            """)
    int insert(
            @Param("id") Long id,
            @Param("code") String code,
            @Param("name") String name,
            @Param("courseName") String courseName,
            @Param("description") String description,
            @Param("visibility") String visibility,
            @Param("createdBy") Long createdBy
    );

    @Update("""
            UPDATE knowledge_bases
            SET status = #{status}, published_at = CASE WHEN #{status} = 'PUBLISHED' THEN CURRENT_TIMESTAMP(3) ELSE published_at END,
                updated_at = CURRENT_TIMESTAMP(3), version = version + 1
            WHERE id = #{id} AND deleted_at IS NULL
            """)
    int updateStatus(@Param("id") Long id, @Param("status") String status);

    @Select("SELECT COUNT(*) FROM documents WHERE knowledge_base_id = #{id} AND status = 'PUBLISHED' AND deleted_at IS NULL")
    long countPublishedDocuments(Long id);

    @Select("SELECT COUNT(*) FROM documents WHERE knowledge_base_id=#{id} AND deleted_at IS NULL")
    long countDocuments(Long id);

    @Update("UPDATE knowledge_bases SET deleted_at = CURRENT_TIMESTAMP(3), updated_at = CURRENT_TIMESTAMP(3) WHERE id = #{id}")
    int softDelete(Long id);

    @Select("SELECT status FROM knowledge_bases WHERE id=#{id} AND deleted_at IS NULL")
    Optional<String> findStatus(Long id);
}
