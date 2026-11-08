COMMENT ON COLUMN cached_verification_status.user_id IS 'The id of the user';
COMMENT ON COLUMN cached_verification_status.verification_status IS 'The current verification status';
COMMENT ON COLUMN cached_verification_status.verified IS 'Only for faster retrieval, the real status is done by ''verification_status''';
COMMENT ON COLUMN cached_verification_status.main_account IS 'If the user is an alt, this field contains the user id of the main account';
COMMENT ON COLUMN cached_verification_status.last_checked IS 'When the status was last checked';