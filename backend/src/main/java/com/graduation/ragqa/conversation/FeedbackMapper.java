package com.graduation.ragqa.conversation;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Optional;

@Mapper
public interface FeedbackMapper {

    @Insert("INSERT INTO feedback (id,message_id,user_id,helpful,citation_error,incomplete,suspected_error,comment) VALUES (#{id},#{messageId},#{userId},#{helpful},#{citationError},#{incomplete},#{suspectedError},#{comment}) ON DUPLICATE KEY UPDATE helpful=VALUES(helpful),citation_error=VALUES(citation_error),incomplete=VALUES(incomplete),suspected_error=VALUES(suspected_error),comment=VALUES(comment),updated_at=CURRENT_TIMESTAMP(3)")
    int upsert(@Param("id") Long id, @Param("messageId") Long messageId, @Param("userId") Long userId,
               @Param("helpful") boolean helpful, @Param("citationError") boolean citationError,
               @Param("incomplete") boolean incomplete, @Param("suspectedError") boolean suspectedError,
               @Param("comment") String comment);

    @Select("SELECT COUNT(*) FROM feedback WHERE message_id=#{messageId} AND user_id=#{userId}")
    int countOwnerFeedback(@Param("messageId") Long messageId, @Param("userId") Long userId);

    @org.apache.ibatis.annotations.Delete("DELETE FROM feedback WHERE message_id=#{messageId} AND user_id=#{userId}")
    int delete(@Param("messageId") Long messageId, @Param("userId") Long userId);

    @Select("SELECT helpful,citation_error AS citationError,incomplete,suspected_error AS suspectedError,comment FROM feedback WHERE message_id=#{messageId} AND user_id=#{userId}")
    Optional<FeedbackRow> find(@Param("messageId") Long messageId, @Param("userId") Long userId);

    record FeedbackRow(Boolean helpful, Boolean citationError, Boolean incomplete,
                       Boolean suspectedError, String comment) { }
}
