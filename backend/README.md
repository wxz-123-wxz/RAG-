# 业务后端

Spring Boot 3 业务 API，当前实现认证、角色权限、统一响应、请求追踪和第一版数据库结构。

## 前置条件

- JDK 17
- Maven 3.6.3+
- MySQL 8
- 已创建空数据库和低权限业务账号

## 本地数据库

以管理员身份在 MySQL 中执行一次：

```sql
CREATE DATABASE rag_qa CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER 'rag_qa'@'localhost' IDENTIFIED BY 'replace_with_strong_password';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES
    ON rag_qa.* TO 'rag_qa'@'localhost';
```

数据库密码和 JWT 密钥通过环境变量注入。JWT 密钥必须是至少 32 个随机字节的 Base64 值。PowerShell 可在不写文件的情况下生成：

```powershell
$bytes = New-Object byte[] 48
[Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
[Convert]::ToBase64String($bytes)
```

首次运行可以临时设置 `APP_BOOTSTRAP_ADMIN_PASSWORD` 创建系统管理员。成功创建后立即取消该环境变量；重复启动不会覆盖已有账号。

## 构建

从项目根目录执行，确保 Maven 缓存写到 D 盘项目目录：

```powershell
mvn -f backend/pom.xml -Dmaven.repo.local=.runtime/m2 test
```

## 运行

设置 `.env.example` 中列出的环境变量后执行：

```powershell
mvn -f backend/pom.xml -Dmaven.repo.local=.runtime/m2 spring-boot:run
```

接口地址：`http://localhost:8080`  
OpenAPI：`http://localhost:8080/swagger-ui.html`  
存活检查：`http://localhost:8080/health/live`

## 已实现接口

- `POST /api/v1/auth/login`
- `POST /api/v1/auth/refresh`
- `POST /api/v1/auth/logout`
- `GET /api/v1/users/me`
- `GET /health/live`

