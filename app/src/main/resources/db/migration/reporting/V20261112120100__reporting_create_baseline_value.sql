-- vigil-reporting (PM-M21): baseline values (DM-E-80).
GRANT USAGE ON SCHEMA reporting TO vigil_app;

CREATE TABLE reporting.baseline_value (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  metric_key varchar(40) NOT NULL CHECK (metric_key IN ('time_to_acknowledge', 'time_to_recover', 'alerts_suppressed', 'incidents_merged')),
  value numeric(12,1) NOT NULL CHECK (value >= 0),
  unit varchar(40) NOT NULL CHECK (unit IN ('minutes', 'count')),
  label varchar(60) NOT NULL,
  UNIQUE (tenant_id, metric_key)
);
GRANT SELECT, INSERT, UPDATE ON reporting.baseline_value TO vigil_app;
