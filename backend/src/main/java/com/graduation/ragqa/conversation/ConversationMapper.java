package com.graduation.ragqa.conversation;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Optional;

@Mapper
public interface ConversationMapper {

    @Select("SELECT id,user_id,knowledge_base_id,title,last_message_at,created_at FROM conversations WHERE user_id=#{userId} AND deleted_at IS NULL ORDER BY COALESCE(last_message_at,created_at) DESC LIMIT 100")
    List<ConversationRow> findForUser(Long userId);

    @Select("SELECT id,user_id,knowledge_base_id,title,last_message_at,created_at FROM conversations WHERE id=#{id} AND user_id=#{userId} AND deleted_at IS NULL")
    Optional<ConversationRow> findForOwner(@Param("id") Long id, @Param("userId") Long userId);

    @Insert("INSERT INTO conversations (id,user_id,knowledge_base_id,title,status) VALUES (#{id},#{userId},#{kbId},#{title},'ACTIVE')")
    int insert(@Param("id") Long id, @Param("userId") Long userId,
               @Param("kbId") Long kbId, @Param("title") String title);

    @Update("UPDATE conversations SET title=#{title},updated_at=CURRENT_TIMESTAMP(3),version=version+1 WHERE id=#{id} AND user_id=#{userId} AND deleted_at IS NULL")
    int rename(@Param("id") Long id, @Param("userId") Long userId, @Param("title") String title);

    @Update("UPDATE conversations SET deleted_at=CURRENT_TIMESTAMP(3),updated_at=CURRENT_TIMESTAMP(3) WHERE id=#{id} AND user_id=#{userId} AND deleted_at IS NULL")
    int softDelete(@Param("id") Long id, @Param("userId") Long userId);

    @Update("UPDATE conversations SET last_message_at=CURRENT_TIMESTAMP(3),updated_at=CURRENT_TIMESTAMP(3) WHERE id=#{id}")
    int touch(Long id);

    @Select("SELECT d.id FROM conversations c JOIN documents d ON d.knowledge_base_id=c.knowledge_base_id AND d.status='PUBLISHED' AND d.deleted_at IS NULL JOIN document_versions v ON v.id=d.current_version_id AND v.parse_status='READY' WHERE c.id=#{conversationId} GROUP BY d.id ORDER BY d.id")
    List<Long> publishedVersionIds(Long conversationId);

    @Select("SELECT CAST(v.id AS CHAR) FROM conversations c JOIN documents d ON d.knowledge_base_id=c.knowledge_base_id AND d.status='PUBLISHED' AND d.deleted_at IS NULL JOIN document_versions v ON v.id=d.current_version_id AND v.parse_status='READY' WHERE c.id=#{conversationId} ORDER BY d.id")
    List<String> publishedVersionIdStrings(Long conversationId);

    @Select("""
            <script>
            SELECT CAST(v.id AS CHAR) FROM documents d
            JOIN document_versions v ON v.id=d.current_version_id AND v.parse_status='READY'
            JOIN knowledge_bases kb ON kb.id=d.knowledge_base_id AND kb.status='PUBLISHED' AND kb.deleted_at IS NULL
            WHERE d.status='PUBLISHED' AND d.deleted_at IS NULL AND d.knowledge_base_id IN
            <foreach collection='kbIds' item='kbId' open='(' separator=',' close=')'>#{kbId}</foreach>
            ORDER BY d.id LIMIT 1001
            </script>
            """)
    List<String> publishedVersionsForBases(@Param("kbIds") List<Long> kbIds);
}
