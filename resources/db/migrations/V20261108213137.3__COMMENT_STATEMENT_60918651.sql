COMMENT ON COLUMN install_request_scope.id IS 'Unique id for every scope entry';
COMMENT ON COLUMN install_request_scope.request_id IS 'The request id';
COMMENT ON COLUMN install_request_scope.scope IS 'The scope';
COMMENT ON COLUMN install_request_scope.description IS 'The description of the scope (from slack)';
COMMENT ON COLUMN install_request_scope."sensitive" IS 'If the scope is sensitive (from slack)';
COMMENT ON COLUMN install_request_scope.token_type IS 'The type of the token (bot or user)';
COMMENT ON COLUMN install_request_scope.optional IS 'If the scope is optional';
COMMENT ON COLUMN install_request_scope.previous_approved IS 'If the scope was previous approved';
COMMENT ON COLUMN install_request_scope.restriction_level IS 'On which level the scope is restricted';