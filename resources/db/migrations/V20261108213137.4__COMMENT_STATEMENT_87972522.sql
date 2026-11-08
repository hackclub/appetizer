COMMENT ON COLUMN install_request_history.id IS 'Unique id for this history entry';
COMMENT ON COLUMN install_request_history.request_id IS 'The request id';
COMMENT ON COLUMN install_request_history.status IS 'The status';
COMMENT ON COLUMN install_request_history.actor_id IS 'The actor which triggered this change';
COMMENT ON COLUMN install_request_history.actor_type IS 'The type of the actor';
COMMENT ON COLUMN install_request_history."action" IS 'The action which was done';
COMMENT ON COLUMN install_request_history.reason IS 'The reason of the action';
COMMENT ON COLUMN install_request_history.message IS 'A message';
COMMENT ON COLUMN install_request_history.state_id IS 'Unique state id, here for debugging simultaneous access problems';