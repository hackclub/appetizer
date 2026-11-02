ALTER TABLE scope_rules ADD public_description VARCHAR(500) NULL;
ALTER TABLE scope_rules ALTER COLUMN created_at SET DEFAULT CURRENT_TIMESTAMP;
COMMENT ON COLUMN scope_rules.created_at IS 'Timestamp of when the rule was created';
ALTER TABLE scope_rules ALTER COLUMN updated_at SET DEFAULT CURRENT_TIMESTAMP;
COMMENT ON COLUMN scope_rules.updated_at IS 'Timestamp of when the rule was last updated';