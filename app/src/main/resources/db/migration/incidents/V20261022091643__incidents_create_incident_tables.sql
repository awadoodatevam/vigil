-- vigil-incidents (PM-M09): incidents, their state transitions, alert memberships, acknowledgements, escalations, notified teams, evidence entries, timeline entries (DM-E-32 to DM-E-39) and the incident settings (DM-E-40).
GRANT USAGE ON SCHEMA incidents TO vigil_app;

CREATE TABLE incidents.incident (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  sequence bigint NOT NULL CHECK (sequence >= 1),
  number bigint CHECK (number >= 1),
  title varchar(200) NOT NULL,
  state varchar(40) NOT NULL CHECK (state IN ('forming', 'open', 'resolved', 'closed', 'merged')),
  state_changed_at timestamptz NOT NULL,
  first_alert_fired_at timestamptz NOT NULL,
  last_alert_joined_at timestamptz NOT NULL,
  stable_at timestamptz,
  alert_count bigint NOT NULL CHECK (alert_count >= 0),
  alert_types jsonb NOT NULL,
  alert_sources jsonb NOT NULL,
  hypothesis_component_key varchar(64) CHECK (hypothesis_component_key ~ '^[A-Za-z0-9._-]{1,64}$'),
  hypothesis_snapshot jsonb,
  hypothesis_computation_id uuid,
  affected_services jsonb NOT NULL,
  severity varchar(40) CHECK (severity IN ('P1', 'P2', 'P3', 'P4')),
  owner_team_key varchar(64) CHECK (owner_team_key ~ '^[A-Za-z0-9._-]{1,64}$'),
  informed_team_keys jsonb NOT NULL,
  assignment_evaluation_id uuid,
  assignment_frozen boolean NOT NULL,
  escalation_level bigint NOT NULL CHECK (escalation_level >= 0),
  acknowledged_at timestamptz,
  acknowledged_by uuid,
  ai_status varchar(40) NOT NULL CHECK (ai_status IN ('not_run', 'available', 'unavailable')),
  ai_unavailable_reason varchar(40) CHECK (ai_unavailable_reason IN ('no_provider_reachable', 'no_provider_allowed', 'output_failed_checks')),
  ai_status_changed_at timestamptz,
  ai_last_request_group_id uuid,
  resolved_at timestamptz,
  resolved_by_verification_id uuid,
  closed_at timestamptz,
  merged_into_incident_id uuid,
  reopen_count bigint NOT NULL CHECK (reopen_count >= 0),
  CHECK (ai_status <> 'unavailable' OR ai_unavailable_reason IS NOT NULL),
  UNIQUE (tenant_id, sequence),
  UNIQUE (tenant_id, number)
);
CREATE INDEX incident_state_idx ON incidents.incident (tenant_id, state);
GRANT SELECT, INSERT, UPDATE ON incidents.incident TO vigil_app;

CREATE TABLE incidents.incident_state_transition (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  created_at timestamptz NOT NULL,
  incident_id uuid NOT NULL REFERENCES incidents.incident (id),
  transition_id varchar(64) NOT NULL CHECK (transition_id ~ '^[A-Za-z0-9._-]{1,64}$'),
  from_state varchar(40) CHECK (from_state IN ('forming', 'open', 'resolved', 'closed', 'merged')),
  to_state varchar(40) NOT NULL CHECK (to_state IN ('forming', 'open', 'resolved', 'closed', 'merged')),
  at timestamptz NOT NULL,
  actor_kind varchar(40) NOT NULL CHECK (actor_kind IN ('user', 'module')),
  actor_user_id uuid,
  actor_module varchar(40),
  reason varchar(300) NOT NULL,
  CHECK ((actor_kind = 'user' AND actor_user_id IS NOT NULL AND actor_module IS NULL) OR (actor_kind = 'module' AND actor_module IS NOT NULL AND actor_user_id IS NULL))
);
CREATE INDEX incident_state_transition_incident_idx ON incidents.incident_state_transition (tenant_id, incident_id, at);
GRANT SELECT, INSERT ON incidents.incident_state_transition TO vigil_app;

CREATE TABLE incidents.incident_alert_membership (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  incident_id uuid NOT NULL REFERENCES incidents.incident (id),
  alert_id uuid NOT NULL,
  alert_snapshot jsonb NOT NULL,
  grouping_decision_id uuid NOT NULL,
  reason jsonb NOT NULL,
  joined_at timestamptz NOT NULL,
  final_reason jsonb,
  ended_at timestamptz
);
CREATE UNIQUE INDEX incident_alert_membership_active_idx ON incidents.incident_alert_membership (tenant_id, alert_id) WHERE ended_at IS NULL;
CREATE INDEX incident_alert_membership_incident_idx ON incidents.incident_alert_membership (tenant_id, incident_id);
GRANT SELECT, INSERT, UPDATE ON incidents.incident_alert_membership TO vigil_app;

CREATE TABLE incidents.acknowledgement (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  created_at timestamptz NOT NULL,
  incident_id uuid NOT NULL REFERENCES incidents.incident (id),
  user_id uuid NOT NULL,
  team_key varchar(64) NOT NULL CHECK (team_key ~ '^[A-Za-z0-9._-]{1,64}$'),
  episode bigint NOT NULL CHECK (episode >= 1),
  at timestamptz NOT NULL,
  UNIQUE (tenant_id, incident_id, episode)
);
GRANT SELECT, INSERT ON incidents.acknowledgement TO vigil_app;

CREATE TABLE incidents.incident_escalation (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  created_at timestamptz NOT NULL,
  incident_id uuid NOT NULL REFERENCES incidents.incident (id),
  level bigint NOT NULL CHECK (level >= 1),
  team_keys jsonb NOT NULL,
  cause varchar(40) NOT NULL CHECK (cause IN ('acknowledgement_timeout', 'escalate_now')),
  timer integer CHECK (timer >= 0),
  policy_revision bigint NOT NULL,
  at timestamptz NOT NULL
);
CREATE INDEX incident_escalation_incident_idx ON incidents.incident_escalation (tenant_id, incident_id);
GRANT SELECT, INSERT ON incidents.incident_escalation TO vigil_app;

CREATE TABLE incidents.notified_team (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  created_at timestamptz NOT NULL,
  incident_id uuid NOT NULL REFERENCES incidents.incident (id),
  team_key varchar(64) NOT NULL CHECK (team_key ~ '^[A-Za-z0-9._-]{1,64}$'),
  first_notified_at timestamptz NOT NULL,
  purpose varchar(40) NOT NULL CHECK (purpose IN ('incident_notification', 'escalation', 'approval_request')),
  UNIQUE (tenant_id, incident_id, team_key)
);
GRANT SELECT, INSERT ON incidents.notified_team TO vigil_app;

CREATE TABLE incidents.evidence_entry (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  created_at timestamptz NOT NULL,
  incident_id uuid NOT NULL REFERENCES incidents.incident (id),
  kind varchar(40) NOT NULL CHECK (kind IN ('diagnostic_result', 'ai_investigation', 'similarity_match', 'approval', 'action_execution', 'recovery_verification')),
  producer_module varchar(40) NOT NULL,
  producer_record_id uuid NOT NULL,
  source_system varchar(200) NOT NULL,
  collected_at timestamptz NOT NULL,
  summary varchar(200) NOT NULL,
  content jsonb NOT NULL,
  data_category varchar(40) NOT NULL CHECK (data_category IN ('alert_data', 'service_context', 'diagnostic_output', 'runbook_text', 'incident_history', 'incident_record')),
  UNIQUE (tenant_id, producer_module, producer_record_id)
);
CREATE INDEX evidence_entry_incident_idx ON incidents.evidence_entry (tenant_id, incident_id, collected_at);
GRANT SELECT, INSERT ON incidents.evidence_entry TO vigil_app;

CREATE TABLE incidents.timeline_entry (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  created_at timestamptz NOT NULL,
  incident_id uuid NOT NULL REFERENCES incidents.incident (id),
  at timestamptz NOT NULL,
  actor_kind varchar(40) NOT NULL CHECK (actor_kind IN ('user', 'module')),
  actor_user_id uuid,
  actor_module varchar(40),
  entry_type varchar(40) NOT NULL CHECK (entry_type IN ('created', 'alert_joined', 'merged_in', 'hypothesis_changed', 'impact_changed', 'assignment_changed', 'state_changed', 'acknowledged', 'escalated', 'evidence_added', 'ai_status_changed', 'notified_team_added', 'title_changed')),
  text varchar(300) NOT NULL,
  ref_type varchar(40),
  ref_id uuid,
  CHECK ((actor_kind = 'user' AND actor_user_id IS NOT NULL AND actor_module IS NULL) OR (actor_kind = 'module' AND actor_module IS NOT NULL AND actor_user_id IS NULL))
);
CREATE INDEX timeline_entry_incident_idx ON incidents.timeline_entry (tenant_id, incident_id, at);
GRANT SELECT, INSERT ON incidents.timeline_entry TO vigil_app;

CREATE TABLE incidents.incident_settings (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  reopen_window integer NOT NULL CHECK (reopen_window >= 0),
  closure_check_interval integer NOT NULL CHECK (closure_check_interval >= 0),
  CHECK (closure_check_interval BETWEEN 1 AND 60),
  UNIQUE (tenant_id)
);
GRANT SELECT, INSERT, UPDATE ON incidents.incident_settings TO vigil_app;
