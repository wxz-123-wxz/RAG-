-- NULL is an explicit all-accessible-knowledge-bases conversation scope.
-- Existing conversations retain their original individual knowledge base.
ALTER TABLE conversations MODIFY knowledge_base_id BIGINT NULL;
