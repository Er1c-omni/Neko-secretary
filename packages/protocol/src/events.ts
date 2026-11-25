import { randomUUID } from "node:crypto";

export type AssistantMode = "life" | "secretary";

export type TodayScript = "office" | "wfh" | "interview" | "holiday";

export type EventSource = "android" | "scheduler" | "webhook" | `pc:${string}`;

export type EventStatus = "pending" | "processed" | "ignored" | "failed";

export type EventRoute = "ignore" | "fast_path" | "wake_agent";

export type EventType =
  | "user.voice_transcript"
  | "user.text_message"
  | "user.action_reply"
  | "share.screenshot"
  | "share.text_or_url"
  | "sensor.wifi_changed"
  | "sensor.activity_snapshot"
  | "sensor.usage_distraction"
  | "sensor.notification_external"
  | "pc.activity_heartbeat"
  | "pc.worker.registered"
  | "pc.worker.log_alert"
  | "scheduler.tick"
  | "state.script_selected"
  | "state.mode_switched"
  | "agent.thread.updated"
  | "agent.approval.requested"
  | "agent.approval.resolved";

export interface EventEnvelope<TPayload = Record<string, unknown>> {
  id: string;
  idempotencyKey: string;
  type: EventType;
  source: EventSource;
  ts: number;
  payload: TPayload;
}

export function createEventEnvelope<TPayload = Record<string, unknown>>(
  input: Omit<EventEnvelope<TPayload>, "id" | "ts"> & {
    id?: string;
    ts?: number;
  },
): EventEnvelope<TPayload> {
  return {
    id: input.id ?? randomUUID(),
    idempotencyKey: input.idempotencyKey,
    type: input.type,
    source: input.source,
    ts: input.ts ?? Date.now(),
    payload: input.payload,
  };
}
