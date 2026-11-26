import { randomUUID } from "node:crypto";
import type { ScheduledJobSpec } from "@neko/protocol";
import type { DatabaseSync } from "node:sqlite";

export interface DueJobFire {
  job: ScheduledJobSpec;
  fireKey: string;
  slotTs: number;
  firedAt: number;
}

export interface PersistedJobRow {
  spec: ScheduledJobSpec;
  nextRunAt: number | null;
  lastRunAt: number | null;
  lastFireKey: string | null;
  firesToday: number;
  status: string;
}

export interface RecordFeedbackInput {
  jobId: string | null;
  intent: string;
  decisionScore: number;
  userAction: string;
  contextSnapshot?: Record<string, unknown>;
  latencySec?: number | null;
  now?: number;
}

export interface ProactiveFeedbackRecord {
  id: string;
  jobId: string | null;
  intent: string;
  decisionScore: number;
  userAction: string;
  latencySec: number | null;
  createdAt: number;
}

const WEEKDAY_MAP: Record<string, number> = {
  sun: 0,
  mon: 1,
  tue: 2,
  wed: 3,
  thu: 4,
  fri: 5,
  sat: 6,
};

function parseHm(hm: string): { hour: number; minute: number } | null {
  const match = /^(\d{1,2}):(\d{2})$/.exec(hm.trim());
  if (!match) return null;
  const hour = Number(match[1]);
  const minute = Number(match[2]);
  if (hour < 0 || hour > 23 || minute < 0 || minute > 59) return null;
  return { hour, minute };
}

function getLocalClock(ts: number, timeZone?: string): {
  year: number;
  month: number;
  day: number;
  weekday: number;
  hour: number;
  minute: number;
} {
  try {
    const fmt = new Intl.DateTimeFormat("en-US", {
      timeZone: timeZone || undefined,
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
      weekday: "short",
      hour: "2-digit",
      minute: "2-digit",
      hourCycle: "h23",
    });
    const parts = fmt.formatToParts(new Date(ts));
    const lookup = (type: string): string => parts.find((p) => p.type === type)?.value ?? "0";
    const wd = lookup("weekday").slice(0, 3).toLowerCase();
    return {
      year: Number(lookup("year")),
      month: Number(lookup("month")),
      day: Number(lookup("day")),
      weekday: WEEKDAY_MAP[wd] ?? new Date(ts).getDay(),
      hour: Number(lookup("hour")) % 24,
      minute: Number(lookup("minute")),
    };
  } catch {
    const d = new Date(ts);
    return {
      year: d.getFullYear(),
      month: d.getMonth() + 1,
      day: d.getDate(),
      weekday: d.getDay(),
      hour: d.getHours(),
      minute: d.getMinutes(),
    };
  }
}

export function isInQuietHours(
  now: number,
  quietHours: [string, string],
  timeZone?: string,
): boolean {
  const start = parseHm(quietHours[0]);
  const end = parseHm(quietHours[1]);
  if (!start || !end) return false;
  const clock = getLocalClock(now, timeZone);
  const currentMin = clock.hour * 60 + clock.minute;
  const startMin = start.hour * 60 + start.minute;
  const endMin = end.hour * 60 + end.minute;
  if (startMin === endMin) return false;
  if (startMin < endMin) {
    return currentMin >= startMin && currentMin < endMin;
  }
  // Crosses midnight (e.g. 23:00 -> 07:30)
  return currentMin >= startMin || currentMin < endMin;
}

export function computeNextRunAt(spec: ScheduledJobSpec, fromMs: number): number | null {
  if (spec.trigger.type === "interval") {
    const mins = Math.max(1, spec.trigger.everyMinutes);
    return fromMs + mins * 60_000;
  }
  if (spec.trigger.type === "time") {
    const hm = parseHm(spec.trigger.time);
    if (!hm) return null;
    const allowedDays =
      spec.trigger.days && spec.trigger.days.length > 0
        ? new Set(
            spec.trigger.days
              .map((d) => WEEKDAY_MAP[d.slice(0, 3).toLowerCase()])
              .filter((n): n is number => n !== undefined),
          )
        : null;

    // Search forward up to 8 days in 1-minute increments? No, jump day-by-day using local clock diff!
    const base = new Date(fromMs);
    for (let dayOffset = 0; dayOffset <= 8; dayOffset++) {
      const candidateApprox = base.getTime() + dayOffset * 86_400_000;
      const clock = getLocalClock(candidateApprox, spec.timezone);
      if (allowedDays && !allowedDays.has(clock.weekday)) {
        continue;
      }
      const deltaMinutes = hm.hour * 60 + hm.minute - (clock.hour * 60 + clock.minute);
      const candidateTs =
        candidateApprox + deltaMinutes * 60_000 - (candidateApprox % 60_000);
      if (candidateTs > fromMs) {
        return candidateTs;
      }
    }
  }
  return null;
}

function isSameCalendarDay(a: number | null, b: number, timeZone?: string): boolean {
  if (a === null) return false;
  const ca = getLocalClock(a, timeZone);
  const cb = getLocalClock(b, timeZone);
  return ca.year === cb.year && ca.month === cb.month && ca.day === cb.day;
}

/**
 * Zero-token fast-path parser for common recurring reminder voice/text requests
 * (e.g. "每小时提醒我喝水", "每隔30分钟提醒我喝水", "每2小时提醒我站起来").
 */
export function parseQuickReminderCommand(
  text: string,
  now: number = Date.now(),
): { spec: ScheduledJobSpec; nextRunAt: number; confirmReply: string } | null {
  const clean = text.trim();
  if (!/(?:提醒|叫我|喊我)/.test(clean)) return null;

  let everyMinutes: number | null = null;
  const minMatch = clean.match(/每(?:隔)?\s*(\d+)\s*分钟/);
  const hourNumMatch = clean.match(/每(?:隔)?\s*(\d+)\s*(?:个)?小时/);
  if (minMatch) {
    everyMinutes = Math.max(1, Number(minMatch[1]));
  } else if (hourNumMatch) {
    everyMinutes = Math.max(1, Number(hourNumMatch[1]) * 60);
  } else if (/每(?:个)?小时/.test(clean)) {
    everyMinutes = 60;
  }

  if (everyMinutes === null) return null;

  const isWater = /喝水|补水|饮水/.test(clean);
  const isStandup = /久坐|站起来|活动|拉伸/.test(clean);
  const intent = isWater ? "water_reminder" : isStandup ? "sedentary_alert" : "generic_reply";
  const jobId = isWater
    ? "job_water_hourly"
    : isStandup
      ? "job_standup_interval"
      : `job_custom_${everyMinutes}m`;
  const name = isWater
    ? `每 ${everyMinutes} 分钟喝水提醒`
    : isStandup
      ? `每 ${everyMinutes} 分钟活动提醒`
      : clean.slice(0, 32);

  const spec: ScheduledJobSpec = {
    id: jobId,
    version: 1,
    name,
    modeScope: ["life", "secretary"],
    noAgentScript: true,
    timezone: "Asia/Shanghai",
    trigger: { type: "interval", everyMinutes },
    policy: {
      quietHours: ["23:00", "07:30"],
      deferWhenBusy: true,
      maxFiresPerDay: 16,
      retryOnOfflineTtlMin: 20,
    },
    action: {
      type: "proactive_notify",
      intent,
      channel: "live2d_bubble",
      quickReplies: isWater ? ["喝完啦", "5m后"] : ["好哒", "稍后提醒"],
    },
    status: "active",
  };

  const nextRunAt = now + everyMinutes * 60_000;
  const unitLabel = everyMinutes % 60 === 0 ? `${everyMinutes / 60} 小时` : `${everyMinutes} 分钟`;
  const confirmReply = isWater
    ? `好哒！已为你设定每 ${unitLabel} 提醒喝水，到点我会抱着水杯弹出来提醒你哦~`
    : `已设定「${name}」（每 ${unitLabel} 触发一次）。`;

  return { spec, nextRunAt, confirmReply };
}

export class HybridScheduler {
  private readonly db: DatabaseSync;
  private timer: NodeJS.Timeout | null = null;

  constructor(db: DatabaseSync) {
    this.db = db;
  }

  upsertJob(spec: ScheduledJobSpec, nextRunAt: number | null = null): void {
    const resolvedNext = nextRunAt ?? computeNextRunAt(spec, Date.now());
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
      .run(spec.id, spec.name, JSON.stringify(spec), resolvedNext, spec.status);
  }

  listJobs(): PersistedJobRow[] {
    const rows = this.db
      .prepare(
        `SELECT spec_json, next_run_at, last_run_at, last_fire_key, fires_today, status
         FROM scheduled_jobs
         ORDER BY COALESCE(next_run_at, 9999999999999) ASC`,
      )
      .all() as Array<{
      spec_json: string;
      next_run_at: number | null;
      last_run_at: number | null;
      last_fire_key: string | null;
      fires_today: number;
      status: string;
    }>;

    return rows.map((row) => ({
      spec: JSON.parse(row.spec_json) as ScheduledJobSpec,
      nextRunAt: row.next_run_at,
      lastRunAt: row.last_run_at,
      lastFireKey: row.last_fire_key,
      firesToday: row.fires_today,
      status: row.status,
    }));
  }

  snoozeJob(jobId: string, delayMinutes: number, now: number = Date.now()): number | null {
    const row = this.db
      .prepare("SELECT id FROM scheduled_jobs WHERE id = ?")
      .get(jobId) as { id: string } | undefined;
    if (!row) return null;
    const snoozedUntil = now + Math.max(1, delayMinutes) * 60_000;
    this.db
      .prepare("UPDATE scheduled_jobs SET next_run_at = ?, status = 'active' WHERE id = ?")
      .run(snoozedUntil, jobId);
    return snoozedUntil;
  }

  pollDueJobs(now: number = Date.now()): DueJobFire[] {
    const rows = this.db
      .prepare(
        `SELECT id, spec_json, next_run_at, last_run_at, last_fire_key, fires_today
         FROM scheduled_jobs
         WHERE status = 'active' AND next_run_at IS NOT NULL AND next_run_at <= ?`,
      )
      .all(now) as Array<{
      id: string;
      spec_json: string;
      next_run_at: number;
      last_run_at: number | null;
      last_fire_key: string | null;
      fires_today: number;
    }>;

    const fires: DueJobFire[] = [];
    for (const row of rows) {
      const fireKey = `${row.id}:${row.next_run_at}`;
      if (row.last_fire_key === fireKey) {
        continue;
      }

      const spec = JSON.parse(row.spec_json) as ScheduledJobSpec;
      const nextSlot = computeNextRunAt(spec, now);

      // Reboot catch-up guard: drop stale missed slots older than retryOnOfflineTtlMin
      const ttlMs = Math.max(1, spec.policy.retryOnOfflineTtlMin || 30) * 60_000;
      if (now - row.next_run_at > ttlMs) {
        this.db
          .prepare("UPDATE scheduled_jobs SET next_run_at = ?, last_fire_key = ? WHERE id = ?")
          .run(nextSlot, fireKey, row.id);
        continue;
      }

      // Reset daily fire counter on calendar day rollover
      const firesToday = isSameCalendarDay(row.last_run_at, now, spec.timezone)
        ? row.fires_today
        : 0;

      // Quiet hours & daily cap guard (L0 deadline alerts or deferWhenBusy=false bypass quiet hours)
      const isCritical = spec.action.intent === "deadline_alert";
      const quiet =
        !isCritical &&
        spec.policy.deferWhenBusy &&
        isInQuietHours(now, spec.policy.quietHours, spec.timezone);
      const capped = !isCritical && firesToday >= spec.policy.maxFiresPerDay;

      if (quiet || capped) {
        this.db
          .prepare("UPDATE scheduled_jobs SET next_run_at = ?, last_fire_key = ? WHERE id = ?")
          .run(nextSlot, fireKey, row.id);
        continue;
      }

      this.db
        .prepare(
          `UPDATE scheduled_jobs
           SET last_run_at = ?, last_fire_key = ?, next_run_at = ?, fires_today = ?
           WHERE id = ?`,
        )
        .run(now, fireKey, nextSlot, firesToday + 1, row.id);

      fires.push({ job: spec, fireKey, slotTs: row.next_run_at, firedAt: now });
    }
    return fires;
  }

  recordFeedback(input: RecordFeedbackInput): ProactiveFeedbackRecord {
    const record: ProactiveFeedbackRecord = {
      id: randomUUID(),
      jobId: input.jobId,
      intent: input.intent,
      decisionScore: input.decisionScore,
      userAction: input.userAction,
      latencySec: input.latencySec ?? null,
      createdAt: input.now ?? Date.now(),
    };

    this.db
      .prepare(
        `INSERT INTO proactive_feedback (
          id, job_id, intent, decision_score, context_snapshot_json, user_action, latency_sec, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
      )
      .run(
        record.id,
        record.jobId,
        record.intent,
        record.decisionScore,
        JSON.stringify(input.contextSnapshot ?? {}),
        record.userAction,
        record.latencySec,
        record.createdAt,
      );

    return record;
  }

  listFeedback(limit: number = 20): ProactiveFeedbackRecord[] {
    const rows = this.db
      .prepare(
        `SELECT id, job_id, intent, decision_score, user_action, latency_sec, created_at
         FROM proactive_feedback
         ORDER BY created_at DESC
         LIMIT ?`,
      )
      .all(limit) as Array<{
      id: string;
      job_id: string | null;
      intent: string;
      decision_score: number;
      user_action: string;
      latency_sec: number | null;
      created_at: number;
    }>;

    return rows.map((r) => ({
      id: r.id,
      jobId: r.job_id,
      intent: r.intent,
      decisionScore: r.decision_score,
      userAction: r.user_action,
      latencySec: r.latency_sec,
      createdAt: r.created_at,
    }));
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
