import { randomUUID } from "node:crypto";
import type {
  MemoryCategory,
  MemoryRecord,
  MemorySourceType,
  ProspectiveItem,
  ProspectiveKind,
  ProspectiveStatus,
  WorldStateEntry,
} from "@neko/protocol";
import type { DatabaseSync } from "node:sqlite";

export interface AddMemoryInput {
  category: MemoryCategory;
  title?: string | null;
  content: string;
  tags?: string[];
  sourceType: MemorySourceType;
  sourceRef?: string | null;
  confidence?: number;
  importance?: number;
  validUntil?: number | null;
}

export interface AddProspectiveInput {
  kind: ProspectiveKind;
  title: string;
  details?: Record<string, unknown>;
  dueAt?: number | null;
  recurrenceRule?: string | null;
  priority?: number;
}

export class MemoryRepository {
  private readonly db: DatabaseSync;

  constructor(db: DatabaseSync) {
    this.db = db;
  }

  upsertWorldState<T>(key: string, value: T, ttlSec: number | null = null): WorldStateEntry<T> {
    const now = Date.now();
    this.db
      .prepare(
        `INSERT INTO world_state (key, value_json, updated_at, ttl_sec)
         VALUES (?, ?, ?, ?)
         ON CONFLICT(key) DO UPDATE SET
           value_json = excluded.value_json,
           updated_at = excluded.updated_at,
           ttl_sec = excluded.ttl_sec`,
      )
      .run(key, JSON.stringify(value), now, ttlSec);

    return { key, value, updatedAt: now, ttlSec };
  }

  getWorldState<T>(key: string, now: number = Date.now()): WorldStateEntry<T> | null {
    const row = this.db
      .prepare("SELECT key, value_json, updated_at, ttl_sec FROM world_state WHERE key = ?")
      .get(key) as
      | { key: string; value_json: string; updated_at: number; ttl_sec: number | null }
      | undefined;

    if (!row) {
      return null;
    }

    if (row.ttl_sec !== null && now - row.updated_at > row.ttl_sec * 1000) {
      return null;
    }

    return {
      key: row.key,
      value: JSON.parse(row.value_json) as T,
      updatedAt: row.updated_at,
      ttlSec: row.ttl_sec,
    };
  }

  addMemory(input: AddMemoryInput): MemoryRecord {
    const now = Date.now();
    const record: MemoryRecord = {
      id: randomUUID(),
      category: input.category,
      title: input.title ?? null,
      content: input.content,
      tags: input.tags ?? [],
      sourceType: input.sourceType,
      sourceRef: input.sourceRef ?? null,
      confidence: input.confidence ?? 1.0,
      importance: input.importance ?? 0.5,
      validUntil: input.validUntil ?? null,
      createdAt: now,
      updatedAt: now,
      isDeleted: false,
    };

    this.db
      .prepare(
        `INSERT INTO memories (
          id, category, title, content, tags_json, source_type, source_ref,
          confidence, importance, valid_until, created_at, updated_at, is_deleted
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)`,
      )
      .run(
        record.id,
        record.category,
        record.title,
        record.content,
        JSON.stringify(record.tags),
        record.sourceType,
        record.sourceRef,
        record.confidence,
        record.importance,
        record.validUntil,
        record.createdAt,
        record.updatedAt,
      );

    return record;
  }

  searchMemories(query: string, limit: number = 10): MemoryRecord[] {
    const rows = this.db
      .prepare(
        `SELECT m.*
         FROM memories_fts f
         JOIN memories m ON m.rowid = f.rowid
         WHERE memories_fts MATCH ? AND m.is_deleted = 0
         ORDER BY bm25(memories_fts), m.updated_at DESC
         LIMIT ?`,
      )
      .all(query, limit) as Array<Record<string, unknown>>;

    return rows.map((row) => this.mapMemoryRow(row));
  }

  softDeleteMemory(id: string): boolean {
    const result = this.db
      .prepare("UPDATE memories SET is_deleted = 1, updated_at = ? WHERE id = ? AND is_deleted = 0")
      .run(Date.now(), id);
    return Number(result.changes) > 0;
  }

  addProspectiveItem(input: AddProspectiveInput): ProspectiveItem {
    const now = Date.now();
    const item: ProspectiveItem = {
      id: randomUUID(),
      kind: input.kind,
      title: input.title,
      details: input.details ?? {},
      dueAt: input.dueAt ?? null,
      recurrenceRule: input.recurrenceRule ?? null,
      priority: input.priority ?? 50,
      status: "active",
      createdAt: now,
      updatedAt: now,
    };

    this.db
      .prepare(
        `INSERT INTO prospective_items (
          id, kind, title, details_json, due_at, recurrence_rule, priority, status, created_at, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
      )
      .run(
        item.id,
        item.kind,
        item.title,
        JSON.stringify(item.details),
        item.dueAt,
        item.recurrenceRule,
        item.priority,
        item.status,
        item.createdAt,
        item.updatedAt,
      );

    return item;
  }

  listProspectiveItems(status: ProspectiveStatus = "active"): ProspectiveItem[] {
    const rows = this.db
      .prepare(
        `SELECT * FROM prospective_items
         WHERE status = ?
         ORDER BY priority DESC, COALESCE(due_at, 9999999999999) ASC`,
      )
      .all(status) as Array<Record<string, unknown>>;

    return rows.map((row) => ({
      id: String(row.id),
      kind: String(row.kind) as ProspectiveKind,
      title: String(row.title),
      details: JSON.parse(String(row.details_json)) as Record<string, unknown>,
      dueAt: row.due_at === null ? null : Number(row.due_at),
      recurrenceRule: row.recurrence_rule === null ? null : String(row.recurrence_rule),
      priority: Number(row.priority),
      status: String(row.status) as ProspectiveStatus,
      createdAt: Number(row.created_at),
      updatedAt: Number(row.updated_at),
    }));
  }

  private mapMemoryRow(row: Record<string, unknown>): MemoryRecord {
    return {
      id: String(row.id),
      category: String(row.category) as MemoryCategory,
      title: row.title === null ? null : String(row.title),
      content: String(row.content),
      tags: JSON.parse(String(row.tags_json)) as string[],
      sourceType: String(row.source_type) as MemorySourceType,
      sourceRef: row.source_ref === null ? null : String(row.source_ref),
      confidence: Number(row.confidence),
      importance: Number(row.importance),
      validUntil: row.valid_until === null ? null : Number(row.valid_until),
      createdAt: Number(row.created_at),
      updatedAt: Number(row.updated_at),
      isDeleted: Number(row.is_deleted) === 1,
    };
  }
}
