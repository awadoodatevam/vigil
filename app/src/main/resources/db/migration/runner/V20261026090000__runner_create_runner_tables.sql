-- vigil-runner (PM-M05): operations (DM-E-12), target access grants (DM-E-13) and operation runs (DM-E-14).
GRANT USAGE ON SCHEMA runner TO vigil_app;

CREATE TABLE runner.operation (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  operation_key varchar(64) NOT NULL CHECK (operation_key ~ '^[A-Za-z0-9._-]{1,64}$'),
  name varchar(100) NOT NULL,
  permission_kind varchar(40) NOT NULL CHECK (permission_kind IN ('diagnostic', 'action')),
  command_template varchar(2000) NOT NULL,
  parameters jsonb NOT NULL CHECK (jsonb_typeof(parameters) = 'array'),
  output_parser varchar(40) NOT NULL CHECK (output_parser IN ('df_row', 'du_top', 'count_bytes', 'single_number', 'removed_files')),
  timeout integer NOT NULL CHECK (timeout BETWEEN 1 AND 3600),
  active boolean NOT NULL,
  UNIQUE (tenant_id, operation_key)
);
GRANT SELECT, INSERT, UPDATE ON runner.operation TO vigil_app;

CREATE TABLE runner.target_access_grant (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  target_key varchar(64) NOT NULL CHECK (target_key ~ '^[A-Za-z0-9._-]{1,64}$'),
  connector_instance_id uuid NOT NULL,
  permission_kind varchar(40) NOT NULL CHECK (permission_kind IN ('diagnostic', 'action')),
  credential_ref_id uuid NOT NULL,
  active boolean NOT NULL
);
CREATE UNIQUE INDEX target_access_grant_active_idx ON runner.target_access_grant (tenant_id, target_key, permission_kind) WHERE active;
GRANT SELECT, INSERT, UPDATE ON runner.target_access_grant TO vigil_app;

CREATE TABLE runner.operation_run (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  operation_key varchar(64) NOT NULL CHECK (operation_key ~ '^[A-Za-z0-9._-]{1,64}$'),
  target_key varchar(64) NOT NULL CHECK (target_key ~ '^[A-Za-z0-9._-]{1,64}$'),
  permission_kind varchar(40) NOT NULL CHECK (permission_kind IN ('diagnostic', 'action')),
  parameters jsonb NOT NULL CHECK (jsonb_typeof(parameters) = 'object'),
  requested_by varchar(40) NOT NULL,
  request_id varchar(150) NOT NULL,
  state varchar(40) NOT NULL CHECK (state IN ('running', 'succeeded', 'failed', 'unknown', 'refused')),
  started_at timestamptz NOT NULL,
  finished_at timestamptz,
  exit_status bigint,
  output_values jsonb CHECK (output_values IS NULL OR jsonb_typeof(output_values) = 'array'),
  output_text varchar(65536),
  output_truncated boolean NOT NULL,
  error varchar(1000),
  CHECK ((state = 'running') = (finished_at IS NULL))
);
CREATE UNIQUE INDEX operation_run_request_idx ON runner.operation_run (tenant_id, request_id);
CREATE INDEX operation_run_state_idx ON runner.operation_run (tenant_id, state);
GRANT SELECT, INSERT, UPDATE ON runner.operation_run TO vigil_app;
