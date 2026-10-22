-- vigil-routing (PM-M10): routing rule set revisions, escalation policy revisions (DM-E-41, DM-E-42) and routing evaluations (DM-E-43).
GRANT USAGE ON SCHEMA routing TO vigil_app;

CREATE TABLE routing.routing_rule_set_revision (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  created_at timestamptz NOT NULL,
  revision bigint NOT NULL CHECK (revision >= 1),
  rules jsonb NOT NULL,
  saved_by_kind varchar(40) NOT NULL CHECK (saved_by_kind IN ('user', 'module')),
  saved_by_user_id uuid,
  saved_by_module varchar(40),
  saved_at timestamptz NOT NULL,
  change_summary varchar(300) NOT NULL,
  UNIQUE (tenant_id, revision),
  CHECK ((saved_by_kind = 'user' AND saved_by_user_id IS NOT NULL AND saved_by_module IS NULL) OR (saved_by_kind = 'module' AND saved_by_module IS NOT NULL AND saved_by_user_id IS NULL))
);
GRANT SELECT, INSERT ON routing.routing_rule_set_revision TO vigil_app;

CREATE TABLE routing.escalation_policy_revision (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  created_at timestamptz NOT NULL,
  revision bigint NOT NULL CHECK (revision >= 1),
  levels jsonb NOT NULL,
  saved_by_kind varchar(40) NOT NULL CHECK (saved_by_kind IN ('user', 'module')),
  saved_by_user_id uuid,
  saved_by_module varchar(40),
  saved_at timestamptz NOT NULL,
  UNIQUE (tenant_id, revision),
  CHECK ((saved_by_kind = 'user' AND saved_by_user_id IS NOT NULL AND saved_by_module IS NULL) OR (saved_by_kind = 'module' AND saved_by_module IS NOT NULL AND saved_by_user_id IS NULL))
);
GRANT SELECT, INSERT ON routing.escalation_policy_revision TO vigil_app;

CREATE TABLE routing.routing_evaluation (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  created_at timestamptz NOT NULL,
  incident_id uuid NOT NULL,
  requested_by varchar(40) NOT NULL CHECK (requested_by IN ('correlation', 'workflows', 'test_replay')),
  rule_set_revision bigint NOT NULL CHECK (rule_set_revision >= 1),
  inputs jsonb NOT NULL,
  matched_rule_key varchar(64) NOT NULL CHECK (matched_rule_key ~ '^[A-Za-z0-9._-]{1,64}$'),
  result_severity varchar(40) NOT NULL CHECK (result_severity IN ('P1', 'P2', 'P3', 'P4')),
  result_owner_team_key varchar(64) NOT NULL CHECK (result_owner_team_key ~ '^[A-Za-z0-9._-]{1,64}$'),
  result_informed_team_keys jsonb NOT NULL,
  applied boolean NOT NULL,
  not_applied_reason varchar(40) CHECK (not_applied_reason IN ('assignment_frozen', 'dry_run', 'incident_not_active')),
  at timestamptz NOT NULL,
  CHECK ((applied AND not_applied_reason IS NULL) OR (NOT applied AND not_applied_reason IS NOT NULL))
);
CREATE INDEX routing_evaluation_incident_idx ON routing.routing_evaluation (tenant_id, incident_id, at);
GRANT SELECT, INSERT ON routing.routing_evaluation TO vigil_app;
