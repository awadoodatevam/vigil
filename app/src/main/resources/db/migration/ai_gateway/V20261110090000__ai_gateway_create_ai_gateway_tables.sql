GRANT USAGE ON SCHEMA ai_gateway TO vigil_app;
-- vigil-ai-gateway (PM-M18): AI provider configurations (DM-E-70), allowed providers (DM-E-71),
-- task assignments (DM-E-72), data routing rules (DM-E-73) and AI usage records (DM-E-74).

CREATE TABLE ai_gateway.ai_provider_configuration (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  provider_key varchar(64) NOT NULL CHECK (provider_key ~ '^[A-Za-z0-9._-]{1,64}$'),
  provider_kind varchar(40) NOT NULL CHECK (provider_kind IN ('claude', 'openai', 'local_model', 'local_embedding')),
  connector_instance_id uuid NOT NULL,
  model_name varchar(100) NOT NULL,
  location varchar(40) NOT NULL CHECK (location IN ('external', 'in_house')),
  price_input_per_million numeric(12,6) NOT NULL CHECK (price_input_per_million >= 0),
  price_output_per_million numeric(12,6) NOT NULL CHECK (price_output_per_million >= 0),
  active boolean NOT NULL,
  UNIQUE (tenant_id, provider_key)
);
GRANT SELECT, INSERT, UPDATE ON ai_gateway.ai_provider_configuration TO vigil_app;

CREATE TABLE ai_gateway.allowed_provider (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  task varchar(40) NOT NULL CHECK (task IN ('SL-T-1', 'SL-T-2', 'SL-T-3')),
  provider_kind varchar(40) NOT NULL CHECK (provider_kind IN ('claude', 'openai', 'local_model', 'local_embedding')),
  UNIQUE (tenant_id, task, provider_kind)
);
GRANT SELECT, INSERT, UPDATE ON ai_gateway.allowed_provider TO vigil_app;

CREATE TABLE ai_gateway.task_assignment (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  task varchar(40) NOT NULL CHECK (task IN ('SL-T-1', 'SL-T-2', 'SL-T-3')),
  provider_keys jsonb NOT NULL CHECK (jsonb_typeof(provider_keys) = 'array'),
  updated_by_kind varchar(40) NOT NULL CHECK (updated_by_kind IN ('user', 'module')),
  updated_by_user_id uuid,
  updated_by_module varchar(40),
  CHECK ((updated_by_kind = 'user' AND updated_by_user_id IS NOT NULL AND updated_by_module IS NULL) OR (updated_by_kind = 'module' AND updated_by_module IS NOT NULL AND updated_by_user_id IS NULL)),
  UNIQUE (tenant_id, task)
);
GRANT SELECT, INSERT, UPDATE ON ai_gateway.task_assignment TO vigil_app;

CREATE TABLE ai_gateway.data_routing_rule (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  data_category varchar(40) NOT NULL CHECK (data_category IN ('alert_data', 'service_context', 'diagnostic_output', 'runbook_text', 'incident_history', 'incident_record')),
  allowed_provider_keys jsonb NOT NULL CHECK (jsonb_typeof(allowed_provider_keys) = 'array'),
  updated_by_kind varchar(40) NOT NULL CHECK (updated_by_kind IN ('user', 'module')),
  updated_by_user_id uuid,
  updated_by_module varchar(40),
  CHECK ((updated_by_kind = 'user' AND updated_by_user_id IS NOT NULL AND updated_by_module IS NULL) OR (updated_by_kind = 'module' AND updated_by_module IS NOT NULL AND updated_by_user_id IS NULL)),
  UNIQUE (tenant_id, data_category)
);
GRANT SELECT, INSERT, UPDATE ON ai_gateway.data_routing_rule TO vigil_app;

CREATE TABLE ai_gateway.ai_usage_record (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  created_at timestamptz NOT NULL,
  task varchar(40) NOT NULL CHECK (task IN ('SL-T-1', 'SL-T-2', 'SL-T-3')),
  provider_key varchar(64) NOT NULL CHECK (provider_key ~ '^[A-Za-z0-9._-]{1,64}$'),
  provider_location varchar(40) NOT NULL CHECK (provider_location IN ('external', 'in_house')),
  model_name varchar(100) NOT NULL,
  request_group_id uuid NOT NULL,
  incident_id uuid,
  fallback_position bigint NOT NULL CHECK (fallback_position >= 1),
  data_categories jsonb NOT NULL CHECK (jsonb_typeof(data_categories) = 'array'),
  outcome varchar(40) NOT NULL CHECK (outcome IN ('succeeded', 'unreachable', 'provider_error', 'refused_by_data_routing')),
  input_units bigint NOT NULL CHECK (input_units >= 0),
  output_units bigint NOT NULL CHECK (output_units >= 0),
  cost numeric(12,6) NOT NULL CHECK (cost >= 0),
  started_at timestamptz NOT NULL,
  finished_at timestamptz NOT NULL
);
CREATE INDEX ai_usage_record_request_group_idx ON ai_gateway.ai_usage_record (tenant_id, request_group_id);
GRANT SELECT, INSERT ON ai_gateway.ai_usage_record TO vigil_app;
