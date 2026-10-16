-- vigil-identity (PM-M02): users, teams, memberships, permission grants (DM-E-03 to DM-E-06,
-- configuration) and login sessions (DM-E-07, stateful).
GRANT USAGE ON SCHEMA identity TO vigil_app;

CREATE TABLE identity."user" (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  username varchar(64) NOT NULL CHECK (username ~ '^[A-Za-z0-9._-]{1,64}$'),
  display_name varchar(100) NOT NULL,
  password_hash varchar(255) NOT NULL,
  active boolean NOT NULL,
  UNIQUE (tenant_id, username)
);
GRANT SELECT, INSERT, UPDATE ON identity."user" TO vigil_app;

CREATE TABLE identity.team (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  team_key varchar(64) NOT NULL CHECK (team_key ~ '^[A-Za-z0-9._-]{1,64}$'),
  name varchar(100) NOT NULL,
  active boolean NOT NULL,
  UNIQUE (tenant_id, team_key)
);
GRANT SELECT, INSERT, UPDATE ON identity.team TO vigil_app;

CREATE TABLE identity.team_membership (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  team_id uuid NOT NULL REFERENCES identity.team (id),
  user_id uuid NOT NULL REFERENCES identity."user" (id),
  active boolean NOT NULL
);
CREATE UNIQUE INDEX team_membership_active_idx ON identity.team_membership (tenant_id, team_id, user_id) WHERE active;
GRANT SELECT, INSERT, UPDATE ON identity.team_membership TO vigil_app;

CREATE TABLE identity.permission_grant (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  user_id uuid NOT NULL REFERENCES identity."user" (id),
  permission varchar(40) NOT NULL CHECK (permission IN ('view', 'acknowledge', 'approve_action',
    'manage_setup', 'manage_routing', 'manage_ai_settings', 'rerun_ai_investigation', 'edit_workflow',
    'run_test_replay', 'publish_workflow')),
  active boolean NOT NULL
);
CREATE UNIQUE INDEX permission_grant_active_idx ON identity.permission_grant (tenant_id, user_id, permission) WHERE active;
GRANT SELECT, INSERT, UPDATE ON identity.permission_grant TO vigil_app;

CREATE TABLE identity.login_session (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  user_id uuid NOT NULL REFERENCES identity."user" (id),
  token_hash varchar(255) NOT NULL,
  state varchar(40) NOT NULL CHECK (state IN ('active', 'ended')),
  expires_at timestamptz NOT NULL,
  last_activity_at timestamptz NOT NULL,
  ended_at timestamptz,
  end_reason varchar(40) CHECK (end_reason IN ('logout', 'expired')),
  UNIQUE (tenant_id, token_hash)
);
CREATE INDEX login_session_tenant_state_idx ON identity.login_session (tenant_id, state);
GRANT SELECT, INSERT, UPDATE ON identity.login_session TO vigil_app;
