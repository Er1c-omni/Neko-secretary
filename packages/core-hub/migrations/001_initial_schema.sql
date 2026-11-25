-- Schema migration tracking
CREATE TABLE IF NOT EXISTS schema_migrations (
    id TEXT PRIMARY KEY,
    applied_at INTEGER NOT NULL
);

-- 1. Event Sourcing & Idempotency Log
CREATE TABLE IF NOT EXISTS events (
    id TEXT PRIMARY KEY,
    idempotency_key TEXT UNIQUE NOT NULL,
    type TEXT NOT NULL,
    source TEXT NOT NULL,
    payload_json TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'pending',
    route TEXT,
    created_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_events_type_created ON events (type, created_at DESC);

-- 2. Structured Long-Term Memories (Profile / Episodic / Semantic / Procedural)
CREATE TABLE IF NOT EXISTS memories (
    id TEXT PRIMARY KEY,
    category TEXT NOT NULL,
    title TEXT,
    content TEXT NOT NULL,
    tags_json TEXT NOT NULL DEFAULT '[]',
    source_type TEXT NOT NULL,
    source_ref TEXT,
    confidence REAL NOT NULL DEFAULT 1.0,
    importance REAL NOT NULL DEFAULT 0.5,
    valid_until INTEGER,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    is_deleted INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_memories_cat_active ON memories (category, is_deleted, updated_at DESC);

CREATE VIRTUAL TABLE IF NOT EXISTS memories_fts USING fts5(
    title,
    content,
    tags_json,
    content='memories',
    content_rowid='rowid'
);

CREATE TRIGGER IF NOT EXISTS memories_ai AFTER INSERT ON memories BEGIN
    INSERT INTO memories_fts(rowid, title, content, tags_json)
    VALUES (new.rowid, new.title, new.content, new.tags_json);
END;

CREATE TRIGGER IF NOT EXISTS memories_ad AFTER DELETE ON memories BEGIN
    INSERT INTO memories_fts(memories_fts, rowid, title, content, tags_json)
    VALUES ('delete', old.rowid, old.title, old.content, old.tags_json);
END;

CREATE TRIGGER IF NOT EXISTS memories_au AFTER UPDATE ON memories BEGIN
    INSERT INTO memories_fts(memories_fts, rowid, title, content, tags_json)
    VALUES ('delete', old.rowid, old.title, old.content, old.tags_json);
    INSERT INTO memories_fts(rowid, title, content, tags_json)
    VALUES (new.rowid, new.title, new.content, new.tags_json);
END;

-- 3. Prospective Memory (Goals / Deadlines / Commitments / Subscriptions / Inventory / Habits)
CREATE TABLE IF NOT EXISTS prospective_items (
    id TEXT PRIMARY KEY,
    kind TEXT NOT NULL,
    title TEXT NOT NULL,
    details_json TEXT NOT NULL DEFAULT '{}',
    due_at INTEGER,
    recurrence_rule TEXT,
    priority INTEGER NOT NULL DEFAULT 50,
    status TEXT NOT NULL DEFAULT 'active',
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_prospective_status_due ON prospective_items (status, due_at ASC);

-- 4. Real-Time World State Snapshot (State Memory)
CREATE TABLE IF NOT EXISTS world_state (
    key TEXT PRIMARY KEY,
    value_json TEXT NOT NULL,
    updated_at INTEGER NOT NULL,
    ttl_sec INTEGER
);

-- 5. Persistent Hybrid Scheduler Jobs
CREATE TABLE IF NOT EXISTS scheduled_jobs (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    spec_json TEXT NOT NULL,
    next_run_at INTEGER,
    last_run_at INTEGER,
    last_fire_key TEXT,
    fires_today INTEGER NOT NULL DEFAULT 0,
    status TEXT NOT NULL DEFAULT 'active'
);

-- 6. Multi-PC Worker Nodes & Orchestrated Agent Threads
CREATE TABLE IF NOT EXISTS worker_nodes (
    node_id TEXT PRIMARY KEY,
    display_name TEXT NOT NULL,
    tags_json TEXT NOT NULL DEFAULT '[]',
    providers_json TEXT NOT NULL DEFAULT '[]',
    tailscale_ip TEXT,
    last_heartbeat_at INTEGER,
    last_input_active_at INTEGER,
    metrics_json TEXT NOT NULL DEFAULT '{}',
    status TEXT NOT NULL DEFAULT 'offline'
);

CREATE TABLE IF NOT EXISTS agent_threads (
    thread_id TEXT PRIMARY KEY,
    parent_thread_id TEXT,
    node_id TEXT NOT NULL,
    provider TEXT NOT NULL,
    title TEXT NOT NULL,
    status TEXT NOT NULL,
    summary TEXT,
    pending_approval_json TEXT,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_threads_status_updated ON agent_threads (status, updated_at DESC);

-- 7. Proactive Decision Feedback Log
CREATE TABLE IF NOT EXISTS proactive_feedback (
    id TEXT PRIMARY KEY,
    job_id TEXT,
    intent TEXT NOT NULL,
    decision_score REAL NOT NULL,
    context_snapshot_json TEXT NOT NULL DEFAULT '{}',
    user_action TEXT NOT NULL,
    latency_sec INTEGER,
    created_at INTEGER NOT NULL
);
