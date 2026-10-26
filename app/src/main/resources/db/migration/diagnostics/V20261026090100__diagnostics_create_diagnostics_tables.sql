-- vigil-diagnostics (PM-M13): diagnostic definitions (DM-E-53) and diagnostic results (DM-E-54).
GRANT USAGE ON SCHEMA diagnostics TO vigil_app;

CREATE TABLE diagnostics.diagnostic_definition (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  diagnostic_key varchar(64) NOT NULL CHECK (diagnostic_key ~ '^[A-Za-z0-9._-]{1,64}$'),
  name varchar(100) NOT NULL,
  operation_key varchar(64) NOT NULL CHECK (operation_key ~ '^[A-Za-z0-9._-]{1,64}$'),
  parameter_values jsonb NOT NULL CHECK (jsonb_typeof(parameter_values) = 'object'),
  summary_template varchar(200) NOT NULL,
  active boolean NOT NULL,
  UNIQUE (tenant_id, diagnostic_key)
);
GRANT SELECT, INSERT, UPDATE ON diagnostics.diagnostic_definition TO vigil_app;

CREATE TABLE diagnostics.diagnostic_result (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  created_at timestamptz NOT NULL,
  incident_id uuid NOT NULL,
  run_id uuid NOT NULL,
  step_execution_id uuid NOT NULL,
  diagnostic_key varchar(64) NOT NULL CHECK (diagnostic_key ~ '^[A-Za-z0-9._-]{1,64}$'),
  component_key varchar(64) NOT NULL CHECK (component_key ~ '^[A-Za-z0-9._-]{1,64}$'),
  target_key varchar(64) CHECK (target_key ~ '^[A-Za-z0-9._-]{1,64}$'),
  operation_run_id uuid,
  outcome varchar(40) NOT NULL CHECK (outcome IN ('succeeded', 'failed')),
  values jsonb,
  collected_at timestamptz NOT NULL,
  error varchar(500),
  CHECK ((outcome = 'succeeded') = (values IS NOT NULL)),
  CHECK ((outcome = 'failed') = (error IS NOT NULL))
);
CREATE UNIQUE INDEX diagnostic_result_step_idx ON diagnostics.diagnostic_result (tenant_id, step_execution_id, diagnostic_key);
CREATE INDEX diagnostic_result_incident_idx ON diagnostics.diagnostic_result (tenant_id, incident_id);
GRANT SELECT, INSERT ON diagnostics.diagnostic_result TO vigil_app;
