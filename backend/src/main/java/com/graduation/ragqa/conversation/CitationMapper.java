package com.graduation.ragqa.conversation;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface CitationMapper {

    @Insert("""
            INSERT INTO answer_citations
              (id,message_id,document_chunk_id,citation_no,quote_text,document_title_snapshot,
               heading_snapshot,page_start,page_end,retrieval_score)
            VALUES (#{id},#{messageId},#{chunkId},#{number},#{quote},#{title},#{heading},#{pageStart},#{pageEnd},#{score})
            """)
    int insert(@Param("id") Long id, @Param("messageId") Long messageId,
               @Param("chunkId") Long chunkId, @Param("number") Integer number,
               @Param("quote") String quote, @Param("title") String title,
               @Param("heading") String heading, @Param("pageStart") Integer pageStart,
               @Param("pageEnd") Integer pageEnd, @Param("score") Double score);

    @Select("""
            SELECT CAST(c.id AS CHAR) AS id,c.citation_no AS number,CAST(c.document_chunk_id AS CHAR) AS chunkId,
                   CAST(ch.document_version_id AS CHAR) AS documentVersionId,
                   c.document_title_snapshot AS documentTitle,c.heading_snapshot AS heading,
                   c.page_start AS pageStart,c.page_end AS pageEnd,c.quote_text AS quote,
                   c.retrieval_score AS score,ch.chunk_no AS chunkNo
            FROM answer_citations c JOIN document_chunks ch ON ch.id=c.document_chunk_id
            WHERE c.message_id=#{messageId} ORDER BY c.citation_no
            """)
    List<CitationRow> findByMessage(Long messageId);

    @Select("SELECT id FROM document_chunks WHERE id=#{chunkId} AND document_version_id=#{versionId}")
    java.util.Optional<Long> findChunkIdForVersion(@Param("chunkId") Long chunkId,
                                                    @Param("versionId") Long versionId);
}
