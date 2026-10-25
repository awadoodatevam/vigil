-- vigil-workflows (PM-M11): the workflow (DM-E-45) and its versions (DM-E-47).
GRANT USAGE ON SCHEMA workflows TO vigil_app;

CREATE TABLE workflows.workflow (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  name varchar(100) NOT NULL,
  active_version_id uuid NOT NULL,
  UNIQUE (tenant_id)
);
GRANT SELECT, INSERT, UPDATE ON workflows.workflow TO vigil_app;

CREATE TABLE workflows.workflow_version (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  created_at timestamptz NOT NULL,
  workflow_id uuid NOT NULL REFERENCES workflows.workflow (id),
  number bigint NOT NULL CHECK (number >= 1),
  content jsonb NOT NULL,
  origin varchar(40) NOT NULL CHECK (origin IN ('seed', 'draft', 'rollback')),
  origin_ref uuid,
  published_by_kind varchar(40) NOT NULL CHECK (published_by_kind IN ('user', 'module')),
  published_by_user_id uuid,
  published_by_module varchar(40),
  published_at timestamptz NOT NULL,
  change_list jsonb NOT NULL,
  UNIQUE (tenant_id, workflow_id, number),
  CHECK ((published_by_kind = 'user' AND published_by_user_id IS NOT NULL AND published_by_module IS NULL) OR (published_by_kind = 'module' AND published_by_module IS NOT NULL AND published_by_user_id IS NULL))
);
GRANT SELECT, INSERT ON workflows.workflow_version TO vigil_app;
