import type { AssistantMode } from "./events.ts";

export type SchedulerTrigger =
  | { type: "time"; time: string; days?: string[] }
  | { type: "interval"; everyMinutes: number }
  | { type: "event"; event: string }
  | { type: "location"; ssid?: string; geofenceId?: string; transition: "enter" | "exit" }
  | { type: "condition"; expr: string }
  | { type: "deadline"; targetId: string; leadMinutes: number[] }
  | { type: "dependency"; dependsOnJobId: string }
  | { type: "composite"; all: SchedulerTrigger[] };

export interface JobPolicy {
  quietHours: [string, string];
  deferWhenBusy: boolean;
  maxFiresPerDay: number;
  retryOnOfflineTtlMin: number;
}

export interface ScheduledJobSpec {
  id: string;
  version: number;
  name: string;
  modeScope: AssistantMode[];
  noAgentScript: boolean;
  timezone: string;
  trigger: SchedulerTrigger;
  conditions?: Record<string, unknown>;
  fallback?: SchedulerTrigger;
  policy: JobPolicy;
  action: {
    type: "proactive_notify" | "run_skill" | "dispatch_worker";
    intent: string;
    channel: "live2d_bubble" | "system_notification" | "silent_log";
    quickReplies?: string[];
  };
  status: "active" | "paused" | "completed";
}
