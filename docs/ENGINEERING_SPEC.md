# Neko-Secretary 工程开发与可靠性规范

本文档约束所有编码人员与 AI Coding Agent 的具体实现标准，确保代码轻量、可测试、可长期稳定运行。

---

## 1. 语言与代码规范

### 1.1 TypeScript（`packages/protocol`, `packages/core-hub`）
- **Node 24 原生 TypeScript 兼容规范**（极其重要）：
  - `tsconfig.base.json` 启用 `"erasableSyntaxOnly": true` 与 `"allowImportingTsExtensions": true`。
  - **严禁使用 `enum`**、非空声明合并或构造函数参数简写（如 `constructor(public db: Db)`）。统一使用 `as const` 对象与字符串联合类型（Union Types）。
  - 所有相对路径导入必须显式携带 `.ts` 后缀（例如 `import { HubDatabase } from "../storage/database.ts";`）。
  - 使用原生 ESM（`"type": "module"`）与 Node 内置 `node:sqlite`、`node:http`、`node:test`、`node:assert/strict`。
- **代码整洁原则**：
  - 核心业务逻辑不得耦合具体 LLM SDK 或特定传输实现，必须通过 `ModelProvider` 与 `TransportAdapter` 接口解耦。
  - 严禁重复造轮子：所有共享类型一律定义在 `@neko/protocol` 中。

### 1.2 Go（`packages/pc-worker`）
- 使用 Go 1.27 标准库优先原则（`net/http`、`encoding/json`、`os/exec`、`context`），避免引入重型框架，确保可交叉编译为 Windows / Linux / macOS 单二进制文件。
- 所有子进程调用必须绑定 `context.Context` 超时控制与输出流截断限制。

### 1.3 Kotlin / Android（`apps/android`）
- UI 一律使用 Jetpack Compose，状态管理使用 `StateFlow` / `Coroutines`。
- 悬浮窗渲染必须采用 `TextureView` + 独立 `EGLThread`，严格遵守 60/20/10/0 四级帧率自适应。
- 不得硬编码公司 Wi-Fi SSID、API Key 或私有 Tailscale IP；全部读取配置或加密存储（EncryptedSharedPreferences / DataStore）。

---

## 2. SQLite 与数据迁移规范

- 必须启用 `PRAGMA journal_mode = WAL;` 与 `PRAGMA foreign_keys = ON;`。
- 所有表结构变更通过 `packages/core-hub/migrations/NNN_name.sql` 顺序脚本执行，严禁在业务代码里零散 `CREATE TABLE`（除非通过迁移器）。
- 记忆检索表 `memories` 通过 `AFTER INSERT` / `AFTER UPDATE` / `AFTER DELETE` 触发器自动同步 `memories_fts`（FTS5），并统一使用 `is_deleted = 1` 软删除。

---

## 3. SafetyGate 安全分级矩阵

| 风险等级 | 典型工具与动作 | 放行策略 |
| :--- | :--- | :--- |
| **L0（只读安全）** | 读日历、读天气、查记忆、查 Git 状态、查进程与 GPU 指标、读文件摘要 | 自动放行，记录审计日志 |
| **L1（低风险可逆）** | 创建闹钟/提醒、写入 `prospective_items`、向灵感库追加 Markdown、在独立分支新建 Git Worktree | 自动执行 + 推送轻量通知 |
| **L2（高风险/外部副作用）** | 删除文件、高危 Shell 命令（`rm`/`kill`/`chmod`）、`git push`、对外发邮件/消息、开启实验性手机 UI 操控 | **强制挂起**，下发 `ApprovalRequest` 至手机确认；超时默认拒绝 |

### 提示词注入防御
所有来自外部截图 OCR、第三方通知、网页监控、邮件的内容，进入 LLM Prompt 前**必须**经由 `wrapUntrustedExternalData(source, content)` 转义并包裹在 `<untrusted_external_data source="...">...</untrusted_external_data>` 中。

---

## 4. 可靠性验证清单（每阶段必测）

1. **单测门禁**：`pnpm typecheck` 与 `pnpm test` 100% 通过；`pc-worker` 执行 `go test ./...` 100% 通过。
2. **重启自恢复**：Core Hub 重启后能从 `scheduled_jobs` 与 `agent_threads` 恢复状态；Android 重启接收 `BOOT_COMPLETED` 自动恢复前台服务与本地兜底闹钟。
3. **幂等性验证**：同一 `idempotencyKey` 重复上报 `event.ingest` 必须返回 `deduplicated: true` 且仅落库一条记录。
4. **防社死与静默验证**：`STEALTH_OFFICE` 状态下，`PersonaRenderer` 输出必须置 `stealthOverride = true` 且 `tts.enabledIfAllowed = false`。
5. **非静默死线例外验证**：静默时段（如 02:00）普通喝水提醒必须被拦截，而 `urgency >= 95` 的紧急死线或 GPU 崩溃告警必须放行。
