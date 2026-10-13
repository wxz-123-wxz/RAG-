package com.graduation.ragqa.conversation;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Optional;

@Mapper
public interface ConversationMessageMapper {

    @org.apache.ibatis.annotations.Update("UPDATE messages SET model_name=#{model},answer_origin=#{origin},evidence_state=#{state} WHERE id=#{id}")
    int saveAnswerMetadata(@Param("id") Long id, @Param("model") String model,
                           @Param("origin") String origin, @Param("state") String state);

    @Select("SELECT id,conversation_id,parent_message_id,role,content,answer_status,version_no,latency_ms,created_at,model_name,answer_origin,evidence_state FROM messages WHERE conversation_id=#{conversationId} ORDER BY created_at,id LIMIT 200")
    List<MessageRow> findByConversation(Long conversationId);

    @Select("SELECT id,conversation_id,parent_message_id,role,content,answer_status,version_no,latency_ms,created_at,model_name,answer_origin,evidence_state FROM messages WHERE id=#{id} AND conversation_id=#{conversationId}")
    Optional<MessageRow> findById(@Param("id") Long id, @Param("conversationId") Long conversationId);

    @Select("SELECT id,conversation_id,parent_message_id,role,content,answer_status,version_no,latency_ms,created_at FROM messages WHERE id=#{id} AND role='USER'")
    Optional<MessageRow> findQuestionById(Long id);

    @Insert("INSERT INTO messages (id,conversation_id,role,content,answer_status,version_no,latency_ms,created_at,answer_origin,evidence_state) VALUES (#{id},#{conversationId},#{role},#{content},#{status},#{versionNo},#{latencyMs},CURRENT_TIMESTAMP(3),'NONE','NOT_APPLICABLE')")
    int insert(@Param("id") Long id, @Param("conversationId") Long conversationId,
               @Param("role") String role, @Param("content") String content,
               @Param("status") String status, @Param("versionNo") Integer versionNo,
               @Param("latencyMs") Integer latencyMs);

    @Insert("INSERT INTO messages (id,conversation_id,parent_message_id,role,content,answer_status,version_no,answer_version,latency_ms) VALUES (#{id},#{conversationId},#{parentId},'ASSISTANT',#{content},#{status},#{versionNo},#{versionNo},#{latencyMs})")
    int insertAnswer(@Param("id") Long id, @Param("conversationId") Long conversationId,
                     @Param("parentId") Long parentId, @Param("content") String content,
                     @Param("status") String status, @Param("versionNo") Integer versionNo,
                     @Param("latencyMs") Integer latencyMs);

    @Select("SELECT COALESCE(MAX(version_no),0) FROM messages WHERE parent_message_id=#{parentId}")
    int maxAnswerVersion(Long parentId);

    @Select("SELECT content FROM (SELECT id,content,created_at FROM messages WHERE conversation_id=#{conversationId} AND role='USER' ORDER BY created_at DESC,id DESC LIMIT 11) recent ORDER BY created_at,id")
    List<String> recentUserMessages(Long conversationId);

    @Select("SELECT id FROM conversations WHERE id=#{conversationId} AND user_id=#{userId} AND deleted_at IS NULL")
    Optional<Long> isOwned(@Param("conversationId") Long conversationId, @Param("userId") Long userId);

    @Select("SELECT conversation_id FROM messages WHERE id=#{messageId}")
    Optional<Long> findConversationId(Long messageId);
}
