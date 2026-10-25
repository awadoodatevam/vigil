-- vigil-engine (PM-M12): workflow runs (DM-E-50), step executions (DM-E-51) and durable timers (DM-E-52).
GRANT USAGE ON SCHEMA engine TO vigil_app;

CREATE TABLE engine.workflow_run (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  incident_id uuid NOT NULL,
  mode varchar(40) NOT NULL CHECK (mode IN ('live', 'test')),
  episode bigint CHECK (episode >= 1),
  workflow_version_id uuid,
  draft_id uuid,
  draft_revision bigint,
  content jsonb NOT NULL,
  test_replay_id uuid,
  state varchar(40) NOT NULL CHECK (state IN ('running', 'waiting', 'finished', 'cancelled', 'failed')),
  wait_reason varchar(40) CHECK (wait_reason IN ('incident_forming', 'step_waiting')),
  wake_pending boolean NOT NULL,
  wake_reason varchar(60),
  current_step_execution_id uuid,
  next_step_id varchar(64) CHECK (next_step_id ~ '^[A-Za-z0-9._-]{1,64}$'),
  simulated_now timestamptz,
  started_at timestamptz NOT NULL,
  finished_at timestamptz,
  end_reason varchar(40) CHECK (end_reason IN ('path_ended', 'incident_merged', 'incident_closed', 'incident_reopened', 'step_failed_repeatedly', 'step_limit_reached')),
  end_detail varchar(300),
  CHECK (mode <> 'live' OR episode IS NOT NULL),
  CHECK ((state = 'waiting') = (wait_reason IS NOT NULL)),
  CHECK ((state IN ('finished', 'cancelled', 'failed')) = (end_reason IS NOT NULL))
);
CREATE UNIQUE INDEX workflow_run_live_episode_idx ON engine.workflow_run (tenant_id, incident_id, episode) WHERE mode = 'live';
CREATE UNIQUE INDEX workflow_run_live_unfinished_idx ON engine.workflow_run (tenant_id, incident_id) WHERE mode = 'live' AND state IN ('running', 'waiting');
CREATE INDEX workflow_run_incident_idx ON engine.workflow_run (tenant_id, incident_id);
CREATE INDEX workflow_run_state_idx ON engine.workflow_run (tenant_id, state);
GRANT SELECT, INSERT, UPDATE ON engine.workflow_run TO vigil_app;

CREATE TABLE engine.step_execution (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  run_id uuid NOT NULL REFERENCES engine.workflow_run (id),
  step_id varchar(64) NOT NULL CHECK (step_id ~ '^[A-Za-z0-9._-]{1,64}$'),
  step_type varchar(40) NOT NULL CHECK (step_type IN ('trigger', 'condition', 'diagnostic', 'ai_task', 'approval', 'action', 'recovery_check', 'escalation', 'notification', 'ticket', 'wait')),
  visit bigint NOT NULL CHECK (visit >= 1),
  state varchar(40) NOT NULL CHECK (state IN ('running', 'waiting', 'completed', 'cancelled')),
  started_at timestamptz NOT NULL,
  finished_at timestamptz,
  named_output varchar(64) CHECK (named_output ~ '^[A-Za-z0-9._-]{1,64}$'),
  result_ref jsonb,
  wake_events jsonb,
  timer_id uuid,
  step_state jsonb,
  evaluation_count bigint NOT NULL CHECK (evaluation_count >= 0),
  refusal_reason varchar(300),
  test_note varchar(300),
  failed_attempts bigint NOT NULL CHECK (failed_attempts >= 0),
  retry_due_at timestamptz,
  CHECK ((state = 'completed') = (named_output IS NOT NULL))
);
CREATE INDEX step_execution_run_idx ON engine.step_execution (tenant_id, run_id);
GRANT SELECT, INSERT, UPDATE ON engine.step_execution TO vigil_app;

CREATE TABLE engine.durable_timer (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  run_id uuid NOT NULL REFERENCES engine.workflow_run (id),
  step_execution_id uuid NOT NULL REFERENCES engine.step_execution (id),
  due_at timestamptz NOT NULL,
  state varchar(40) NOT NULL CHECK (state IN ('pending', 'fired', 'cancelled')),
  purpose varchar(60) NOT NULL
);
CREATE UNIQUE INDEX durable_timer_pending_idx ON engine.durable_timer (tenant_id, step_execution_id) WHERE state = 'pending';
CREATE INDEX durable_timer_due_idx ON engine.durable_timer (tenant_id, state, due_at);
GRANT SELECT, INSERT, UPDATE ON engine.durable_timer TO vigil_app;
