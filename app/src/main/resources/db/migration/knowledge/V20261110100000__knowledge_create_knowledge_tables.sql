-- vigil-knowledge (PM-M16): runbooks (DM-E-63), past incidents (DM-E-64), incident embeddings
-- (DM-E-65), similarity search results (DM-E-66) and knowledge settings (DM-E-67).
GRANT USAGE ON SCHEMA knowledge TO vigil_app;

CREATE TABLE knowledge.runbook (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  runbook_key varchar(64) NOT NULL CHECK (runbook_key ~ '^[A-Za-z0-9._-]{1,64}$'),
  version bigint NOT NULL CHECK (version >= 1),
  title varchar(200) NOT NULL,
  applicable_alert_types jsonb NOT NULL CHECK (jsonb_typeof(applicable_alert_types) = 'array'),
  applicable_component_kinds jsonb NOT NULL CHECK (jsonb_typeof(applicable_component_kinds) = 'array'),
  text varchar(20000) NOT NULL,
  active boolean NOT NULL,
  CHECK (jsonb_array_length(applicable_alert_types) >= 1)
);
CREATE UNIQUE INDEX runbook_active_key_idx ON knowledge.runbook (tenant_id, runbook_key) WHERE active;
GRANT SELECT, INSERT, UPDATE ON knowledge.runbook TO vigil_app;

CREATE TABLE knowledge.past_incident (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  past_incident_key varchar(64) NOT NULL CHECK (past_incident_key ~ '^[A-Za-z0-9._-]{1,64}$'),
  title varchar(200) NOT NULL,
  record_label varchar(60) NOT NULL,
  opened_at timestamptz NOT NULL,
  acknowledged_at timestamptz,
  recovered_at timestamptz,
  alert_count bigint NOT NULL CHECK (alert_count >= 1),
  alert_types jsonb NOT NULL CHECK (jsonb_typeof(alert_types) = 'array'),
  affected_service_keys jsonb NOT NULL CHECK (jsonb_typeof(affected_service_keys) = 'array'),
  hypothesis_component_key varchar(64) NOT NULL CHECK (hypothesis_component_key ~ '^[A-Za-z0-9._-]{1,64}$'),
  summary varchar(2000) NOT NULL,
  fix_description varchar(2000) NOT NULL,
  fix_action_key varchar(64) CHECK (fix_action_key ~ '^[A-Za-z0-9._-]{1,64}$'),
  fix_success_count bigint NOT NULL CHECK (fix_success_count >= 0),
  active boolean NOT NULL,
  UNIQUE (tenant_id, past_incident_key)
);
GRANT SELECT, INSERT, UPDATE ON knowledge.past_incident TO vigil_app;

CREATE TABLE knowledge.incident_embedding (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  created_at timestamptz NOT NULL,
  subject_kind varchar(40) NOT NULL CHECK (subject_kind IN ('past_incident', 'incident')),
  subject_key varchar(64) NOT NULL,
  provider_key varchar(64) NOT NULL CHECK (provider_key ~ '^[A-Za-z0-9._-]{1,64}$'),
  model_name varchar(100) NOT NULL,
  search_text varchar(2000) NOT NULL,
  vector public.vector NOT NULL
);
CREATE UNIQUE INDEX incident_embedding_past_idx ON knowledge.incident_embedding (tenant_id, subject_key, provider_key) WHERE subject_kind = 'past_incident';
CREATE INDEX incident_embedding_kind_idx ON knowledge.incident_embedding (tenant_id, subject_kind, provider_key);
GRANT SELECT, INSERT ON knowledge.incident_embedding TO vigil_app;

CREATE TABLE knowledge.similarity_search_result (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  created_at timestamptz NOT NULL,
  incident_id uuid NOT NULL,
  run_id uuid,
  search_text varchar(2000) NOT NULL,
  provider_key varchar(64) NOT NULL CHECK (provider_key ~ '^[A-Za-z0-9._-]{1,64}$'),
  outcome varchar(40) NOT NULL CHECK (outcome IN ('match', 'no_match')),
  best_past_incident_key varchar(64) CHECK (best_past_incident_key ~ '^[A-Za-z0-9._-]{1,64}$'),
  score numeric(3,2),
  success_count bigint,
  fix_action_key varchar(64) CHECK (fix_action_key ~ '^[A-Za-z0-9._-]{1,64}$'),
  min_similarity numeric(3,2) NOT NULL,
  searched_at timestamptz NOT NULL,
  CHECK (outcome <> 'match' OR (best_past_incident_key IS NOT NULL AND score IS NOT NULL AND success_count IS NOT NULL))
);
CREATE INDEX similarity_search_result_incident_idx ON knowledge.similarity_search_result (tenant_id, incident_id);
GRANT SELECT, INSERT ON knowledge.similarity_search_result TO vigil_app;

CREATE TABLE knowledge.knowledge_settings (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  min_similarity numeric(3,2) NOT NULL CHECK (min_similarity BETWEEN 0 AND 1),
  runbook_limit bigint NOT NULL CHECK (runbook_limit >= 1),
  embedding_provider_key varchar(64) NOT NULL CHECK (embedding_provider_key ~ '^[A-Za-z0-9._-]{1,64}$'),
  embedding_state varchar(40) NOT NULL CHECK (embedding_state IN ('ready', 'reembedding')),
  reembedding_provider_key varchar(64) CHECK (reembedding_provider_key ~ '^[A-Za-z0-9._-]{1,64}$'),
  CHECK ((embedding_state = 'reembedding') = (reembedding_provider_key IS NOT NULL)),
  UNIQUE (tenant_id)
);
GRANT SELECT, INSERT, UPDATE ON knowledge.knowledge_settings TO vigil_app;
