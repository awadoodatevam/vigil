-- vigil-servicemap (PM-M07): generations (DM-E-24) and their services, components, identity keys and dependencies (DM-E-25 to DM-E-28). Immutable (AR-P-06).
GRANT USAGE ON SCHEMA servicemap TO vigil_app;

CREATE TABLE servicemap.generation (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  created_at timestamptz NOT NULL,
  generation bigint NOT NULL CHECK (generation >= 1),
  imported_by_kind varchar(40) NOT NULL CHECK (imported_by_kind IN ('user', 'module')),
  imported_by_user_id uuid,
  imported_by_module varchar(40),
  file_name varchar(255) NOT NULL CHECK (length(file_name) >= 1),
  file_hash varchar(128) NOT NULL,
  counts jsonb NOT NULL,
  UNIQUE (tenant_id, generation),
  CHECK ((imported_by_kind = 'user' AND imported_by_user_id IS NOT NULL AND imported_by_module IS NULL) OR (imported_by_kind = 'module' AND imported_by_module IS NOT NULL AND imported_by_user_id IS NULL))
);
GRANT SELECT, INSERT ON servicemap.generation TO vigil_app;

CREATE TABLE servicemap.business_service (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  created_at timestamptz NOT NULL,
  generation bigint NOT NULL,
  service_key varchar(64) NOT NULL CHECK (service_key ~ '^[A-Za-z0-9._-]{1,64}$'),
  name varchar(100) NOT NULL,
  criticality varchar(40) CHECK (criticality IN ('critical', 'high', 'medium', 'low')),
  owner_team_key varchar(64) CHECK (owner_team_key ~ '^[A-Za-z0-9._-]{1,64}$'),
  component_keys jsonb NOT NULL,
  UNIQUE (tenant_id, generation, service_key)
);
GRANT SELECT, INSERT ON servicemap.business_service TO vigil_app;

CREATE TABLE servicemap.component (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  created_at timestamptz NOT NULL,
  generation bigint NOT NULL,
  component_key varchar(64) NOT NULL CHECK (component_key ~ '^[A-Za-z0-9._-]{1,64}$'),
  name varchar(100) NOT NULL,
  kind varchar(40) NOT NULL CHECK (kind IN ('server', 'application', 'database', 'queue', 'check')),
  criticality varchar(40) CHECK (criticality IN ('critical', 'high', 'medium', 'low')),
  owner_team_key varchar(64) CHECK (owner_team_key ~ '^[A-Za-z0-9._-]{1,64}$'),
  runs_on_key varchar(64) CHECK (runs_on_key ~ '^[A-Za-z0-9._-]{1,64}$'),
  target_key varchar(64) CHECK (target_key ~ '^[A-Za-z0-9._-]{1,64}$'),
  UNIQUE (tenant_id, generation, component_key)
);
GRANT SELECT, INSERT ON servicemap.component TO vigil_app;

CREATE TABLE servicemap.component_identity_key (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  created_at timestamptz NOT NULL,
  generation bigint NOT NULL,
  component_key varchar(64) NOT NULL CHECK (component_key ~ '^[A-Za-z0-9._-]{1,64}$'),
  source_kind varchar(40) NOT NULL CHECK (source_kind IN ('zabbix', 'alertmanager')),
  pair_name varchar(64) NOT NULL CHECK (pair_name ~ '^[A-Za-z0-9._-]{1,64}$'),
  pair_value varchar(200) NOT NULL,
  UNIQUE (tenant_id, generation, source_kind, pair_name, pair_value)
);
GRANT SELECT, INSERT ON servicemap.component_identity_key TO vigil_app;

CREATE TABLE servicemap.dependency (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  created_at timestamptz NOT NULL,
  generation bigint NOT NULL,
  from_component_key varchar(64) NOT NULL CHECK (from_component_key ~ '^[A-Za-z0-9._-]{1,64}$'),
  to_component_key varchar(64) NOT NULL CHECK (to_component_key ~ '^[A-Za-z0-9._-]{1,64}$'),
  UNIQUE (tenant_id, generation, from_component_key, to_component_key)
);
GRANT SELECT, INSERT ON servicemap.dependency TO vigil_app;
