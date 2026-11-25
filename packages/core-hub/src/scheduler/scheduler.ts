import type { ScheduledJobSpec } from "@neko/protocol";
import type { DatabaseSync } from "node:sqlite";

export interface DueJobFire {
  job: ScheduledJobSpec;
  fireKey: string;
  firedAt: number;
}

export class HybridScheduler {
  private readonly db: DatabaseSync;
  private timer: NodeJS.Timeout | null = null;

  constructor(db: DatabaseSync) {
    this.db = db;
  }

  upsertJob(spec: ScheduledJobSpec, nextRunAt: number | null = null): void {
    this.db
      .prepare(
        `INSERT INTO scheduled_jobs (id, name, spec_json, next_run_at, status)
         VALUES (?, ?, ?, ?, ?)
         ON CONFLICT(id) DO UPDATE SET
           name = excluded.name,
           spec_json = excluded.spec_json,
           next_run_at = COALESCE(excluded.next_run_at, scheduled_jobs.next_run_at),
           status = excluded.status`,
      )
      .run(spec.id, spec.name, JSON.stringify(spec), nextRunAt, spec.status);
  }

  pollDueJobs(now: number = Date.now()): DueJobFire[] {
    const rows = this.db
      .prepare(
        `SELECT id, spec_json, next_run_at, last_fire_key
         FROM scheduled_jobs
         WHERE status = 'active' AND next_run_at IS NOT NULL AND next_run_at <= ?`,
      )
      .all(now) as Array<{
      id: string;
      spec_json: string;
      next_run_at: number;
      last_fire_key: string | null;
    }>;

    const fires: DueJobFire[] = [];
    for (const row of rows) {
      const fireKey = `${row.id}:${row.next_run_at}`;
      if (row.last_fire_key === fireKey) {
        continue;
      }

      const spec = JSON.parse(row.spec_json) as ScheduledJobSpec;
      const nextSlot =
        spec.trigger.type === "interval"
          ? now + spec.trigger.everyMinutes * 60_000
          : null;

      this.db
        .prepare(
          `UPDATE scheduled_jobs
           SET last_run_at = ?, last_fire_key = ?, next_run_at = ?, fires_today = fires_today + 1
           WHERE id = ?`,
        )
        .run(now, fireKey, nextSlot, row.id);

      fires.push({ job: spec, fireKey, firedAt: now });
    }
    return fires;
  }

  start(intervalMs: number, onFire: (fire: DueJobFire) => void): void {
    if (this.timer) {
      return;
    }
    this.timer = setInterval(() => {
      for (const fire of this.pollDueJobs()) {
        onFire(fire);
      }
    }, intervalMs);
    this.timer.unref();
  }

  stop(): void {
    if (this.timer) {
      clearInterval(this.timer);
      this.timer = null;
    }
  }
}
