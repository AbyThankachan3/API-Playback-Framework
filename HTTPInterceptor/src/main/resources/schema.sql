-- src/main/resources/schema.sql
CREATE INDEX IF NOT EXISTS idx_method_endpoint ON api_log(method, endpoint);
CREATE INDEX IF NOT EXISTS idx_parameters_jsonb ON api_log USING GIN (parameters jsonb_path_ops);
CREATE INDEX IF NOT EXISTS idx_headers_jsonb ON api_log USING GIN (headers jsonb_path_ops);
CREATE INDEX IF NOT EXISTS idx_request_body_jsonb ON api_log USING GIN (request_body jsonb_path_ops);
CREATE INDEX IF NOT EXISTS idx_created_at ON api_log(created_at DESC);
