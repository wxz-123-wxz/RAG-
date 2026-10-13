package com.graduation.ragqa.auth;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.Optional;

@Mapper
public interface RefreshTokenMapper {

    @Insert("""
            INSERT INTO refresh_tokens (id, user_id, token_hash, expires_at, device_info)
            VALUES (#{id}, #{userId}, #{tokenHash}, #{expiresAt}, #{deviceInfo})
            """)
    int insert(
            @Param("id") Long id,
            @Param("userId") Long userId,
            @Param("tokenHash") String tokenHash,
            @Param("expiresAt") LocalDateTime expiresAt,
            @Param("deviceInfo") String deviceInfo
    );

    @Select("""
            SELECT id, user_id, expires_at, revoked_at
            FROM refresh_tokens
            WHERE token_hash = #{tokenHash}
            """)
    Optional<RefreshTokenRow> findByHash(String tokenHash);

    @Update("""
            UPDATE refresh_tokens
            SET revoked_at = #{revokedAt}
            WHERE id = #{id} AND revoked_at IS NULL
            """)
    int revokeById(@Param("id") Long id, @Param("revokedAt") LocalDateTime revokedAt);

    @Update("""
            UPDATE refresh_tokens
            SET revoked_at = #{revokedAt}
            WHERE token_hash = #{tokenHash} AND revoked_at IS NULL
            """)
    int revokeByHash(@Param("tokenHash") String tokenHash, @Param("revokedAt") LocalDateTime revokedAt);

    @Update("""
            UPDATE refresh_tokens
            SET revoked_at = #{revokedAt}
            WHERE user_id = #{userId} AND revoked_at IS NULL
            """)
    int revokeAllForUser(@Param("userId") Long userId, @Param("revokedAt") LocalDateTime revokedAt);
}

