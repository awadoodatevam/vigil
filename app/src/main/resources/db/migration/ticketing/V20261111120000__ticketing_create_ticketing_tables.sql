-- vigil-ticketing (PM-M19): ticket links (DM-E-75) and ticket sync records (DM-E-76).
GRANT USAGE ON SCHEMA ticketing TO vigil_app;

CREATE TABLE ticketing.ticket_link (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  incident_id uuid NOT NULL,
  connector_instance_id uuid NOT NULL,
  external_key varchar(50) NOT NULL,
  url varchar(500) NOT NULL,
  state varchar(40) NOT NULL CHECK (state IN ('open', 'resolved'))
);
CREATE UNIQUE INDEX ticket_link_incident_idx ON ticketing.ticket_link (tenant_id, incident_id);
GRANT SELECT, INSERT, UPDATE ON ticketing.ticket_link TO vigil_app;

CREATE TABLE ticketing.ticket_sync_record (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  incident_id uuid NOT NULL,
  ticket_link_id uuid,
  operation varchar(40) NOT NULL CHECK (operation IN ('create', 'reopen', 'resolve', 'record_escalation')),
  requested_by varchar(40) NOT NULL CHECK (requested_by IN ('workflow_step', 'routing')),
  step_execution_id uuid,
  escalation_id uuid,
  content_sent jsonb NOT NULL CHECK (jsonb_typeof(content_sent) = 'object'),
  outcome varchar(40) NOT NULL CHECK (outcome IN ('pending', 'done', 'failed')),
  error varchar(500),
  at timestamptz NOT NULL,
  finished_at timestamptz,
  CHECK ((requested_by = 'workflow_step') = (step_execution_id IS NOT NULL)),
  CHECK ((requested_by = 'routing') = (operation = 'record_escalation')),
  CHECK ((operation = 'record_escalation') = (escalation_id IS NOT NULL)),
  CHECK ((outcome = 'pending') = (finished_at IS NULL)),
  CHECK (outcome <> 'failed' OR error IS NOT NULL)
);
CREATE UNIQUE INDEX ticket_sync_record_step_idx ON ticketing.ticket_sync_record (tenant_id, step_execution_id) WHERE step_execution_id IS NOT NULL;
CREATE UNIQUE INDEX ticket_sync_record_escalation_idx ON ticketing.ticket_sync_record (tenant_id, escalation_id) WHERE escalation_id IS NOT NULL;
CREATE INDEX ticket_sync_record_incident_idx ON ticketing.ticket_sync_record (tenant_id, incident_id);
GRANT SELECT, INSERT, UPDATE ON ticketing.ticket_sync_record TO vigil_app;
