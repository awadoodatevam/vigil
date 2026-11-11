-- vigil-recovery (PM-M15): recovery check definitions (DM-E-61) and recovery verifications (DM-E-62).
GRANT USAGE ON SCHEMA recovery TO vigil_app;

CREATE TABLE recovery.recovery_check_definition (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  recovery_check_key varchar(64) NOT NULL CHECK (recovery_check_key ~ '^[A-Za-z0-9._-]{1,64}$'),
  name varchar(100) NOT NULL,
  conditions jsonb NOT NULL CHECK (jsonb_typeof(conditions) = 'array'),
  observation_window_seconds bigint NOT NULL CHECK (observation_window_seconds BETWEEN 1 AND 86400),
  is_default boolean NOT NULL,
  active boolean NOT NULL,
  UNIQUE (tenant_id, recovery_check_key)
);
CREATE UNIQUE INDEX recovery_check_definition_default_idx ON recovery.recovery_check_definition (tenant_id) WHERE is_default AND active;
GRANT SELECT, INSERT, UPDATE ON recovery.recovery_check_definition TO vigil_app;

CREATE TABLE recovery.recovery_verification (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  incident_id uuid NOT NULL,
  run_id uuid NOT NULL,
  step_execution_id uuid NOT NULL,
  recovery_check_key varchar(64) NOT NULL CHECK (recovery_check_key ~ '^[A-Za-z0-9._-]{1,64}$'),
  definition_snapshot jsonb NOT NULL,
  state varchar(40) NOT NULL CHECK (state IN ('observing', 'recovered', 'not_recovered', 'cancelled')),
  started_at timestamptz NOT NULL,
  max_wait_until timestamptz NOT NULL,
  window_started_at timestamptz,
  last_evaluation jsonb NOT NULL,
  decided_at timestamptz,
  CHECK ((state IN ('recovered', 'not_recovered')) = (decided_at IS NOT NULL)),
  CHECK (max_wait_until > started_at)
);
CREATE UNIQUE INDEX recovery_verification_step_idx ON recovery.recovery_verification (tenant_id, step_execution_id);
CREATE INDEX recovery_verification_incident_idx ON recovery.recovery_verification (tenant_id, incident_id);
GRANT SELECT, INSERT, UPDATE ON recovery.recovery_verification TO vigil_app;
