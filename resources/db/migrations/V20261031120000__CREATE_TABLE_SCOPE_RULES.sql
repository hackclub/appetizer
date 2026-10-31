CREATE TABLE IF NOT EXISTS scope_rules (
    id BIGSERIAL PRIMARY KEY,
    pattern VARCHAR(255) NOT NULL,
    action VARCHAR(20) NOT NULL,
    user_target VARCHAR(20) NOT NULL DEFAULT 'ALL',
    scope_type VARCHAR(20) NOT NULL DEFAULT 'BOTH',
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    description VARCHAR(500) NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Migrate existing restricted_scopes data if table exists
DO $$
BEGIN
    IF EXISTS (SELECT FROM information_schema.tables WHERE table_name = 'restricted_scopes') THEN
        INSERT INTO scope_rules (pattern, action, user_target, scope_type, enabled, created_at, updated_at)
        SELECT
            scope,
            CASE
                WHEN restricted = 'ALLOWED' THEN 'ALLOW'
                WHEN restricted = 'ALLOWED_FOR_UNVERIFIED' THEN 'ALLOW'
                WHEN restricted = 'RESTRICTED' AND review = TRUE THEN 'REVIEW'
                ELSE 'BLOCK'
            END,
            CASE
                WHEN restricted = 'ALLOWED' THEN 'VERIFIED'
                WHEN restricted = 'ALLOWED_FOR_UNVERIFIED' THEN 'ALL'
                ELSE 'ALL'
            END,
            scope_type,
            TRUE,
            CURRENT_TIMESTAMP,
            CURRENT_TIMESTAMP
        FROM restricted_scopes
        ON CONFLICT DO NOTHING;
    END IF;
END $$;
