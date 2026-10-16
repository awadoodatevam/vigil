-- vigil-tenancy (PM-M01): tenants (DM-E-01) and tenant aliases (DM-E-83), both installation-level, and
-- tenant settings (DM-E-02).
GRANT USAGE ON SCHEMA tenancy TO vigil_app;

CREATE TABLE tenancy.tenant (
  id uuid PRIMARY KEY,
  name varchar(100) NOT NULL,
  state varchar(40) NOT NULL CHECK (state IN ('provisioning', 'ready', 'failed')),
  created_at timestamptz NOT NULL,
  created_by_kind varchar(40) NOT NULL CHECK (created_by_kind IN ('user', 'module')),
  created_by_user_id uuid,
  created_by_module varchar(40),
  ready_at timestamptz,
  CHECK ((created_by_kind = 'user' AND created_by_user_id IS NOT NULL AND created_by_module IS NULL) OR (created_by_kind = 'module' AND created_by_module IS NOT NULL AND created_by_user_id IS NULL))
);
GRANT SELECT, INSERT, UPDATE ON tenancy.tenant TO vigil_app;

CREATE TABLE tenancy.tenant_settings (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES tenancy.tenant (id),
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  customer_name varchar(100) NOT NULL,
  customer_logo bytea,
  customer_logo_media_type varchar(40) CHECK (customer_logo_media_type IN ('image/png', 'image/svg+xml')),
  environment_label varchar(100),
  product_display_name varchar(60) NOT NULL,
  updated_by_kind varchar(40) NOT NULL CHECK (updated_by_kind IN ('user', 'module')),
  updated_by_user_id uuid,
  updated_by_module varchar(40),
  UNIQUE (tenant_id),
  CHECK ((customer_logo IS NULL) = (customer_logo_media_type IS NULL)),
  CHECK ((updated_by_kind = 'user' AND updated_by_user_id IS NOT NULL AND updated_by_module IS NULL) OR (updated_by_kind = 'module' AND updated_by_module IS NOT NULL AND updated_by_user_id IS NULL))
);
GRANT SELECT, INSERT, UPDATE ON tenancy.tenant_settings TO vigil_app;

CREATE TABLE tenancy.tenant_alias (
  host_name varchar(253) NOT NULL,
  revision bigint NOT NULL,
  tenant_id uuid NOT NULL REFERENCES tenancy.tenant (id),
  set_at timestamptz NOT NULL,
  set_by varchar(40) NOT NULL,
  PRIMARY KEY (host_name, revision),
  CHECK (host_name = lower(host_name) AND revision >= 1)
);
CREATE INDEX tenant_alias_tenant_idx ON tenancy.tenant_alias (tenant_id);
GRANT SELECT, INSERT ON tenancy.tenant_alias TO vigil_app;
