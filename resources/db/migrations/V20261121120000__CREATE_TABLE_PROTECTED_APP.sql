CREATE TABLE IF NOT EXISTS protected_app (app_id VARCHAR(20) PRIMARY KEY, app_name VARCHAR(100) NULL, protected_by VARCHAR(20) NOT NULL, protected_at TIMESTAMP NOT NULL, reason VARCHAR(2000) NULL);
COMMENT ON COLUMN protected_app.app_id IS 'The app id';
COMMENT ON COLUMN protected_app.app_name IS 'The name of the app (at the time it was protected)';
COMMENT ON COLUMN protected_app.protected_by IS 'The admin who protected the app';
COMMENT ON COLUMN protected_app.protected_at IS 'When the app was protected';
COMMENT ON COLUMN protected_app.reason IS 'Why the app is protected';
