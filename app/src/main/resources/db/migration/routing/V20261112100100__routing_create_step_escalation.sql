-- vigil-routing (PM-M10-05): the escalations each Escalation step execution recorded, by round
-- (technical table, CTX-D-071 (2)).
CREATE TABLE routing.step_escalation (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  created_at timestamptz NOT NULL,
  incident_id uuid NOT NULL,
  step_execution_id uuid NOT NULL,
  round bigint NOT NULL CHECK (round >= 1),
  escalation_id uuid NOT NULL,
  level bigint NOT NULL CHECK (level >= 1),
  at timestamptz NOT NULL,
  UNIQUE (tenant_id, step_execution_id, round)
);
GRANT SELECT, INSERT ON routing.step_escalation TO vigil_app;
