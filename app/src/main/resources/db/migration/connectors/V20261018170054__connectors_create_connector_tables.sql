-- vigil-connectors (PM-M04): credential references (DM-E-11) and connector instances (DM-E-10).
GRANT USAGE ON SCHEMA connectors TO vigil_app;

CREATE TABLE connectors.credential_reference (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  purpose varchar(40) NOT NULL CHECK (purpose IN ('webhook_token', 'external_api', 'target_diagnostic', 'target_action', 'smtp', 'webhook_url')),
  locator varchar(500) NOT NULL CHECK (locator ~ '^secret:[a-z0-9-]{1,64}$')
);
CREATE INDEX credential_reference_tenant_idx ON connectors.credential_reference (tenant_id);
GRANT SELECT, INSERT, UPDATE ON connectors.credential_reference TO vigil_app;

CREATE TABLE connectors.connector_instance (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  name varchar(100) NOT NULL CHECK (length(name) >= 1),
  kind varchar(64) NOT NULL CHECK (kind ~ '^[A-Za-z0-9._-]{1,64}$'),
  address varchar(500) NOT NULL CHECK (length(address) >= 1),
  settings jsonb NOT NULL CHECK (jsonb_typeof(settings) = 'object'),
  credential_ref_id uuid REFERENCES connectors.credential_reference (id),
  active boolean NOT NULL,
  updated_by_kind varchar(40) NOT NULL CHECK (updated_by_kind IN ('user', 'module')),
  updated_by_user_id uuid,
  updated_by_module varchar(40),
  CHECK ((updated_by_kind = 'user' AND updated_by_user_id IS NOT NULL AND updated_by_module IS NULL) OR (updated_by_kind = 'module' AND updated_by_module IS NOT NULL AND updated_by_user_id IS NULL))
);
CREATE INDEX connector_instance_tenant_idx ON connectors.connector_instance (tenant_id);
GRANT SELECT, INSERT, UPDATE ON connectors.connector_instance TO vigil_app;
