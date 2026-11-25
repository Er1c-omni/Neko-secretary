import type { EventEnvelope, EventRoute, EventType } from "@neko/protocol";
import type { DatabaseSync } from "node:sqlite";

export interface IngestResult {
  accepted: boolean;
  deduplicated: boolean;
  route: EventRoute;
}

const FAST_PATH_TYPES = new Set<EventType>([
  "scheduler.tick",
  "state.script_selected",
  "state.mode_switched",
  "sensor.wifi_changed",
  "pc.activity_heartbeat",
  "pc.worker.registered",
  "user.action_reply",
]);

const WAKE_AGENT_TYPES = new Set<EventType>([
  "user.voice_transcript",
  "user.text_message",
  "share.screenshot",
  "share.text_or_url",
  "sensor.usage_distraction",
  "sensor.notification_external",
  "pc.worker.log_alert",
  "agent.approval.requested",
]);

export class EventBus {
  private readonly db: DatabaseSync;

  constructor(db: DatabaseSync) {
    this.db = db;
  }

  routeEvent(envelope: EventEnvelope): EventRoute {
    if (FAST_PATH_TYPES.has(envelope.type)) {
      return "fast_path";
    }
    if (WAKE_AGENT_TYPES.has(envelope.type)) {
      return "wake_agent";
    }
    return "ignore";
  }

  ingest(envelope: EventEnvelope): IngestResult {
    const existing = this.db
      .prepare("SELECT route FROM events WHERE idempotency_key = ?")
      .get(envelope.idempotencyKey) as { route: EventRoute | null } | undefined;

    if (existing) {
      return {
        accepted: true,
        deduplicated: true,
        route: existing.route ?? "ignore",
      };
    }

    const route = this.routeEvent(envelope);
    const status = route === "ignore" ? "ignored" : "pending";

    this.db
      .prepare(
        `INSERT INTO events (id, idempotency_key, type, source, payload_json, status, route, created_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
      )
      .run(
        envelope.id,
        envelope.idempotencyKey,
        envelope.type,
        envelope.source,
        JSON.stringify(envelope.payload),
        status,
        route,
        envelope.ts,
      );

    return {
      accepted: true,
      deduplicated: false,
      route,
    };
  }
}
