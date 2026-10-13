# REST API 清单

版本：0.1（接口概要设计；2026-09-24 回答边界实现补充见末尾）
基础路径：`/api/v1`  
数据格式：除文件上传/下载外均为 `application/json; charset=utf-8`

## 1. 通用约定

### 1.1 认证

- 访问令牌：`Authorization: Bearer <access_token>`。
- 刷新令牌建议使用 `HttpOnly`、`Secure`（生产环境）、`SameSite` Cookie；若课程项目最终采用响应体传输，必须说明风险并避免本地持久化明文。
- `/auth/login`、`/auth/refresh` 外的业务接口默认需要认证。
- 角色权限之外，后端还必须验证会话所有者或知识库成员关系。

### 1.2 成功响应

```json
{
  "success": true,
  "data": {},
  "traceId": "01J..."
}
```

创建资源使用 `201 Created`；无响应体的删除/退出可使用 `204 No Content`；异步任务创建使用 `202 Accepted`。

### 1.3 错误响应

```json
{
  "success": false,
  "error": {
    "code": "DOCUMENT_DUPLICATE",
    "message": "该知识库中已存在内容相同的文档",
    "details": []
  },
  "traceId": "01J..."
}
```

错误信息面向用户且不包含堆栈、磁盘路径、SQL、密钥或内部模型地址。

### 1.4 分页与排序

- 请求：`page=1&pageSize=20&sort=createdAt,desc`，页码从 1 开始，`pageSize` 最大 100。
- 响应数据：`items`、`page`、`pageSize`、`total`、`totalPages`。
- 仅允许白名单排序字段，禁止将请求值直接拼接到 SQL。

### 1.5 并发、幂等与时间

- 资源修改可携带 `If-Match` 或请求体 `version`；冲突返回 `409 RESOURCE_VERSION_CONFLICT`。
- 文档上传、任务重试、问答和评测运行等写操作接受 `Idempotency-Key`；同用户、同端点、同键返回首次结果。
- 时间字段使用带时区的 ISO 8601，例如 `2026-09-23T19:00:00+08:00`。
- ID 在 JSON 中以字符串返回，避免 JavaScript 大整数精度丢失。

## 2. 认证与当前用户

| 方法 | 路径 | 角色 | 说明 | 主要状态码 |
|---|---|---|---|---|
| POST | `/auth/login` | 公开 | 账号密码登录 | 200/400/401/423/429 |
| POST | `/auth/refresh` | 持刷新令牌 | 轮换访问/刷新令牌 | 200/401 |
| POST | `/auth/logout` | 登录用户 | 撤销当前刷新令牌 | 204/401 |
| GET | `/users/me` | 登录用户 | 当前用户与权限摘要 | 200/401 |
| PATCH | `/users/me/password` | 登录用户 | 修改本人密码并撤销其他会话 | 204/400/401 |

登录请求示例：

```json
{
  "username": "student01",
  "password": "用户输入的密码"
}
```

## 3. 用户与角色管理

仅 `SYS_ADMIN` 可访问。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/admin/users` | 分页查询用户，按状态/关键字过滤 |
| POST | `/admin/users` | 创建用户 |
| GET | `/admin/users/{userId}` | 用户详情，不返回密码哈希 |
| PATCH | `/admin/users/{userId}` | 修改展示名、邮箱等资料 |
| PATCH | `/admin/users/{userId}/status` | 启用、停用或解锁账号 |
| PUT | `/admin/users/{userId}/roles` | 替换角色集合 |
| POST | `/admin/users/{userId}/password-reset` | 生成一次性重置流程或设置临时密码 |
| GET | `/admin/roles` | 查询可分配角色 |

## 4. 知识库管理

| 方法 | 路径 | 最低权限 | 说明 |
|---|---|---|---|
| GET | `/knowledge-bases` | 登录用户 | 返回当前用户可见知识库 |
| POST | `/knowledge-bases` | KB_ADMIN | 创建草稿知识库 |
| GET | `/knowledge-bases/{kbId}` | 可见/可管理 | 查询详情与权限摘要 |
| PATCH | `/knowledge-bases/{kbId}` | 该库管理员 | 修改名称、说明、访问范围 |
| DELETE | `/knowledge-bases/{kbId}` | 该库管理员/SYS_ADMIN | 逻辑删除草稿或已下架知识库 |
| POST | `/knowledge-bases/{kbId}/publish` | 该库管理员 | 发布，要求至少一个就绪文档 |
| POST | `/knowledge-bases/{kbId}/offline` | 该库管理员 | 下架并阻止新问答 |
| GET | `/knowledge-bases/{kbId}/retrieval-config` | 该库管理员 | 查询检索配置 |
| PUT | `/knowledge-bases/{kbId}/retrieval-config` | 该库管理员 | 更新配置和版本 |
| GET | `/knowledge-bases/{kbId}/members` | 该库管理员 | 管理员成员列表 |
| PUT | `/knowledge-bases/{kbId}/members/{userId}` | SYS_ADMIN | 授权知识库管理员 |
| DELETE | `/knowledge-bases/{kbId}/members/{userId}` | SYS_ADMIN | 撤销授权 |

检索配置示例：

```json
{
  "method": "HYBRID_RERANK",
  "bm25TopK": 20,
  "vectorTopK": 20,
  "fusionTopK": 10,
  "finalTopK": 5,
  "rrfK": 60,
  "bm25Weight": 1.0,
  "vectorWeight": 1.0,
  "evidenceThreshold": 0.62,
  "chunkSize": 600,
  "chunkOverlap": 100,
  "version": 1
}
```

范围仅是初始建议，服务端必须做上下限校验，最终值由实验决定。

## 5. 文档、版本和任务

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/knowledge-bases/{kbId}/documents` | 可见/可管理 | 分页查询文档；学生只看到已发布文档摘要 |
| POST | `/knowledge-bases/{kbId}/documents` | 该库管理员 | `multipart/form-data` 上传新逻辑文档和首版本 |
| GET | `/documents/{documentId}` | 可见/可管理 | 文档详情和当前版本 |
| PATCH | `/documents/{documentId}` | 该库管理员 | 修改标题、分类等元数据 |
| DELETE | `/documents/{documentId}` | 该库管理员 | 逻辑删除；已发布文档须先下架 |
| POST | `/documents/{documentId}/versions` | 该库管理员 | 上传新版本 |
| GET | `/documents/{documentId}/versions` | 该库管理员 | 版本列表 |
| POST | `/documents/{documentId}/publish` | 该库管理员 | 发布指定就绪版本 |
| POST | `/documents/{documentId}/offline` | 该库管理员 | 下架当前版本 |
| GET | `/document-versions/{versionId}/content` | 有引用/管理权限 | 返回可分页的解析正文，不返回磁盘路径 |
| GET | `/document-versions/{versionId}/chunks` | 该库管理员 | 查询片段及来源元数据 |
| GET | `/ingestion-tasks/{taskId}` | 该库管理员 | 查询任务进度和脱敏错误 |
| POST | `/ingestion-tasks/{taskId}/retry` | 该库管理员 | 幂等重试失败任务 |
| POST | `/document-versions/{versionId}/reindex` | 该库管理员 | 创建重建索引任务 |

上传成功返回 `202 Accepted`：

```json
{
  "success": true,
  "data": {
    "documentId": "101",
    "versionId": "201",
    "taskId": "301",
    "status": "QUEUED"
  },
  "traceId": "01J..."
}
```

## 6. 会话、问答和引用

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/conversations` | 登录用户 | 查询本人会话 |
| POST | `/conversations` | 登录用户 | 在一个已发布知识库中创建会话 |
| GET | `/conversations/{conversationId}` | 会话所有者 | 会话详情与分页消息 |
| PATCH | `/conversations/{conversationId}` | 会话所有者 | 重命名会话 |
| DELETE | `/conversations/{conversationId}` | 会话所有者 | 逻辑删除会话 |
| POST | `/conversations/{conversationId}/messages` | 会话所有者 | 提问并同步返回结果；必须设置超时 |
| POST | `/messages/{messageId}/regenerations` | 会话所有者 | 对用户问题重新生成回答版本 |
| GET | `/messages/{messageId}` | 会话所有者 | 查询消息、状态和回答版本 |
| GET | `/messages/{messageId}/citations` | 会话所有者 | 查询回答引用 |
| GET | `/citations/{citationId}/context` | 会话所有者/该库管理员 | 查询引用正文及有限上下文 |

首期可采用同步问答接口，前端显示加载状态；若实测生成时间过长，再增加 SSE。请求示例：

```json
{
  "content": "软件需求规格说明书应包含哪些主要内容？"
}
```

回答示例：

```json
{
  "success": true,
  "data": {
    "userMessageId": "501",
    "assistantMessage": {
      "id": "502",
      "status": "ANSWERED",
      "content": "根据知识库资料，主要包括……[1]",
      "latencyMs": 3260,
      "citations": [
        {
          "id": "601",
          "number": 1,
          "documentTitle": "需求工程课程讲义",
          "heading": "第3章 需求规格说明",
          "pageStart": 28,
          "pageEnd": 29,
          "quote": "……"
        }
      ]
    }
  },
  "traceId": "01J..."
}
```

拒答时 HTTP 仍为 200，因为这是有效业务结果：

```json
{
  "status": "REFUSED",
  "content": "当前知识库没有足够依据回答该问题",
  "citations": [],
  "reason": "INSUFFICIENT_EVIDENCE"
}
```

## 7. 反馈

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| PUT | `/messages/{messageId}/feedback` | 回答可见用户 | 新增或替换本人反馈 |
| GET | `/messages/{messageId}/feedback` | 回答可见用户 | 本人当前反馈 |
| DELETE | `/messages/{messageId}/feedback` | 反馈所有者 | 删除本人反馈 |
| GET | `/knowledge-bases/{kbId}/feedback` | 该库管理员 | 分页查看反馈，按标签/时间过滤 |

反馈请求字段：`helpful`、`tags`（`CITATION_ERROR`、`INCOMPLETE`、`SUSPECTED_ERROR`）、`comment`（最多 1000 字）。

## 8. 统计

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/knowledge-bases/{kbId}/analytics/overview` | 该库管理员 | 问答量、拒答数、反馈和耗时摘要 |
| GET | `/knowledge-bases/{kbId}/analytics/popular-questions` | 该库管理员 | 归一化热门问题 |
| GET | `/knowledge-bases/{kbId}/analytics/unanswered` | 该库管理员 | 拒答/检索失败问题 |
| GET | `/knowledge-bases/{kbId}/analytics/low-rated` | 该库管理员 | 低评价回答 |
| GET | `/knowledge-bases/{kbId}/analytics/cited-documents` | 该库管理员 | 常引用文档 |
| GET | `/admin/analytics/overview` | SYS_ADMIN | 全局运行概况 |

所有统计接口接受 `from`、`to`，并返回统计口径说明。平均值与 P95 不混用，空样本返回 `null` 而不是 0。

## 9. 标准问题与评测

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/knowledge-bases/{kbId}/evaluation-questions` | 该库管理员 | 题目列表与复核状态 |
| POST | `/knowledge-bases/{kbId}/evaluation-questions` | 该库管理员 | 创建标准题 |
| POST | `/knowledge-bases/{kbId}/evaluation-questions/import` | 该库管理员 | 导入 CSV 并返回逐行校验结果 |
| GET | `/evaluation-questions/{questionId}` | 该库管理员 | 题目详情 |
| PATCH | `/evaluation-questions/{questionId}` | 该库管理员 | 编辑题目 |
| DELETE | `/evaluation-questions/{questionId}` | 该库管理员 | 停用/逻辑删除题目 |
| POST | `/evaluation-runs` | 该库管理员 | 创建并启动运行，返回 202 |
| GET | `/evaluation-runs` | 该库管理员 | 运行列表 |
| GET | `/evaluation-runs/{runId}` | 该库管理员 | 状态、配置快照和汇总指标 |
| GET | `/evaluation-runs/{runId}/results` | 该库管理员 | 逐题结果 |
| PATCH | `/evaluation-results/{resultId}/review` | 该库管理员 | 提交人工评分 |
| POST | `/evaluation-runs/{runId}/resume` | 该库管理员 | 从未完成题继续 |
| GET | `/evaluation-runs/{runId}/export` | 该库管理员 | 导出原始结果 CSV |
| GET | `/evaluation-runs/compare` | 该库管理员 | 按多个运行 ID 对比 |

启动运行请求必须引用明确的题集版本，并包含 `retrievalMethod`、检索参数、Embedding/Reranker/Chat Model 标识和证据阈值；服务端保存不可变快照。

## 10. 系统配置与审计

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/admin/model-configs` | SYS_ADMIN | 模型配置列表，敏感值脱敏 |
| POST | `/admin/model-configs` | SYS_ADMIN | 新建配置，密钥使用 secret 引用 |
| PATCH | `/admin/model-configs/{configId}` | SYS_ADMIN | 更新非敏感参数/secret 引用 |
| POST | `/admin/model-configs/{configId}/test` | SYS_ADMIN | 连接与维度检查，不回显密钥 |
| POST | `/admin/model-configs/{configId}/activate` | SYS_ADMIN | 启用配置 |
| GET | `/admin/audit-logs` | SYS_ADMIN | 按操作者、动作、对象和时间查询 |
| GET | `/health/live` | 运维 | 进程存活，不检查所有依赖 |
| GET | `/health/ready` | 运维 | 就绪检查，返回依赖摘要但不泄露地址 |

## 11. 内部 RAG API

内部接口统一前缀 `/internal/v1`，使用服务间认证，仅允许 Spring Boot 调用。

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/documents/parse` | 解析并返回结构化内容元数据 |
| POST | `/documents/index` | 切片、向量化并建立索引 |
| DELETE | `/document-versions/{versionId}/index` | 幂等删除版本索引 |
| POST | `/retrieval/search` | 执行一种指定检索方案，返回各阶段排名 |
| POST | `/answers/generate` | 检索、证据判断和生成的完整流程 |
| POST | `/models/embedding/check` | 检查模型可达性和向量维度 |
| POST | `/models/reranker/check` | 检查重排模型 |
| POST | `/models/chat/check` | 检查生成模型，使用固定无敏感测试提示 |
| GET | `/health/live` | RAG 进程存活 |
| GET | `/health/ready` | 解析器、ES 和必要模型的就绪摘要 |

内部调用字段需包含 `traceId`、`knowledgeBaseId`、配置快照版本；服务端不能信任由浏览器直接提交的知识库过滤条件。

## 12. 核心错误码

| HTTP | 错误码 | 含义 |
|---:|---|---|
| 400 | `VALIDATION_ERROR` | 参数校验失败 |
| 400 | `FILE_EMPTY` / `FILE_TOO_LARGE` / `FILE_TYPE_UNSUPPORTED` | 文件不符合规则 |
| 401 | `AUTH_REQUIRED` / `TOKEN_INVALID` / `TOKEN_EXPIRED` | 认证失败 |
| 403 | `ACCESS_DENIED` | 角色或资源权限不足 |
| 404 | `RESOURCE_NOT_FOUND` | 资源不存在或无权感知其存在 |
| 409 | `DOCUMENT_DUPLICATE` | 同知识库内容重复 |
| 409 | `RESOURCE_VERSION_CONFLICT` | 乐观锁冲突 |
| 409 | `TASK_ALREADY_RUNNING` | 同版本已有活动任务 |
| 422 | `DOCUMENT_PARSE_FAILED` | 文档无法解析 |
| 429 | `RATE_LIMITED` | 请求频率超过限制 |
| 502 | `RAG_SERVICE_ERROR` / `MODEL_UPSTREAM_ERROR` | 内部或上游服务失败 |
| 503 | `SEARCH_UNAVAILABLE` / `SERVICE_NOT_READY` | 检索或必要服务不可用 |
| 504 | `MODEL_TIMEOUT` | 模型调用超时 |

## 13. 后续契约产物

实现阶段将依据本清单生成 OpenAPI 3.1 文档，并以契约测试校验 Spring Boot DTO、前端类型和 FastAPI 模型。若端点发生语义变更，必须先更新本清单和 OpenAPI，再修改调用方。

## 2026-09-24 已实现的回答边界契约

本节记录实际实现，前述清单仍含规划接口，不代表全部功能已完成。

- `POST /conversations/{id}/messages`：`content`、可选 `answerMode=default|extractive|generated`。普通提问不能指定 general 绕过确认。
- `POST /messages/{questionMessageId}/regenerations`：可选正文 `{ "answerMode": "extractive" }`，模式取值同上。
- `POST /messages/{sourceAnswerId}/general-answer`：用户点击“大模型回答”即直接发送 `{ "confirm": true }`，前端无二次确认弹窗；缺失/false 仍返回 400，代表未明确选择该入口。检查会话归属、知识库仍可见/已发布、来源为已保存且关联原问题的 ASSISTANT 回答（ANSWERED 或 REFUSED），不再限制覆盖程度。GENERAL、用户消息、失败记录等返回 409。只向模型发送原问题，不发送知识库片段或历史；无自动兜底，新答案单独保存。
- `assistantMessage` 新增 `answerOrigin=KNOWLEDGE_BASE|GENERAL|NONE`、`evidenceState=UNASSESSED|MODEL_REPORTED_COMPLETE|MODEL_REPORTED_PARTIAL|INSUFFICIENT|NOT_APPLICABLE`、`model`。Flyway V2 将来源与依据状态存入消息，历史查询保留这些元数据。
- FastAPI 结构化生成要求 `coverage`、`claims[{text,citations}]` 和 `missing_aspects`，不把模型自报覆盖程度当概率。拒答和通用回答的引用均为空，前者不代表依赖故障。原文回答不评估问题覆盖程度，状态为 UNASSESSED。
- 生成模式缺配置、鉴权、余额不足、参数不兼容、限流、超时、截断/非法结构或引用错误均返回安全业务错误，不保存半条回答。详见 `RagAnswerClient` 的 `CHAT_MODEL_*` 映射。
- 通用回答从知识库运行统计中排除；批量评测目前固定 extractive，不评价通用模型知识或真实生成质量。
- `GET /system/capabilities` 返回默认模式、是否配置模型、模型名及开发基线状态，不包含密钥；configured 不等于已逐请求验证远端健康。
