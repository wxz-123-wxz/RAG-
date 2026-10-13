CREATE TABLE users (
    id BIGINT NOT NULL PRIMARY KEY,
    username VARCHAR(64) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    display_name VARCHAR(64) NOT NULL,
    email VARCHAR(255) NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    last_login_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted_at DATETIME(3) NULL,
    version INT NOT NULL DEFAULT 0,
    CONSTRAINT uk_users_username UNIQUE (username),
    CONSTRAINT uk_users_email UNIQUE (email),
    INDEX idx_users_status (status, deleted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE roles (
    id BIGINT NOT NULL PRIMARY KEY,
    code VARCHAR(32) NOT NULL,
    name VARCHAR(64) NOT NULL,
    description VARCHAR(255) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT uk_roles_code UNIQUE (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE user_roles (
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    created_by BIGINT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (user_id, role_id),
    CONSTRAINT fk_user_roles_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_user_roles_role FOREIGN KEY (role_id) REFERENCES roles (id),
    CONSTRAINT fk_user_roles_creator FOREIGN KEY (created_by) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE refresh_tokens (
    id BIGINT NOT NULL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    token_hash CHAR(64) NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    revoked_at DATETIME(3) NULL,
    device_info VARCHAR(255) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT uk_refresh_tokens_hash UNIQUE (token_hash),
    CONSTRAINT fk_refresh_tokens_user FOREIGN KEY (user_id) REFERENCES users (id),
    INDEX idx_refresh_tokens_user_active (user_id, revoked_at, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE knowledge_bases (
    id BIGINT NOT NULL PRIMARY KEY,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    course_name VARCHAR(128) NOT NULL,
    description TEXT NULL,
    visibility VARCHAR(16) NOT NULL DEFAULT 'RESTRICTED',
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    created_by BIGINT NOT NULL,
    retrieval_config_json JSON NULL,
    published_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted_at DATETIME(3) NULL,
    version INT NOT NULL DEFAULT 0,
    CONSTRAINT uk_knowledge_bases_code UNIQUE (code),
    CONSTRAINT fk_knowledge_bases_creator FOREIGN KEY (created_by) REFERENCES users (id),
    INDEX idx_knowledge_bases_status (status, visibility, deleted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE knowledge_base_members (
    knowledge_base_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    member_role VARCHAR(16) NOT NULL DEFAULT 'MANAGER',
    created_by BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (knowledge_base_id, user_id),
    CONSTRAINT fk_kb_members_kb FOREIGN KEY (knowledge_base_id) REFERENCES knowledge_bases (id),
    CONSTRAINT fk_kb_members_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_kb_members_creator FOREIGN KEY (created_by) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE documents (
    id BIGINT NOT NULL PRIMARY KEY,
    knowledge_base_id BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    category VARCHAR(64) NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    current_version_id BIGINT NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted_at DATETIME(3) NULL,
    version INT NOT NULL DEFAULT 0,
    CONSTRAINT fk_documents_kb FOREIGN KEY (knowledge_base_id) REFERENCES knowledge_bases (id),
    CONSTRAINT fk_documents_creator FOREIGN KEY (created_by) REFERENCES users (id),
    INDEX idx_documents_kb_status (knowledge_base_id, status, deleted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE document_versions (
    id BIGINT NOT NULL PRIMARY KEY,
    document_id BIGINT NOT NULL,
    version_no INT NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    storage_key VARCHAR(512) NOT NULL,
    media_type VARCHAR(128) NOT NULL,
    extension VARCHAR(16) NOT NULL,
    file_size BIGINT NOT NULL,
    sha256 CHAR(64) NOT NULL,
    extracted_text MEDIUMTEXT NULL,
    extraction_metadata_json JSON NULL,
    page_count INT NULL,
    parse_status VARCHAR(16) NOT NULL DEFAULT 'UPLOADED',
    parser_name VARCHAR(64) NULL,
    parser_version VARCHAR(64) NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT uk_document_versions_no UNIQUE (document_id, version_no),
    CONSTRAINT uk_document_versions_hash UNIQUE (document_id, sha256),
    CONSTRAINT fk_document_versions_document FOREIGN KEY (document_id) REFERENCES documents (id),
    CONSTRAINT fk_document_versions_creator FOREIGN KEY (created_by) REFERENCES users (id),
    INDEX idx_document_versions_status (parse_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE documents
    ADD CONSTRAINT fk_documents_current_version
    FOREIGN KEY (current_version_id) REFERENCES document_versions (id);

CREATE TABLE ingestion_tasks (
    id BIGINT NOT NULL PRIMARY KEY,
    document_version_id BIGINT NOT NULL,
    task_type VARCHAR(24) NOT NULL,
    status VARCHAR(16) NOT NULL,
    current_stage VARCHAR(24) NULL,
    attempt_no INT NOT NULL DEFAULT 1,
    progress_percent TINYINT UNSIGNED NOT NULL DEFAULT 0,
    config_snapshot_json JSON NULL,
    error_code VARCHAR(64) NULL,
    error_message VARCHAR(1000) NULL,
    trace_id VARCHAR(64) NULL,
    started_at DATETIME(3) NULL,
    finished_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_ingestion_tasks_version FOREIGN KEY (document_version_id) REFERENCES document_versions (id),
    INDEX idx_ingestion_tasks_version_status (document_version_id, status),
    INDEX idx_ingestion_tasks_status_created (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE ingestion_task_events (
    id BIGINT NOT NULL PRIMARY KEY,
    ingestion_task_id BIGINT NOT NULL,
    stage VARCHAR(24) NOT NULL,
    status VARCHAR(16) NOT NULL,
    progress_percent TINYINT UNSIGNED NOT NULL DEFAULT 0,
    detail VARCHAR(500) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_ingestion_task_events_task FOREIGN KEY (ingestion_task_id) REFERENCES ingestion_tasks (id),
    INDEX idx_ingestion_task_events_task_time (ingestion_task_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE document_chunks (
    id BIGINT NOT NULL PRIMARY KEY,
    document_version_id BIGINT NOT NULL,
    chunk_no INT NOT NULL,
    content MEDIUMTEXT NOT NULL,
    content_hash CHAR(64) NOT NULL,
    heading_path VARCHAR(1000) NULL,
    page_start INT NULL,
    page_end INT NULL,
    char_count INT NOT NULL,
    token_count INT NULL,
    embedding_model VARCHAR(128) NULL,
    index_name VARCHAR(128) NULL,
    index_doc_id VARCHAR(128) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT uk_document_chunks_no UNIQUE (document_version_id, chunk_no),
    CONSTRAINT fk_document_chunks_version FOREIGN KEY (document_version_id) REFERENCES document_versions (id),
    INDEX idx_document_chunks_hash (document_version_id, content_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE conversations (
    id BIGINT NOT NULL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    knowledge_base_id BIGINT NOT NULL,
    title VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    last_message_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted_at DATETIME(3) NULL,
    version INT NOT NULL DEFAULT 0,
    CONSTRAINT fk_conversations_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_conversations_kb FOREIGN KEY (knowledge_base_id) REFERENCES knowledge_bases (id),
    INDEX idx_conversations_user_time (user_id, deleted_at, last_message_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE messages (
    id BIGINT NOT NULL PRIMARY KEY,
    conversation_id BIGINT NOT NULL,
    parent_message_id BIGINT NULL,
    role VARCHAR(16) NOT NULL,
    content MEDIUMTEXT NOT NULL,
    version_no INT NOT NULL DEFAULT 1,
    rewritten_query TEXT NULL,
    answer_status VARCHAR(16) NULL,
    answer_version INT NULL,
    model_name VARCHAR(128) NULL,
    latency_ms INT NULL,
    error_code VARCHAR(64) NULL,
    config_snapshot_json JSON NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_messages_conversation FOREIGN KEY (conversation_id) REFERENCES conversations (id),
    CONSTRAINT fk_messages_parent FOREIGN KEY (parent_message_id) REFERENCES messages (id),
    CONSTRAINT uk_messages_answer_version UNIQUE (parent_message_id, answer_version),
    INDEX idx_messages_conversation_time (conversation_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE answer_citations (
    id BIGINT NOT NULL PRIMARY KEY,
    message_id BIGINT NOT NULL,
    document_chunk_id BIGINT NOT NULL,
    citation_no INT NOT NULL,
    quote_text TEXT NOT NULL,
    document_title_snapshot VARCHAR(255) NOT NULL,
    heading_snapshot VARCHAR(1000) NULL,
    page_start INT NULL,
    page_end INT NULL,
    retrieval_score DECIMAL(14, 8) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT uk_answer_citations_no UNIQUE (message_id, citation_no),
    CONSTRAINT fk_answer_citations_message FOREIGN KEY (message_id) REFERENCES messages (id),
    CONSTRAINT fk_answer_citations_chunk FOREIGN KEY (document_chunk_id) REFERENCES document_chunks (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE retrieval_logs (
    id BIGINT NOT NULL PRIMARY KEY,
    message_id BIGINT NOT NULL,
    query_text TEXT NOT NULL,
    method VARCHAR(32) NOT NULL,
    top_k INT NOT NULL,
    candidate_count INT NOT NULL DEFAULT 0,
    evidence_status VARCHAR(16) NOT NULL,
    evidence_reason VARCHAR(255) NULL,
    bm25_ms INT NULL,
    vector_ms INT NULL,
    rerank_ms INT NULL,
    total_ms INT NOT NULL,
    config_snapshot_json JSON NOT NULL,
    trace_id VARCHAR(64) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_retrieval_logs_message FOREIGN KEY (message_id) REFERENCES messages (id),
    INDEX idx_retrieval_logs_message (message_id),
    INDEX idx_retrieval_logs_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE retrieval_log_items (
    id BIGINT NOT NULL PRIMARY KEY,
    retrieval_log_id BIGINT NOT NULL,
    document_chunk_id BIGINT NOT NULL,
    source VARCHAR(16) NOT NULL,
    bm25_rank INT NULL,
    vector_rank INT NULL,
    fusion_rank INT NULL,
    rerank_rank INT NULL,
    bm25_score DECIMAL(14, 8) NULL,
    vector_score DECIMAL(14, 8) NULL,
    fusion_score DECIMAL(14, 8) NULL,
    rerank_score DECIMAL(14, 8) NULL,
    selected_as_evidence BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_retrieval_items_log FOREIGN KEY (retrieval_log_id) REFERENCES retrieval_logs (id),
    CONSTRAINT fk_retrieval_items_chunk FOREIGN KEY (document_chunk_id) REFERENCES document_chunks (id),
    INDEX idx_retrieval_items_rank (retrieval_log_id, fusion_rank)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE feedback (
    id BIGINT NOT NULL PRIMARY KEY,
    message_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    helpful BOOLEAN NOT NULL,
    citation_error BOOLEAN NOT NULL DEFAULT FALSE,
    incomplete BOOLEAN NOT NULL DEFAULT FALSE,
    suspected_error BOOLEAN NOT NULL DEFAULT FALSE,
    comment VARCHAR(1000) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT uk_feedback_message_user UNIQUE (message_id, user_id),
    CONSTRAINT fk_feedback_message FOREIGN KEY (message_id) REFERENCES messages (id),
    CONSTRAINT fk_feedback_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE evaluation_questions (
    id BIGINT NOT NULL PRIMARY KEY,
    knowledge_base_id BIGINT NOT NULL,
    question_code VARCHAR(32) NOT NULL,
    question TEXT NOT NULL,
    reference_answer MEDIUMTEXT NULL,
    reference_document VARCHAR(255) NULL,
    reference_section VARCHAR(255) NULL,
    reference_pages VARCHAR(64) NULL,
    evidence_text MEDIUMTEXT NULL,
    question_type VARCHAR(32) NOT NULL,
    should_refuse BOOLEAN NOT NULL DEFAULT FALSE,
    review_status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    reviewed_by BIGINT NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted_at DATETIME(3) NULL,
    CONSTRAINT uk_evaluation_questions_code UNIQUE (knowledge_base_id, question_code),
    CONSTRAINT fk_evaluation_questions_kb FOREIGN KEY (knowledge_base_id) REFERENCES knowledge_bases (id),
    CONSTRAINT fk_evaluation_questions_reviewer FOREIGN KEY (reviewed_by) REFERENCES users (id),
    CONSTRAINT fk_evaluation_questions_creator FOREIGN KEY (created_by) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE evaluation_runs (
    id BIGINT NOT NULL PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    knowledge_base_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    retrieval_method VARCHAR(32) NOT NULL,
    config_snapshot_json JSON NOT NULL,
    question_count INT NOT NULL,
    completed_count INT NOT NULL DEFAULT 0,
    metrics_json JSON NULL,
    started_at DATETIME(3) NULL,
    finished_at DATETIME(3) NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_evaluation_runs_kb FOREIGN KEY (knowledge_base_id) REFERENCES knowledge_bases (id),
    CONSTRAINT fk_evaluation_runs_creator FOREIGN KEY (created_by) REFERENCES users (id),
    INDEX idx_evaluation_runs_kb_created (knowledge_base_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE evaluation_results (
    id BIGINT NOT NULL PRIMARY KEY,
    evaluation_run_id BIGINT NOT NULL,
    evaluation_question_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    generated_answer MEDIUMTEXT NULL,
    retrieved_chunk_ids_json JSON NULL,
    citation_snapshot_json JSON NULL,
    recall_at_5 DECIMAL(6, 5) NULL,
    reciprocal_rank DECIMAL(6, 5) NULL,
    refusal_correct BOOLEAN NULL,
    answer_correctness TINYINT NULL,
    completeness TINYINT NULL,
    citation_accuracy TINYINT NULL,
    latency_ms INT NULL,
    error_code VARCHAR(64) NULL,
    reviewer_id BIGINT NULL,
    review_comment VARCHAR(1000) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT uk_evaluation_results_question UNIQUE (evaluation_run_id, evaluation_question_id),
    CONSTRAINT fk_evaluation_results_run FOREIGN KEY (evaluation_run_id) REFERENCES evaluation_runs (id),
    CONSTRAINT fk_evaluation_results_question FOREIGN KEY (evaluation_question_id) REFERENCES evaluation_questions (id),
    CONSTRAINT fk_evaluation_results_reviewer FOREIGN KEY (reviewer_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE system_configs (
    id BIGINT NOT NULL PRIMARY KEY,
    config_key VARCHAR(128) NOT NULL,
    config_value_json JSON NULL,
    is_sensitive BOOLEAN NOT NULL DEFAULT FALSE,
    secret_ref VARCHAR(255) NULL,
    description VARCHAR(255) NULL,
    version INT NOT NULL DEFAULT 0,
    created_by BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT uk_system_configs_key UNIQUE (config_key),
    CONSTRAINT fk_system_configs_creator FOREIGN KEY (created_by) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE audit_logs (
    id BIGINT NOT NULL PRIMARY KEY,
    actor_user_id BIGINT NULL,
    action VARCHAR(64) NOT NULL,
    resource_type VARCHAR(64) NOT NULL,
    resource_id VARCHAR(64) NULL,
    result VARCHAR(16) NOT NULL,
    client_ip VARCHAR(64) NULL,
    user_agent VARCHAR(512) NULL,
    trace_id VARCHAR(64) NULL,
    detail_json JSON NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_audit_logs_actor FOREIGN KEY (actor_user_id) REFERENCES users (id),
    INDEX idx_audit_logs_actor_time (actor_user_id, created_at),
    INDEX idx_audit_logs_resource (resource_type, resource_id, created_at),
    INDEX idx_audit_logs_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO roles (id, code, name, description) VALUES
    (1, 'STUDENT', '学生', '使用已发布知识库进行问答并管理本人会话'),
    (2, 'KB_ADMIN', '知识库管理员', '管理被授权知识库、文档与评测'),
    (3, 'SYS_ADMIN', '系统管理员', '管理用户、全局配置与审计信息');
