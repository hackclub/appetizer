COMMENT ON COLUMN scope_rules.pattern IS 'The scope pattern (exact, prefix, wildcard, regex, or *)';
COMMENT ON COLUMN scope_rules."action" IS 'Action: ALLOW, REVIEW, or BLOCK';
COMMENT ON COLUMN scope_rules.user_target IS 'Target user verification: ALL, VERIFIED, or UNVERIFIED';
COMMENT ON COLUMN scope_rules.scope_type IS 'Scope type: BOTH, USER, or BOT';
COMMENT ON COLUMN scope_rules.enabled IS 'Whether the rule is enabled';
COMMENT ON COLUMN scope_rules.description IS 'Optional description or note';
ALTER TABLE scope_rules ALTER COLUMN created_at DROP DEFAULT;
ALTER TABLE scope_rules ALTER COLUMN updated_at DROP DEFAULT;