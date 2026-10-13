package com.graduation.ragqa.user;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Mapper
public interface UserMapper {

    @Select("""
            SELECT u.id,
                   u.username,
                   u.password_hash,
                   u.display_name,
                   u.status,
                   COALESCE(GROUP_CONCAT(r.code ORDER BY r.code SEPARATOR ','), '') AS role_codes
            FROM users u
            LEFT JOIN user_roles ur ON ur.user_id = u.id
            LEFT JOIN roles r ON r.id = ur.role_id
            WHERE u.username = #{username} AND u.deleted_at IS NULL
            GROUP BY u.id, u.username, u.password_hash, u.display_name, u.status
            """)
    Optional<UserAuthRow> findAuthByUsername(String username);

    @Select("""
            SELECT u.id,
                   u.username,
                   u.password_hash,
                   u.display_name,
                   u.status,
                   COALESCE(GROUP_CONCAT(r.code ORDER BY r.code SEPARATOR ','), '') AS role_codes
            FROM users u
            LEFT JOIN user_roles ur ON ur.user_id = u.id
            LEFT JOIN roles r ON r.id = ur.role_id
            WHERE u.id = #{id} AND u.deleted_at IS NULL
            GROUP BY u.id, u.username, u.password_hash, u.display_name, u.status
            """)
    Optional<UserAuthRow> findAuthById(Long id);

    @Select("""
            SELECT u.id,
                   u.username,
                   u.display_name,
                   u.status,
                   u.last_login_at,
                   u.created_at,
                   COALESCE(GROUP_CONCAT(r.code ORDER BY r.code SEPARATOR ','), '') AS role_codes
            FROM users u
            LEFT JOIN user_roles ur ON ur.user_id = u.id
            LEFT JOIN roles r ON r.id = ur.role_id
            WHERE u.deleted_at IS NULL
            GROUP BY u.id, u.username, u.display_name, u.status, u.last_login_at, u.created_at
            ORDER BY u.created_at DESC
            """)
    List<AdminUserRow> findAllUsers();

    @Select("SELECT COUNT(*) FROM users WHERE username = #{username}")
    int countByUsername(String username);

    @Select("SELECT id FROM roles WHERE code = #{code}")
    Optional<Long> findRoleIdByCode(String code);

    @Select("SELECT COUNT(*) FROM user_roles WHERE user_id = #{userId} AND role_id = 2")
    int isKnowledgeBaseAdmin(@Param("userId") Long userId);

    @Select("SELECT COUNT(*) FROM user_roles WHERE user_id = #{userId} AND role_id = 3")
    int isSystemAdmin(@Param("userId") Long userId);

    @Insert("""
            INSERT INTO users (id, username, password_hash, display_name, status)
            VALUES (#{id}, #{username}, #{passwordHash}, #{displayName}, 'ACTIVE')
            """)
    int insertUser(
            @Param("id") Long id,
            @Param("username") String username,
            @Param("passwordHash") String passwordHash,
            @Param("displayName") String displayName
    );

    @Insert("""
            INSERT INTO user_roles (user_id, role_id, created_by)
            VALUES (#{userId}, #{roleId}, #{createdBy})
            """)
    int insertUserRole(
            @Param("userId") Long userId,
            @Param("roleId") Long roleId,
            @Param("createdBy") Long createdBy
    );

    @Update("UPDATE users SET last_login_at = #{loginAt} WHERE id = #{userId}")
    int updateLastLogin(@Param("userId") Long userId, @Param("loginAt") LocalDateTime loginAt);

    @Update("UPDATE users SET status = #{status} WHERE id = #{userId} AND deleted_at IS NULL")
    int updateStatus(@Param("userId") Long userId, @Param("status") String status);
}
