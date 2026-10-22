-- vigil-intake (PM-M06): monitoring sources, alert type definitions and mappings (DM-E-15 to DM-E-17), webhook calls, raw and normalised alerts, alerts and their changes (DM-E-18 to DM-E-23), and the alert counter (AR-R-16).
GRANT USAGE ON SCHEMA intake TO vigil_app;

CREATE TABLE intake.monitoring_source (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  name varchar(100) NOT NULL CHECK (length(name) >= 1),
  source_kind varchar(40) NOT NULL CHECK (source_kind IN ('zabbix', 'alertmanager')),
  connector_instance_id uuid NOT NULL,
  source_key varchar(64) NOT NULL CHECK (source_key ~ '^[A-Za-z0-9._-]{1,64}$'),
  webhook_token_ref_id uuid NOT NULL,
  health_check_interval integer NOT NULL CHECK (health_check_interval >= 0),
  stale_after integer NOT NULL CHECK (stale_after >= 0),
  disconnected_after integer NOT NULL CHECK (disconnected_after >= 0),
  health varchar(40) NOT NULL CHECK (health IN ('healthy', 'stale', 'disconnected')),
  health_changed_at timestamptz NOT NULL,
  last_contact_at timestamptz,
  last_test_at timestamptz,
  last_test_outcome varchar(40) CHECK (last_test_outcome IN ('passed', 'failed')),
  last_test_reason varchar(500),
  active boolean NOT NULL,
  updated_by_kind varchar(40) NOT NULL CHECK (updated_by_kind IN ('user', 'module')),
  updated_by_user_id uuid,
  updated_by_module varchar(40),
  UNIQUE (tenant_id, source_key),
  UNIQUE (tenant_id, webhook_token_ref_id),
  CHECK (health_check_interval >= 1 AND health_check_interval < stale_after AND stale_after < disconnected_after),
  CHECK ((updated_by_kind = 'user' AND updated_by_user_id IS NOT NULL AND updated_by_module IS NULL) OR (updated_by_kind = 'module' AND updated_by_module IS NOT NULL AND updated_by_user_id IS NULL))
);
GRANT SELECT, INSERT, UPDATE ON intake.monitoring_source TO vigil_app;

CREATE TABLE intake.alert_type_definition (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  alert_type varchar(64) NOT NULL CHECK (alert_type ~ '^[A-Za-z0-9._-]{1,64}$'),
  display_name varchar(100) NOT NULL CHECK (length(display_name) >= 1),
  UNIQUE (tenant_id, alert_type)
);
GRANT SELECT, INSERT, UPDATE ON intake.alert_type_definition TO vigil_app;

CREATE TABLE intake.alert_type_mapping (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  source_kind varchar(40) NOT NULL CHECK (source_kind IN ('zabbix', 'alertmanager')),
  "order" bigint NOT NULL CHECK ("order" >= 0),
  field varchar(200) NOT NULL,
  operator varchar(40) NOT NULL CHECK (operator IN ('equals', 'starts_with', 'contains')),
  value varchar(200) NOT NULL,
  alert_type varchar(64) NOT NULL CHECK (alert_type ~ '^[A-Za-z0-9._-]{1,64}$'),
  UNIQUE (tenant_id, source_kind, "order")
);
GRANT SELECT, INSERT, UPDATE ON intake.alert_type_mapping TO vigil_app;

CREATE TABLE intake.webhook_call (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  created_at timestamptz NOT NULL,
  source_id uuid NOT NULL,
  received_at timestamptz NOT NULL,
  content_type varchar(100) NOT NULL,
  body bytea NOT NULL,
  entry_count bigint NOT NULL CHECK (entry_count >= 0),
  parse_outcome varchar(40) NOT NULL CHECK (parse_outcome IN ('parsed', 'failed'))
);
CREATE INDEX webhook_call_source_idx ON intake.webhook_call (tenant_id, source_id, received_at);
GRANT SELECT, INSERT ON intake.webhook_call TO vigil_app;

CREATE TABLE intake.raw_alert (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  created_at timestamptz NOT NULL,
  webhook_call_id uuid NOT NULL REFERENCES intake.webhook_call (id),
  source_id uuid NOT NULL,
  entry_index bigint NOT NULL CHECK (entry_index >= 0),
  received_at timestamptz NOT NULL,
  payload text NOT NULL,
  UNIQUE (tenant_id, webhook_call_id, entry_index)
);
GRANT SELECT, INSERT ON intake.raw_alert TO vigil_app;

CREATE TABLE intake.normalised_alert (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  created_at timestamptz NOT NULL,
  raw_alert_id uuid NOT NULL REFERENCES intake.raw_alert (id),
  outcome varchar(40) NOT NULL CHECK (outcome IN ('normalised', 'failed')),
  failure_reason varchar(300),
  alert_key varchar(200),
  reported_status varchar(40) CHECK (reported_status IN ('firing', 'resolved')),
  alert_type varchar(64) CHECK (alert_type ~ '^[A-Za-z0-9._-]{1,64}$'),
  identity_pairs jsonb,
  title varchar(300),
  source_time timestamptz,
  source_severity varchar(50),
  applied_to_alert_id uuid,
  application varchar(40) NOT NULL CHECK (application IN ('created_alert', 'repeat_firing', 'resolved_alert', 'orphan_resolved', 'not_applied')),
  UNIQUE (tenant_id, raw_alert_id),
  CHECK (outcome <> 'normalised' OR (alert_key IS NOT NULL AND reported_status IS NOT NULL AND alert_type IS NOT NULL AND identity_pairs IS NOT NULL))
);
GRANT SELECT, INSERT ON intake.normalised_alert TO vigil_app;

CREATE TABLE intake.alert (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  source_id uuid NOT NULL,
  source_kind varchar(40) NOT NULL CHECK (source_kind IN ('zabbix', 'alertmanager')),
  alert_key varchar(200) NOT NULL,
  alert_type varchar(64) NOT NULL CHECK (alert_type ~ '^[A-Za-z0-9._-]{1,64}$'),
  identity_pairs jsonb NOT NULL,
  title varchar(300) NOT NULL,
  status varchar(40) NOT NULL CHECK (status IN ('firing', 'resolved')),
  fired_at timestamptz NOT NULL,
  first_received_at timestamptz NOT NULL,
  resolved_at timestamptz,
  resolve_received_at timestamptz,
  entry_count bigint NOT NULL CHECK (entry_count >= 1),
  sequence bigint NOT NULL CHECK (sequence >= 1),
  UNIQUE (tenant_id, sequence)
);
CREATE UNIQUE INDEX alert_firing_idx ON intake.alert (tenant_id, source_id, alert_key) WHERE status = 'firing';
GRANT SELECT, INSERT, UPDATE ON intake.alert TO vigil_app;

CREATE TABLE intake.alert_state_change (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  created_at timestamptz NOT NULL,
  alert_id uuid NOT NULL REFERENCES intake.alert (id),
  from_status varchar(40) CHECK (from_status IN ('firing', 'resolved')),
  to_status varchar(40) NOT NULL CHECK (to_status IN ('firing', 'resolved')),
  at timestamptz NOT NULL,
  source_time timestamptz,
  raw_alert_id uuid NOT NULL REFERENCES intake.raw_alert (id)
);
CREATE INDEX alert_state_change_alert_idx ON intake.alert_state_change (tenant_id, alert_id);
GRANT SELECT, INSERT ON intake.alert_state_change TO vigil_app;

CREATE TABLE intake.source_health_change (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  created_at timestamptz NOT NULL,
  source_id uuid NOT NULL REFERENCES intake.monitoring_source (id),
  from_health varchar(40) CHECK (from_health IN ('healthy', 'stale', 'disconnected')),
  to_health varchar(40) NOT NULL CHECK (to_health IN ('healthy', 'stale', 'disconnected')),
  at timestamptz NOT NULL,
  cause varchar(40) NOT NULL CHECK (cause IN ('source_created', 'contact', 'contact_age', 'test_failed'))
);
CREATE INDEX source_health_change_source_idx ON intake.source_health_change (tenant_id, source_id, at);
GRANT SELECT, INSERT ON intake.source_health_change TO vigil_app;

CREATE TABLE intake.alert_counter (
  tenant_id uuid PRIMARY KEY,
  last_sequence bigint NOT NULL CHECK (last_sequence >= 0)
);
CREATE INDEX alert_counter_tenant_idx ON intake.alert_counter (tenant_id);
GRANT SELECT, INSERT, UPDATE ON intake.alert_counter TO vigil_app;
