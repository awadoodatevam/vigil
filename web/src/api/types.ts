// Names for the schemas of the generated contract types (BP-R-13).
import type { components } from "./generated/vigil-api";

type Schemas = components["schemas"];

export type TenantSettings = Schemas["TenantSettings"];
export type User = Schemas["User"];
export type Permission = User["permissions"][number];
export type AlertStrip = Schemas["AlertStrip"];
export type IncidentPage = Schemas["IncidentPage"];
export type IncidentRow = Schemas["IncidentRow"];
export type ErrorCode = Schemas["ErrorContent"]["code"];
export type IncidentHeader = Schemas["IncidentHeader"];
export type MergedIncident = Schemas["MergedIncident"];
export type IncidentAlerts = Schemas["IncidentAlerts"];
export type AlertGroup = IncidentAlerts["groups"][number];
export type MemberAlert = AlertGroup["alerts"][number];
export type Reason = Schemas["Reason"];
export type EvidenceLines = Schemas["EvidenceLines"];
export type EvidenceLine = Schemas["EvidenceLine"];
export type InvestigationSection = Schemas["InvestigationSection"];
export type ResponseSection = Schemas["ResponseSection"];
export type RecoverySection = Schemas["RecoverySection"];
export type Verification = RecoverySection["verifications"][number];
export type LatestRun = Schemas["LatestRun"];
export type LiveRun = NonNullable<Schemas["LiveRun"]>;
export type StepExecution = LiveRun["step_executions"][number];
export type StepType = StepExecution["step_type"];
export type IncidentTimeline = Schemas["IncidentTimeline"];
export type TimelineEntry = IncidentTimeline["entries"][number];
export type NotifiedTeams = Schemas["NotifiedTeams"];
export type TeamName = Schemas["TeamName"];
export type AcknowledgePage = Schemas["AcknowledgePage"];
export type NewAcknowledgement = Schemas["NewAcknowledgement"];
export type ApprovalPage = Schemas["ApprovalPage"];
export type NewDecision = Schemas["NewDecision"];
export type DecisionRequest = Schemas["DecisionRequest"];
export type Refusal = NonNullable<Schemas["Refusal"]>;
