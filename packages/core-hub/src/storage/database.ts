import { mkdirSync, readdirSync, readFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { DatabaseSync } from "node:sqlite";
import { fileURLToPath } from "node:url";

const CURRENT_DIR = dirname(fileURLToPath(import.meta.url));
const DEFAULT_MIGRATIONS_DIR = resolve(CURRENT_DIR, "../../migrations");

export class HubDatabase {
  readonly db: DatabaseSync;

  constructor(dbPath: string) {
    if (dbPath !== ":memory:") {
      mkdirSync(dirname(resolve(dbPath)), { recursive: true });
    }
    this.db = new DatabaseSync(dbPath);
    this.db.exec("PRAGMA journal_mode = WAL;");
    this.db.exec("PRAGMA foreign_keys = ON;");
  }

  migrate(migrationsDir: string = DEFAULT_MIGRATIONS_DIR): string[] {
    this.db.exec(`
      CREATE TABLE IF NOT EXISTS schema_migrations (
        id TEXT PRIMARY KEY,
        applied_at INTEGER NOT NULL
      );
    `);

    const files = readdirSync(migrationsDir)
      .filter((name) => name.endsWith(".sql"))
      .sort();

    const applied: string[] = [];
    const hasStmt = this.db.prepare("SELECT id FROM schema_migrations WHERE id = ?");
    const insertStmt = this.db.prepare(
      "INSERT INTO schema_migrations (id, applied_at) VALUES (?, ?)",
    );

    for (const file of files) {
      const existing = hasStmt.get(file);
      if (existing) {
        continue;
      }
      const sql = readFileSync(join(migrationsDir, file), "utf8");
      this.db.exec("BEGIN");
      try {
        this.db.exec(sql);
        insertStmt.run(file, Date.now());
        this.db.exec("COMMIT");
        applied.push(file);
      } catch (err) {
        this.db.exec("ROLLBACK");
        throw err;
      }
    }

    return applied;
  }

  close(): void {
    this.db.close();
  }
}
