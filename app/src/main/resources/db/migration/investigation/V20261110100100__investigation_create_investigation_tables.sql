-- vigil-investigation (PM-M17): AI investigation results (DM-E-68) and incident summaries (DM-E-69).
GRANT USAGE ON SCHEMA investigation TO vigil_app;

CREATE TABLE investigation.ai_investigation_result (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  created_at timestamptz NOT NULL,
  incident_id uuid NOT NULL,
  origin varchar(40) NOT NULL CHECK (origin IN ('workflow_step', 'rerun')),
  run_id uuid,
  requested_by_kind varchar(40) NOT NULL CHECK (requested_by_kind IN ('user', 'module')),
  requested_by_user_id uuid,
  requested_by_module varchar(40),
  request_group_id uuid NOT NULL,
  provider_key varchar(64) NOT NULL CHECK (provider_key ~ '^[A-Za-z0-9._-]{1,64}$'),
  model_name varchar(100) NOT NULL,
  likely_cause varchar(2000),
  verdict varchar(40) CHECK (verdict IN ('confirms', 'disputes')),
  evidence_entry_ids jsonb NOT NULL CHECK (jsonb_typeof(evidence_entry_ids) = 'array'),
  uncertainty varchar(2000),
  runbooks_supplied jsonb NOT NULL CHECK (jsonb_typeof(runbooks_supplied) = 'array'),
  runbook_refs jsonb NOT NULL CHECK (jsonb_typeof(runbook_refs) = 'array'),
  raw_output varchar(20000) NOT NULL,
  raw_output_truncated boolean NOT NULL,
  validation varchar(40) NOT NULL CHECK (validation IN ('accepted', 'rejected')),
  rejection_reasons jsonb NOT NULL CHECK (jsonb_typeof(rejection_reasons) = 'array'),
  CHECK ((requested_by_kind = 'user' AND requested_by_user_id IS NOT NULL AND requested_by_module IS NULL) OR (requested_by_kind = 'module' AND requested_by_module IS NOT NULL AND requested_by_user_id IS NULL)),
  CHECK ((validation = 'accepted') = (jsonb_array_length(rejection_reasons) = 0)),
  CHECK ((origin = 'workflow_step') = (run_id IS NOT NULL))
);
CREATE INDEX ai_investigation_result_incident_idx ON investigation.ai_investigation_result (tenant_id, incident_id);
GRANT SELECT, INSERT ON investigation.ai_investigation_result TO vigil_app;

CREATE TABLE investigation.incident_summary (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  created_at timestamptz NOT NULL,
  incident_id uuid NOT NULL,
  run_id uuid,
  text varchar(2000) NOT NULL,
  source varchar(40) NOT NULL CHECK (source IN ('ai', 'template')),
  provider_key varchar(64) CHECK (provider_key ~ '^[A-Za-z0-9._-]{1,64}$'),
  CHECK ((source = 'ai') = (provider_key IS NOT NULL))
);
CREATE INDEX incident_summary_incident_idx ON investigation.incident_summary (tenant_id, incident_id);
GRANT SELECT, INSERT ON investigation.incident_summary TO vigil_app;
