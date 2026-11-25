import { randomUUID } from "node:crypto";
import type {
  AgentProviderName,
  AgentThreadRecord,
  WorkerHeartbeatPayload,
  WorkerNodeInfo,
} from "@neko/protocol";
import type { DatabaseSync } from "node:sqlite";

export interface CreateThreadInput {
  parentThreadId?: string | null;
  nodeId: string;
  provider: AgentProviderName;
  title: string;
}

export class OrchestratorRegistry {
  private readonly db: DatabaseSync;

  constructor(db: DatabaseSync) {
    this.db = db;
  }

  registerWorker(info: WorkerNodeInfo): void {
    const now = Date.now();
    this.db
      .prepare(
        `INSERT INTO worker_nodes (
          node_id, display_name, tags_json, providers_json, tailscale_ip, last_heartbeat_at, status
        ) VALUES (?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(node_id) DO UPDATE SET
          display_name = excluded.display_name,
          tags_json = excluded.tags_json,
          providers_json = excluded.providers_json,
          tailscale_ip = excluded.tailscale_ip,
          last_heartbeat_at = excluded.last_heartbeat_at,
          status = excluded.status`,
      )
      .run(
        info.nodeId,
        info.displayName,
        JSON.stringify(info.tags),
        JSON.stringify(info.providers),
        info.tailscaleIp ?? null,
        now,
        info.status ?? "online",
      );
  }

  recordHeartbeat(payload: WorkerHeartbeatPayload): void {
    const now = Date.now();
    this.db
      .prepare(
        `UPDATE worker_nodes
         SET last_heartbeat_at = ?, last_input_active_at = ?, metrics_json = ?, status = ?
         WHERE node_id = ?`,
      )
      .run(
        now,
        payload.lastInputActiveAt,
        JSON.stringify(payload.metrics ?? {}),
        payload.status ?? "online",
        payload.nodeId,
      );
  }

  listWorkers(): WorkerNodeInfo[] {
    const rows = this.db
      .prepare("SELECT * FROM worker_nodes ORDER BY node_id ASC")
      .all() as Array<Record<string, unknown>>;

    return rows.map((row) => ({
      nodeId: String(row.node_id),
      displayName: String(row.display_name),
      tags: JSON.parse(String(row.tags_json)) as string[],
      providers: JSON.parse(String(row.providers_json)) as AgentProviderName[],
      tailscaleIp: row.tailscale_ip === null ? undefined : String(row.tailscale_ip),
      status: String(row.status) as WorkerNodeInfo["status"],
    }));
  }

  createThread(input: CreateThreadInput): AgentThreadRecord {
    const now = Date.now();
    const record: AgentThreadRecord = {
      threadId: randomUUID(),
      parentThreadId: input.parentThreadId ?? null,
      nodeId: input.nodeId,
      provider: input.provider,
      title: input.title,
      status: "queued",
      summary: null,
      pendingApproval: null,
      createdAt: now,
      updatedAt: now,
    };

    this.db
      .prepare(
        `INSERT INTO agent_threads (
          thread_id, parent_thread_id, node_id, provider, title, status, summary, pending_approval_json, created_at, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, NULL, ?, ?)`,
      )
      .run(
        record.threadId,
        record.parentThreadId,
        record.nodeId,
        record.provider,
        record.title,
        record.status,
        record.summary,
        record.createdAt,
        record.updatedAt,
      );

    return record;
  }
}
