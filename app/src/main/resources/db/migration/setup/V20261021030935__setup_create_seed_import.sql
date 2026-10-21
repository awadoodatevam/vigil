-- vigil-setup (PM-M22): the seed import record (DM-E-82).
GRANT USAGE ON SCHEMA setup TO vigil_app;

CREATE TABLE setup.seed_import (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL UNIQUE,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  bundle_name varchar(100) NOT NULL CHECK (length(bundle_name) >= 1),
  bundle_version varchar(40) NOT NULL CHECK (length(bundle_version) >= 1),
  started_by_kind varchar(40) NOT NULL CHECK (started_by_kind IN ('user', 'module')),
  started_by_user_id uuid,
  started_by_module varchar(40),
  state varchar(40) NOT NULL CHECK (state IN ('running', 'succeeded', 'failed')),
  parts jsonb NOT NULL CHECK (jsonb_typeof(parts) = 'array'),
  finished_at timestamptz,
  CHECK ((started_by_kind = 'user' AND started_by_user_id IS NOT NULL AND started_by_module IS NULL) OR (started_by_kind = 'module' AND started_by_module IS NOT NULL AND started_by_user_id IS NULL))
);
GRANT SELECT, INSERT, UPDATE ON setup.seed_import TO vigil_app;
