# Neko-Secretary 3 周 AI Coding 落地路线图

> 本文件作为日常开发进度看板与 AI Coding 任务清单。每完成一项，请同步更新复选框并运行对应验证命令。

## 模型切换图例（省 Token 实操指南）
- 🟢 **`[Hydrogen High]`（默认主力，约占 75% 工作量）**：常规 API 对接、Compose 页面、插件规则、数据库 CRUD 与单测，日常默认直接用它开干。
- 🟣 **`[Opus 5.5 Medium]`（攻坚专项，约占 25% 工作量）**：涉及 **Live2D EGL/OpenGL 透明渲染与点击透传、实时流式音频与口型同步并发、调度幂等/离线补偿、多台电脑进程托管与跨机多智能体 `SafetyGate` 审批状态机** 时切换，减少返工。

---

## Phase 0：仓库骨架与规范建设（已完成）
- [x] 初始化 Git 仓库与 Monorepo 结构（`packages/protocol`、`packages/core-hub`、`packages/pc-worker`、`apps/android`）
- [x] 完成完整架构、通信协议、工程规范与路线图文档
- [x] 搭建并通过 `@neko/core-hub` 基础单测（SQLite FTS5、EventBus 幂等、ProactiveEngine、PersonaRenderer、SafetyGate）与 `pc-worker` Go 单测

---

## Week 1：骨架通电 + Live2D 桌宠上线（第 7 天即可日常挂载使用）

### Day 1：协议与 Core Hub 底座联调 — 🟢 `[Hydrogen High]`
- 目标文件：`packages/core-hub/src/gateway/model-gateway.ts`、`packages/core-hub/src/server.ts`、`packages/core-hub/src/config.ts`、`apps/android/app/src/main/java/com/neko/secretary/sync/TransportClient.kt`
- [x] 实现 `.env` 加载（`loadDotEnv`）与 `ModelGateway` 真实 HTTP SSE 流式适配器（`OpenAICompatibleProvider` 支持 DeepSeek/OpenAI/火山方舟/Ollama，`GeminiProvider` 支持自定义 `GOOGLE_GEMINI_BASE_URL` 与多模态）
- [x] 补全 `Core Hub` (`GET /health`、`POST /api/chat`、WS `hub.ping` / `chat.send` / `event.ingest` -> `directive.push`) 与 Android `TailscaleDirectTransport` Ktor WebSocket 客户端；本地 `GET /health` 与 `ws://` `hub.ping` 联调通过
- [ ] （待真机/真实网络环境补充）配置本地 `.env` 并完成真实手机经 Tailscale Mesh 连入 `ws://` + 公网模型 API 端到端实测（当前开发机无 `tailscale`/Android 编译环境，且环境内 `GOOGLE_GEMINI_BASE_URL` 外网 TLS `ECONNRESET`；`ModelGateway` 流式与结构化回复已通过本地 Mock SSE 与 Server 集成单测验收）

### Day 2–3：Android 悬浮窗 + Live2D 进阶渲染 — 🟣 `[Opus 5.5 Medium]`
- 目标文件：`apps/android/app/src/main/java/com/neko/secretary/live2d/Live2DTextureRenderer.kt`、`apps/android/app/src/main/java/com/neko/secretary/overlay/CompanionOverlayService.kt`
- 切换原因：需处理独立 EGL 渲染线程生命周期、`TextureView` 透明通道合成、非人物区域点击透传（不遮挡下层 App）及 60/20/10/0 FPS 自适应降频。
- [x] 为 `apps/android` 补齐 Gradle Wrapper（8.11.1），配置 `live2d-core/` 条件编译（`cubismEnabled`）与 `CubismModelBackend` 真实 SDK 适配层，无闭源 SDK 时自动回退透明 SDF 占位桌宠（`PlaceholderNekoBackend`）并支持运行时 `.zip` 模型包安全导入（含 Zip-Slip 与体积防护）；已通过 `:app:testDebugUnitTest` 与 `:app:assembleDebug`
- [x] 完善 `Live2DTextureRenderer`（`EglCore` RGBA8888 透明离屏 pbuffer 保活 + 独立 `Live2DRenderThread` + `EGL_CONTEXT_LOST` 重建 + `FramePacingPolicy` 60/20/10/0 FPS 自适应切换 + `HitTestMask` Alpha 命中网格）
- [x] 完善 `CompanionOverlayService` 拖拽甩边吸附、左右贴边潜伏（`PEEK_IDLE` 42% 露出 + 点击展开/独立 `BubbleWindow` 气泡弹窗 + 超时自动回缩）、透明区域点击透传（`FLAG_NOT_TOUCHABLE` + Android 12+ `maximumObscuringOpacityForTouch` 兼容）与 **职场防社死一键隐身开关**（通知栏快捷操作 + `StealthTileService` 下拉磁贴 + 控制台开关，隐身/息屏/锁屏自动 0 FPS 挂起 GL 线程）
- [x] 模拟器实测（`Pixel_7` Android 16 硬件 GPU）：已实测桌宠透明贴边潜伏（42% 露出）、点击展开与气泡弹窗（60 FPS 提频）、拖拽竖向重定位吸附、一键防社死隐身折叠徽章（`0 FPS` + 禁用 TTS）及 `ACTION_SEND` 分享解析卡片；修复了 API 35/36 顶栏遮挡与首次 attach 帧率刷新问题
- [ ] （待真机与官方 SDK 补充）在 Android Studio 放入官方闭源 Live2D Cubism SDK（按 `apps/android/live2d-core/README.md`）并完成真机挂机功耗实测

### Day 4：即时流式语音输入（ASR） — 🟣 `[Opus 5.5 Medium]`
- 目标文件：`apps/android/app/src/main/java/com/neko/secretary/voice/VoicePipeline.kt`
- 切换原因：涉及端侧 AudioRecord 实时采集分帧、流式 WebSocket 双向通信、长按松手/上滑取消状态机及 Sherpa-ONNX 离线兜底切换。
- [x] 在 `VoicePipeline` 实现 16kHz PCM 分帧采集（`AudioFrameCapture` + RMS 音量包络）、火山引擎二进制流式协议（`VolcengineProtocol` / `VolcengineWsAsrBackend`）与阿里 FunASR 2-pass WebSocket 协议（`FunAsrProtocol` / `FunAsrWsAsrBackend`）、断网/未配置自动降级 Sherpa-ONNX 离线兜底接口（`SherpaOnnxOfflineAsrEngine`）、口头禅清洗（`TranscriptCleaner`）与 `user.voice_transcript` 标准信封封装（`VoiceEventFactory`）
- [x] 在 `CompanionOverlayService` 与 `MainActivity` 接入长按桌宠说话、松手发送、上滑取消（含迟滞防抖 `VoiceGestureMachine` + 实时转写气泡 + 60 FPS 提频）与控制台 ASR 配置页；已通过 `:app:testDebugUnitTest`（35/35）与 `:app:assembleDebug`
- [ ] （待真机/API Key 补充）配置真实火山引擎或 FunASR 密钥，在真机/模拟器验证麦克风实时流式识别与 Core Hub 联动

### Day 5：截图分享一键吞噬 — 🟢 `[Hydrogen High]`
- 目标文件：`apps/android/app/src/main/java/com/neko/secretary/perception/ShareIntakeHandler.kt`、`packages/core-hub/src/plugins/share-intake.ts`、`packages/core-hub/src/server.ts`
- [x] 实现 `ShareIntakePlugin`（严格使用 `wrapUntrustedExternalData` 包裹不可信外部文本 + Vision/Fast 模型结构化解析 + 账单/死线/日程/灵感启发式兜底）与 Core Hub `POST /api/share/intake`、`POST /api/share/confirm`、`POST /api/share/dismiss` 接口（`idempotencyKey` 去重、`directive.push` 广播与一键写入 `memories` + `prospective_items`）
- [x] 完善 Android `ShareIntakeHandler`（`ACTION_SEND` 图片压缩转 Base64 + SHA-256 幂等键 + Ktor 上传 + 离线预览卡片）与 `MainActivity` 分享确认卡片 UI（`[一键入库]` / `[忽略]` + Hub Endpoint 配置）；已通过 `pnpm typecheck && pnpm test`（14/14）、`:app:testDebugUnitTest`（38/38）与 `Pixel_7` 模拟器 `ACTION_SEND` 实测
- [ ] （待真机/Vision API Key 补充）在真机微信/邮件截完图分享给助理，联调公网 Vision 模型 3 秒内端到端入库

### Day 6–7：第一条完整主动提醒闭环 — 🟣 `[Opus 5.5 Medium]`
- 目标文件：`packages/core-hub/src/scheduler/scheduler.ts`、`apps/android/app/src/main/java/com/neko/secretary/sync/TransportClient.kt`
- 切换原因：需保证 `fire_key` 防重落库、断线重连后 Room 离线队列幂等补发，以及服务端在线调度与手机本地 `AlarmManager` 兜底不冲突、不连弹。
- [ ] 完善 `HybridScheduler` 持久化时间/间隔调度与 `fire_key` 防重落库
- [ ] 打通 Core Hub → WebSocket `directive.push` → Android 桌宠抱水杯气泡 `[喝完啦]` / `[5m后]` 反馈回传
- [ ] 在 Android 端接入 `AlarmManager` 作为断网离线基础兜底闹钟
- [ ] 验收：语音说“每小时提醒我喝水”，到点桌宠主动弹出，点击反馈写入 `proactive_feedback` 与 `world_state`

---

## Week 2：生活场景丰满 + 实用痛点拉满

### Day 8–9：每日 3 秒剧本 + 防社死自动化 + 可选 TTS — 🟢 `[Hydrogen High]`（口型同步子项按需切 🟣）
- 目标文件：`packages/core-hub/src/plugins/`、`apps/android/app/src/main/java/com/neko/secretary/voice/VoicePipeline.kt`、`Live2DTextureRenderer.kt`
- 切换提示：剧本卡、Wi-Fi 监听与缓存短句播放用 `Hydrogen High`；若要做 TTS 实时音频流 RMS 音量驱动 Live2D 口型同步（Lip-sync），临时切 `Opus 5.5 Medium`。
- [ ] 实现每日首次解锁弹出“今日 3 秒剧本卡”（`office` / `wfh` / `interview` / `holiday`）及晨间简报插件
- [ ] 接入 `WifiMonitor` 监听公司/实验室 Wi-Fi SSID 自动切换 `STEALTH_OFFICE`
- [ ] 实现可选 TTS 模块（默认关闭/仅居家开启），提前缓存 20+ 条 CosyVoice/豆包情绪短句音频驱动 Live2D 口型同步
- [ ] 验收：连上公司 Wi-Fi 自动隐身静音；在家切 `wfh` 剧本开启温和语音提醒

### Day 10–11：死线雷达 + 考前速览 + 碎片/周报管理 — 🟢 `[Hydrogen High]`
- 目标文件：`apps/android/app/src/main/java/com/neko/secretary/perception/ShareIntakeHandler.kt`、`packages/core-hub/src/plugins/`
- [ ] 接入 Android `NotificationListenerService` 捕获笔试/面试/测评/快递通知（严守零逆向原则）
- [ ] 实现 `prospective_items` 24h/2h/30m 梯度死线升级提醒，及考前/会前 30 分钟秘书速览卡（不做八股抽查）
- [ ] 实现下班 30 秒语音攒实习流水账插件（周五汇总周报与 STAR 简历素材）及消费续费/物品保质期记录
- [ ] 验收：面试通知截图/短信自动生成梯度提醒与考前速览；语音碎碎念可按标签归档

### Day 12–14：专注防摸鱼监工 + 六维记忆可视化 + 主动策略自学习 — 🟢 `[Hydrogen High]`（评分调权按需切 🟣）
- 目标文件：`apps/android/` UI 与感知层、`packages/core-hub/src/memory/repository.ts`、`packages/core-hub/src/proactive/engine.ts`
- [ ] 接入 `UsageStatsManager` 检测专注期内刷短视频/娱乐 App 超阈值，触发 `ANTI_PROCRASTINATE` 桌宠中心抓包动画
- [ ] 在 Android Compose UI 实现记忆（`memories`）与前瞻事项（`prospective_items`）的 FTS5 搜索、查看、编辑与软删除界面
- [ ] 落地 `ProactiveEngine` 基于 `proactive_feedback` 历史点击率微调打扰阈值（复杂边界调参可按需切 `Opus 5.5 Medium`）
- [ ] 验收：论文模式刷 B 站 5 分钟桌宠跳出劝回；App 内可随时检索并删除历史记忆

---

## Week 3：指挥官秘书态 & 多台电脑多智能体编排

### Day 15–16：多机 `pc-worker` 实战部署 — 🟣 `[Opus 5.5 Medium]`
- 目标文件：`packages/pc-worker/internal/activity/activity.go`、`packages/pc-worker/internal/watcher/watcher.go`、`packages/pc-worker/cmd/neko-worker/main.go`、`packages/core-hub/src/proactive/engine.ts`
- 切换原因：涉及 Windows/Linux 原生键鼠空闲时间系统调用、WebSocket 断线重连心跳、三源融合久坐时序窗口与文件日志流哨兵。
- [ ] 为 `packages/pc-worker` 补齐 Windows/Linux 原生键鼠空闲时间采集与真实 WebSocket 客户端长连接
- [ ] 部署至多台目标电脑（实验 GPU 机、台式机、笔记本），打通 **三源融合久坐识别**
- [ ] 接入论文/Obsidian 目录白名单同步 MCP 与 GPU/实验日志哨兵告警推送
- [ ] 验收：手机放桌上人离开不会误报久坐；实验室训练报错 5 秒内推到手机桌宠

### Day 17–18：指挥官秘书工作台 & 多智能体审批流 — 🟣 `[Opus 5.5 Medium]`
- 目标文件：`packages/pc-worker/internal/adapter/adapter.go`、`packages/core-hub/src/orchestrator/registry.ts`、`packages/core-hub/src/server.ts`、`apps/android/app/src/main/java/com/neko/secretary/actions/SafetyGateClient.kt`
- 切换原因：跨多台电脑编排子智能体（PTY/JSON-Stream 托管 `T3 Code` / `dsh` / `Codex` / `Claude Code`）、`parent_thread_id` 谱系追踪，以及 `L2` 级操作挂起等待手机 Diff 审批与超时自动拒绝的状态机一致性要求高。
- [ ] 在 `pc-worker` 适配 T3 Code MCP / DeepSeek Harness (`dsh`) / Codex / Claude Code 任务托管与流式日志转发
- [ ] 在 Android 上线**秘书态一键换装（眼镜+干练职业装）**、多机 `agent_threads` 状态看板及 `L2` 级 `SafetyGate` Diff 审批弹窗
- [ ] 验收：手机语音向指定电脑 Codex 派发重构任务，遇 `git push` 挂起并在手机端一键审批通过

### Day 19–21：扩展生态 + 可靠性压测 + 开源打磨 — 🟢 `[Hydrogen High]`（并发/功耗压测排障按需切 🟣）
- 目标文件：`packages/core-hub/src/plugins/`、`apps/android/`、`docs/ROADMAP.md`
- [ ] 按需接入 Home Assistant MCP、GitHub Release / Arxiv 论文监控插件、横屏 `DOCK_STANDBY` 桌搭陪写模式
- [ ] 完成断网重连幂等补发、手机重启 `BOOT_COMPLETED` 自恢复、Live2D 挂机功耗压测
- [ ] 检查 `.gitignore` 确保无闭源库/密钥残留，进入日常稳定自用与持续迭代阶段
