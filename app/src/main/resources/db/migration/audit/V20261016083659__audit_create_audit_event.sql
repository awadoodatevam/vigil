-- vigil-audit (PM-M03): the audit log, DM-E-08 (immutable, tenant-scoped).
GRANT USAGE ON SCHEMA audit TO vigil_app;

CREATE TABLE audit.audit_event (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  created_at timestamptz NOT NULL,
  sequence bigint GENERATED ALWAYS AS IDENTITY,
  occurred_at timestamptz NOT NULL,
  actor_kind varchar(40) NOT NULL CHECK (actor_kind IN ('user', 'module')),
  actor_user_id uuid,
  actor_module varchar(40),
  event_type varchar(40) NOT NULL CHECK (event_type IN ('incident_created', 'alert_grouped',
    'hypothesis_changed', 'assignment_changed', 'incident_acknowledged', 'incident_escalated',
    'ai_result_accepted', 'ai_result_rejected', 'policy_check_result', 'approval_requested',
    'approval_decided', 'action_executed', 'action_reconciled', 'recovery_verified',
    'incident_state_changed', 'notification_sent', 'ticket_changed', 'configuration_changed')),
  subject_type varchar(40) NOT NULL,
  subject_id uuid NOT NULL,
  incident_id uuid,
  summary varchar(300) NOT NULL,
  details jsonb,
  UNIQUE (tenant_id, sequence),
  CHECK ((actor_kind = 'user' AND actor_user_id IS NOT NULL AND actor_module IS NULL) OR (actor_kind = 'module' AND actor_module IS NOT NULL AND actor_user_id IS NULL))
);
CREATE INDEX audit_event_tenant_occurred_idx ON audit.audit_event (tenant_id, occurred_at DESC, sequence DESC);
CREATE INDEX audit_event_tenant_incident_idx ON audit.audit_event (tenant_id, incident_id);
GRANT SELECT, INSERT ON audit.audit_event TO vigil_app;
