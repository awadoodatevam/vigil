-- vigil-correlation (PM-M08): grouping decisions (DM-E-29), hypothesis computations (DM-E-30), correlation settings (DM-E-31) and the worker's cursor (AR-R-17).
GRANT USAGE ON SCHEMA correlation TO vigil_app;

CREATE TABLE correlation.grouping_decision (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  created_at timestamptz NOT NULL,
  alert_id uuid NOT NULL,
  alert_sequence bigint NOT NULL,
  component_key varchar(64) CHECK (component_key ~ '^[A-Za-z0-9._-]{1,64}$'),
  generation bigint NOT NULL CHECK (generation >= 0),
  decision varchar(40) NOT NULL CHECK (decision IN ('created_incident', 'joined', 'reopened')),
  incident_id uuid NOT NULL,
  merged_incident_ids jsonb NOT NULL,
  related_alert_id uuid,
  relation varchar(40) CHECK (relation IN ('same_component', 'depends_on', 'depended_on_by', 'same_unknown_identity')),
  relation_path jsonb,
  time_delta integer CHECK (time_delta >= 0),
  decided_at timestamptz NOT NULL,
  UNIQUE (tenant_id, alert_id),
  CHECK ((decision = 'created_incident' AND related_alert_id IS NULL AND relation IS NULL AND relation_path IS NULL AND time_delta IS NULL) OR (decision <> 'created_incident' AND related_alert_id IS NOT NULL AND relation IS NOT NULL AND relation_path IS NOT NULL AND time_delta IS NOT NULL))
);
CREATE INDEX grouping_decision_incident_idx ON correlation.grouping_decision (tenant_id, incident_id);
CREATE INDEX grouping_decision_component_idx ON correlation.grouping_decision (tenant_id, component_key);
GRANT SELECT, INSERT ON correlation.grouping_decision TO vigil_app;

CREATE TABLE correlation.hypothesis_computation (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  created_at timestamptz NOT NULL,
  incident_id uuid NOT NULL,
  generation bigint NOT NULL CHECK (generation >= 0),
  affected_component_keys jsonb NOT NULL,
  dependent_counts jsonb NOT NULL,
  result_component_key varchar(64) CHECK (result_component_key ~ '^[A-Za-z0-9._-]{1,64}$'),
  tie_break varchar(40) NOT NULL CHECK (tie_break IN ('none', 'criticality', 'component_key')),
  previous_component_key varchar(64) CHECK (previous_component_key ~ '^[A-Za-z0-9._-]{1,64}$'),
  computed_at timestamptz NOT NULL
);
CREATE INDEX hypothesis_computation_incident_idx ON correlation.hypothesis_computation (tenant_id, incident_id, computed_at);
GRANT SELECT, INSERT ON correlation.hypothesis_computation TO vigil_app;

CREATE TABLE correlation.correlation_settings (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  grouping_window integer NOT NULL CHECK (grouping_window >= 1),
  settle_period integer NOT NULL CHECK (settle_period >= 1),
  max_forming integer NOT NULL,
  stability_check_interval integer NOT NULL CHECK (stability_check_interval BETWEEN 1 AND 5),
  CHECK (max_forming > settle_period),
  UNIQUE (tenant_id)
);
GRANT SELECT, INSERT, UPDATE ON correlation.correlation_settings TO vigil_app;

CREATE TABLE correlation.cursor (
  tenant_id uuid PRIMARY KEY,
  last_sequence bigint NOT NULL CHECK (last_sequence >= 0)
);
GRANT SELECT, INSERT, UPDATE ON correlation.cursor TO vigil_app;
