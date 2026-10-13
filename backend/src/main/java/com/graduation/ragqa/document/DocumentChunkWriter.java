package com.graduation.ragqa.document;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

@Service
public class DocumentChunkWriter {

    private final JdbcTemplate jdbcTemplate;

    public DocumentChunkWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public void replace(Long versionId, String sourceName, String[] chunks) {
        jdbcTemplate.update("DELETE FROM document_chunks WHERE document_version_id = ?", versionId);
        for (int index = 0; index < chunks.length; index++) {
            String content = chunks[index];
            String heading = sourceName.length() > 1000 ? sourceName.substring(0, 1000) : sourceName;
            jdbcTemplate.update("""
                            INSERT INTO document_chunks
                              (id, document_version_id, chunk_no, content, content_hash, heading_path, char_count)
                            VALUES (?, ?, ?, ?, ?, ?, ?)
                            """,
                    IdWorker.getId(), versionId, index, content, sha256(content), heading, content.length());
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to calculate chunk hash", exception);
        }
    }
}
