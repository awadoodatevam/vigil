-- vigil-notifications (PM-M20): delivery addresses (DM-E-77) and notification records (DM-E-78).
GRANT USAGE ON SCHEMA notifications TO vigil_app;

CREATE TABLE notifications.delivery_address (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  subject_kind varchar(40) NOT NULL CHECK (subject_kind IN ('team', 'user')),
  subject_key varchar(64) NOT NULL CHECK (subject_key ~ '^[A-Za-z0-9._-]{1,64}$'),
  channel varchar(40) NOT NULL CHECK (channel IN ('teams', 'email')),
  address varchar(500) NOT NULL,
  credential_ref_id uuid,
  active boolean NOT NULL,
  CHECK ((channel = 'teams') = (credential_ref_id IS NOT NULL))
);
CREATE UNIQUE INDEX delivery_address_active_idx ON notifications.delivery_address (tenant_id, subject_kind, subject_key, channel) WHERE active;
GRANT SELECT, INSERT, UPDATE ON notifications.delivery_address TO vigil_app;

CREATE TABLE notifications.notification_record (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  created_at timestamptz NOT NULL,
  incident_id uuid NOT NULL,
  purpose varchar(40) NOT NULL CHECK (purpose IN ('incident_notification', 'escalation', 'approval_request')),
  origin_module varchar(40) NOT NULL,
  origin_entity varchar(40) NOT NULL,
  origin_id uuid NOT NULL,
  recipient_kind varchar(40) NOT NULL CHECK (recipient_kind IN ('team', 'user')),
  recipient_key varchar(64) NOT NULL CHECK (recipient_key ~ '^[A-Za-z0-9._-]{1,64}$'),
  via_team_key varchar(64) CHECK (via_team_key ~ '^[A-Za-z0-9._-]{1,64}$'),
  channel varchar(40) NOT NULL CHECK (channel IN ('teams', 'email')),
  address varchar(500) NOT NULL,
  content varchar(4000) NOT NULL,
  link varchar(500) NOT NULL,
  status varchar(40) NOT NULL CHECK (status IN ('sent', 'failed')),
  error varchar(500),
  at timestamptz NOT NULL,
  CHECK ((status = 'failed') = (error IS NOT NULL)),
  CHECK ((recipient_kind = 'user') = (via_team_key IS NOT NULL))
);
CREATE UNIQUE INDEX notification_record_sent_idx ON notifications.notification_record (tenant_id, origin_id, recipient_kind, recipient_key, channel) WHERE status = 'sent';
CREATE INDEX notification_record_incident_idx ON notifications.notification_record (tenant_id, incident_id);
GRANT SELECT, INSERT ON notifications.notification_record TO vigil_app;
