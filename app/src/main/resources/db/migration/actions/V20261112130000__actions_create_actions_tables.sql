-- vigil-actions (PM-M14): registered actions, policies, proposals, approval requests and decisions, action executions (DM-E-55 to DM-E-60).
GRANT USAGE ON SCHEMA actions TO vigil_app;

CREATE TABLE actions.registered_action (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  action_key varchar(64) NOT NULL CHECK (action_key ~ '^[A-Za-z0-9._-]{1,64}$'),
  name varchar(100) NOT NULL,
  operation_key varchar(64) NOT NULL CHECK (operation_key ~ '^[A-Za-z0-9._-]{1,64}$'),
  parameter_values jsonb NOT NULL CHECK (jsonb_typeof(parameter_values) = 'object'),
  limits jsonb NOT NULL CHECK (jsonb_typeof(limits) = 'array' AND jsonb_array_length(limits) >= 1),
  attempt_limit bigint NOT NULL CHECK (attempt_limit >= 1),
  precondition jsonb NOT NULL CHECK (jsonb_typeof(precondition) = 'object'),
  reconciliation jsonb NOT NULL CHECK (jsonb_typeof(reconciliation) = 'object'),
  recovery_check_key varchar(64) NOT NULL CHECK (recovery_check_key ~ '^[A-Za-z0-9._-]{1,64}$'),
  active boolean NOT NULL,
  updated_by_kind varchar(40) NOT NULL CHECK (updated_by_kind IN ('user', 'module')),
  updated_by_user_id uuid,
  updated_by_module varchar(40),
  CHECK ((updated_by_kind = 'user' AND updated_by_user_id IS NOT NULL AND updated_by_module IS NULL) OR (updated_by_kind = 'module' AND updated_by_module IS NOT NULL AND updated_by_user_id IS NULL)),
  UNIQUE (tenant_id, action_key)
);
GRANT SELECT, INSERT, UPDATE ON actions.registered_action TO vigil_app;

CREATE TABLE actions.action_policy (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  action_key varchar(64) NOT NULL CHECK (action_key ~ '^[A-Za-z0-9._-]{1,64}$'),
  service_key varchar(64) CHECK (service_key ~ '^[A-Za-z0-9._-]{1,64}$'),
  outcome varchar(40) NOT NULL CHECK (outcome IN ('blocked', 'approval_required', 'preauthorized')),
  active boolean NOT NULL
);
CREATE UNIQUE INDEX action_policy_default_idx ON actions.action_policy (tenant_id, action_key) WHERE service_key IS NULL AND active;
CREATE UNIQUE INDEX action_policy_service_idx ON actions.action_policy (tenant_id, action_key, service_key) WHERE service_key IS NOT NULL AND active;
GRANT SELECT, INSERT, UPDATE ON actions.action_policy TO vigil_app;

CREATE TABLE actions.action_proposal (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  created_at timestamptz NOT NULL,
  incident_id uuid NOT NULL,
  run_id uuid NOT NULL,
  action_key varchar(64) NOT NULL CHECK (action_key ~ '^[A-Za-z0-9._-]{1,64}$'),
  action_snapshot jsonb NOT NULL CHECK (jsonb_typeof(action_snapshot) = 'object'),
  component_key varchar(64) NOT NULL CHECK (component_key ~ '^[A-Za-z0-9._-]{1,64}$'),
  target_key varchar(64) NOT NULL CHECK (target_key ~ '^[A-Za-z0-9._-]{1,64}$'),
  similarity_result_id uuid NOT NULL,
  past_incident_key varchar(64) NOT NULL CHECK (past_incident_key ~ '^[A-Za-z0-9._-]{1,64}$'),
  score numeric(3,2) NOT NULL,
  success_count bigint NOT NULL,
  UNIQUE (tenant_id, run_id)
);
GRANT SELECT, INSERT ON actions.action_proposal TO vigil_app;

CREATE TABLE actions.approval_request (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  incident_id uuid NOT NULL,
  run_id uuid NOT NULL,
  step_execution_id uuid NOT NULL,
  proposal_id uuid NOT NULL,
  sequence_in_run bigint NOT NULL CHECK (sequence_in_run >= 1),
  policy_outcome varchar(40) CHECK (policy_outcome IN ('blocked', 'approval_required', 'preauthorized')),
  eligible_team_keys jsonb NOT NULL CHECK (jsonb_typeof(eligible_team_keys) = 'array'),
  excluded_user_ids jsonb NOT NULL CHECK (jsonb_typeof(excluded_user_ids) = 'array'),
  snapshot jsonb NOT NULL CHECK (jsonb_typeof(snapshot) = 'object'),
  snapshot_hash varchar(128) NOT NULL,
  expires_at timestamptz NOT NULL,
  state varchar(40) NOT NULL CHECK (state IN ('pending', 'approved', 'rejected', 'expired', 'preauthorized', 'blocked', 'refused', 'cancelled')),
  refusal_reason varchar(40) CHECK (refusal_reason IN ('attempt_limit_reached', 'unresolved_execution')),
  decided_at timestamptz,
  decided_by uuid,
  CHECK ((state = 'refused') = (refusal_reason IS NOT NULL)),
  CHECK ((state = 'refused') = (policy_outcome IS NULL)),
  CHECK ((state IN ('approved', 'rejected')) = (decided_by IS NOT NULL)),
  UNIQUE (tenant_id, step_execution_id),
  UNIQUE (tenant_id, run_id, sequence_in_run)
);
CREATE INDEX approval_request_incident_idx ON actions.approval_request (tenant_id, incident_id);
GRANT SELECT, INSERT, UPDATE ON actions.approval_request TO vigil_app;

CREATE TABLE actions.step_without_proposal (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  created_at timestamptz NOT NULL,
  run_id uuid NOT NULL,
  incident_id uuid NOT NULL,
  step_execution_id uuid NOT NULL,
  at timestamptz NOT NULL,
  UNIQUE (tenant_id, step_execution_id)
);
CREATE INDEX step_without_proposal_run_idx ON actions.step_without_proposal (tenant_id, run_id);
GRANT SELECT, INSERT ON actions.step_without_proposal TO vigil_app;

CREATE TABLE actions.approval_decision (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  created_at timestamptz NOT NULL,
  request_id uuid NOT NULL,
  user_id uuid NOT NULL,
  team_key varchar(64) NOT NULL CHECK (team_key ~ '^[A-Za-z0-9._-]{1,64}$'),
  decision varchar(40) NOT NULL CHECK (decision IN ('approve', 'reject')),
  snapshot_hash_shown varchar(128) NOT NULL,
  at timestamptz NOT NULL,
  UNIQUE (tenant_id, request_id)
);
GRANT SELECT, INSERT ON actions.approval_decision TO vigil_app;

CREATE TABLE actions.action_execution (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  incident_id uuid NOT NULL,
  run_id uuid NOT NULL,
  step_execution_id uuid NOT NULL,
  proposal_id uuid NOT NULL,
  action_key varchar(64) NOT NULL CHECK (action_key ~ '^[A-Za-z0-9._-]{1,64}$'),
  approval_request_ids jsonb NOT NULL CHECK (jsonb_typeof(approval_request_ids) = 'array'),
  attempt_number bigint NOT NULL CHECK (attempt_number >= 1),
  state varchar(40) NOT NULL CHECK (state IN ('recorded', 'rechecking', 'not_executed', 'executing', 'reconciling', 'succeeded', 'failed', 'unresolved')),
  recheck_pass bigint NOT NULL CHECK (recheck_pass >= 0),
  recheck jsonb CHECK (jsonb_typeof(recheck) = 'array'),
  operation_run_id uuid,
  reconciliation_run_ids jsonb NOT NULL CHECK (jsonb_typeof(reconciliation_run_ids) = 'array'),
  started_at timestamptz NOT NULL,
  executing_at timestamptz,
  finished_at timestamptz,
  UNIQUE (tenant_id, step_execution_id)
);
CREATE INDEX action_execution_incident_idx ON actions.action_execution (tenant_id, incident_id, action_key);
CREATE INDEX action_execution_run_idx ON actions.action_execution (tenant_id, run_id);
GRANT SELECT, INSERT, UPDATE ON actions.action_execution TO vigil_app;
