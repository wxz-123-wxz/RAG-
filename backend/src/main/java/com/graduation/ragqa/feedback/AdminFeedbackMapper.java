package com.graduation.ragqa.feedback;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AdminFeedbackMapper {
    @Select("""
            SELECT f.id,
                   f.message_id AS messageId,
                   c.knowledge_base_id AS knowledgeBaseId,
                   kb.name AS knowledgeBaseName,
                   f.user_id AS userId,
                   u.username,
                   u.display_name AS displayName,
                   f.helpful,
                   f.citation_error AS citationError,
                   f.incomplete,
                   f.suspected_error AS suspectedError,
                   f.comment,
                   q.content AS question,
                   a.content AS answer,
                   a.answer_status AS answerStatus,
                   a.answer_origin AS answerOrigin,
                   f.updated_at AS submittedAt
            FROM feedback f
              JOIN users u ON u.id=f.user_id
              JOIN messages a ON a.id=f.message_id AND a.role='ASSISTANT'
              LEFT JOIN messages q ON q.id=a.parent_message_id AND q.role='USER'
              JOIN conversations c ON c.id=a.conversation_id
              LEFT JOIN knowledge_bases kb ON kb.id=c.knowledge_base_id
            WHERE f.updated_at >= #{since}
              AND (#{helpful} IS NULL OR f.helpful=#{helpful})
            ORDER BY f.updated_at DESC, f.id DESC
            LIMIT #{limit}
            """)
    List<AdminFeedbackRow> list(@Param("since") LocalDateTime since,
                                @Param("helpful") Boolean helpful,
                                @Param("limit") int limit);
}
