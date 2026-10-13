ALTER TABLE messages
    ADD COLUMN answer_origin VARCHAR(32) NOT NULL DEFAULT 'KNOWLEDGE_BASE',
    ADD COLUMN evidence_state VARCHAR(32) NOT NULL DEFAULT 'UNASSESSED';

UPDATE messages SET answer_origin='NONE', evidence_state='INSUFFICIENT' WHERE answer_status='REFUSED';
UPDATE messages SET answer_origin='NONE', evidence_state='NOT_APPLICABLE' WHERE role='USER';
