// The stored content of the seeded workflow version 1 (DD-F-06, with the DM Section 9 parameter names that
// WorkflowPartCheck.stored writes), for the tests of the run canvas.
import type { RunContent } from "../canvas/graph";

export const CONTENT: RunContent = {
  steps: [
    {
      step_id: "s-trigger",
      type: "trigger",
      label: "Incident created or reopened",
      position: {
        x: 0,
        y: 300,
      },
      parameters: {
        event: "incident_created_or_reopened",
      },
    },
    {
      step_id: "s-severity",
      type: "condition",
      label: "Severity",
      position: {
        x: 220,
        y: 300,
      },
      parameters: {
        branches: [
          {
            label: "p1",
            comparisons: [
              {
                field: "severity",
                operator: "equals",
                value: "P1",
              },
            ],
          },
        ],
      },
    },
    {
      step_id: "s-diagnose",
      type: "diagnostic",
      label: "Check the DB server",
      position: {
        x: 440,
        y: 100,
      },
      parameters: {
        diagnostics: ["disk_usage", "largest_folders", "disk_growth"],
        target: "hypothesis_component",
      },
    },
    {
      step_id: "s-investigate",
      type: "ai_task",
      label: "AI investigation",
      position: {
        x: 660,
        y: 100,
      },
      parameters: {
        task: "SL-T-1",
      },
    },
    {
      step_id: "s-summarise",
      type: "ai_task",
      label: "AI summary",
      position: {
        x: 880,
        y: 100,
      },
      parameters: {
        task: "SL-T-2",
      },
    },
    {
      step_id: "s-ticket",
      type: "ticket",
      label: "Create Jira ticket",
      position: {
        x: 1100,
        y: 100,
      },
      parameters: {
        operation: "create",
      },
    },
    {
      step_id: "s-notify",
      type: "notification",
      label: "Notify owner, informed and NOC L1",
      position: {
        x: 1320,
        y: 100,
      },
      parameters: {
        recipients: ["owner_team", "informed_parties", "named_teams"],
        named_teams: ["noc-l1"],
      },
    },
    {
      step_id: "s-wait-ack",
      type: "escalation",
      label: "Wait for acknowledgement",
      position: {
        x: 1540,
        y: 100,
      },
      parameters: {
        mode: "wait_for_acknowledgement",
        timer: 600,
      },
    },
    {
      step_id: "s-similar",
      type: "ai_task",
      label: "Find similar past incident",
      position: {
        x: 1760,
        y: 100,
      },
      parameters: {
        task: "SL-T-3",
      },
    },
    {
      step_id: "s-approve",
      type: "approval",
      label: "DB Ops approval",
      position: {
        x: 1980,
        y: 100,
      },
      parameters: {
        eligible_teams: ["db-ops"],
        expiry: 900,
      },
    },
    {
      step_id: "s-action",
      type: "action",
      label: "Run approved fix",
      position: {
        x: 2200,
        y: 100,
      },
      parameters: {},
    },
    {
      step_id: "s-recover",
      type: "recovery_check",
      label: "Verify recovery",
      position: {
        x: 2420,
        y: 100,
      },
      parameters: {
        max_wait: 300,
      },
    },
    {
      step_id: "s-resolve",
      type: "ticket",
      label: "Resolve Jira ticket",
      position: {
        x: 2640,
        y: 0,
      },
      parameters: {
        operation: "resolve",
      },
    },
    {
      step_id: "s-escalate",
      type: "escalation",
      label: "Escalate with evidence",
      position: {
        x: 2640,
        y: 200,
      },
      parameters: {
        mode: "escalate_now",
      },
    },
    {
      step_id: "s-summarise-2",
      type: "ai_task",
      label: "AI summary",
      position: {
        x: 440,
        y: 500,
      },
      parameters: {
        task: "SL-T-2",
      },
    },
    {
      step_id: "s-ticket-2",
      type: "ticket",
      label: "Create Jira ticket",
      position: {
        x: 660,
        y: 500,
      },
      parameters: {
        operation: "create",
      },
    },
    {
      step_id: "s-notify-2",
      type: "notification",
      label: "Notify owner team",
      position: {
        x: 880,
        y: 500,
      },
      parameters: {
        recipients: ["owner_team"],
      },
    },
    {
      step_id: "s-recover-2",
      type: "recovery_check",
      label: "Verify recovery",
      position: {
        x: 1100,
        y: 500,
      },
      parameters: {
        max_wait: 14400,
      },
    },
    {
      step_id: "s-resolve-2",
      type: "ticket",
      label: "Resolve Jira ticket",
      position: {
        x: 1320,
        y: 500,
      },
      parameters: {
        operation: "resolve",
      },
    },
  ],
  connections: [
    {
      from_step_id: "s-trigger",
      from_output: "next",
      to_step_id: "s-severity",
    },
    {
      from_step_id: "s-severity",
      from_output: "p1",
      to_step_id: "s-diagnose",
    },
    {
      from_step_id: "s-severity",
      from_output: "otherwise",
      to_step_id: "s-summarise-2",
    },
    {
      from_step_id: "s-diagnose",
      from_output: "done",
      to_step_id: "s-investigate",
    },
    {
      from_step_id: "s-diagnose",
      from_output: "failed",
      to_step_id: "s-investigate",
    },
    {
      from_step_id: "s-investigate",
      from_output: "done",
      to_step_id: "s-summarise",
    },
    {
      from_step_id: "s-investigate",
      from_output: "unavailable",
      to_step_id: "s-summarise",
    },
    {
      from_step_id: "s-summarise",
      from_output: "done",
      to_step_id: "s-ticket",
    },
    {
      from_step_id: "s-summarise",
      from_output: "unavailable",
      to_step_id: "s-ticket",
    },
    {
      from_step_id: "s-ticket",
      from_output: "done",
      to_step_id: "s-notify",
    },
    {
      from_step_id: "s-ticket",
      from_output: "failed",
      to_step_id: "s-notify",
    },
    {
      from_step_id: "s-notify",
      from_output: "sent",
      to_step_id: "s-wait-ack",
    },
    {
      from_step_id: "s-notify",
      from_output: "failed",
      to_step_id: "s-wait-ack",
    },
    {
      from_step_id: "s-wait-ack",
      from_output: "next",
      to_step_id: "s-similar",
    },
    {
      from_step_id: "s-similar",
      from_output: "done",
      to_step_id: "s-approve",
    },
    {
      from_step_id: "s-similar",
      from_output: "unavailable",
      to_step_id: "s-approve",
    },
    {
      from_step_id: "s-approve",
      from_output: "approved",
      to_step_id: "s-action",
    },
    {
      from_step_id: "s-approve",
      from_output: "rejected",
      to_step_id: "s-escalate",
    },
    {
      from_step_id: "s-approve",
      from_output: "expired",
      to_step_id: "s-escalate",
    },
    {
      from_step_id: "s-action",
      from_output: "succeeded",
      to_step_id: "s-recover",
    },
    {
      from_step_id: "s-action",
      from_output: "failed",
      to_step_id: "s-recover",
    },
    {
      from_step_id: "s-recover",
      from_output: "recovered",
      to_step_id: "s-resolve",
    },
    {
      from_step_id: "s-recover",
      from_output: "not_recovered",
      to_step_id: "s-escalate",
    },
    {
      from_step_id: "s-summarise-2",
      from_output: "done",
      to_step_id: "s-ticket-2",
    },
    {
      from_step_id: "s-summarise-2",
      from_output: "unavailable",
      to_step_id: "s-ticket-2",
    },
    {
      from_step_id: "s-ticket-2",
      from_output: "done",
      to_step_id: "s-notify-2",
    },
    {
      from_step_id: "s-ticket-2",
      from_output: "failed",
      to_step_id: "s-notify-2",
    },
    {
      from_step_id: "s-notify-2",
      from_output: "sent",
      to_step_id: "s-recover-2",
    },
    {
      from_step_id: "s-notify-2",
      from_output: "failed",
      to_step_id: "s-recover-2",
    },
    {
      from_step_id: "s-recover-2",
      from_output: "recovered",
      to_step_id: "s-resolve-2",
    },
    {
      from_step_id: "s-recover-2",
      from_output: "not_recovered",
      to_step_id: "s-escalate",
    },
  ],
};
