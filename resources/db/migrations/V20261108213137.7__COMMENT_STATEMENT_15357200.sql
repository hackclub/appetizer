COMMENT ON COLUMN install_request_previous_resolution.request_id IS 'The request id';
COMMENT ON COLUMN install_request_previous_resolution.status IS 'The status of the previous solution';
COMMENT ON COLUMN install_request_previous_resolution.resolved_by_type IS 'The type of the actor who resolved this';
COMMENT ON COLUMN install_request_previous_resolution.resolved_by_id IS 'The id of the actor who resolved this';
COMMENT ON COLUMN install_request_previous_resolution.resolved_at IS 'When the request was resolved';
COMMENT ON COLUMN install_request_previous_resolution.raw_scopes IS 'The scopes in ''token_type:scope'' separated by comma';