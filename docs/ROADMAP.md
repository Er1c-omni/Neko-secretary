# Neko-Secretary 3 周 AI Coding 落地路线图

> 本文件作为日常开发进度看板与 AI Coding 任务清单。每完成一项，请同步更新复选框并运行对应验证命令。

---

## Phase 0：仓库骨架与规范建设（已完成）
- [x] 初始化 Git 仓库与 Monorepo 结构（`packages/protocol`、`packages/core-hub`、`packages/pc-worker`、`apps/android`）
- [x] 完成完整架构、通信协议、工程规范与路线图文档
- [x] 搭建并通过 `@neko/core-hub` 基础单测（SQLite FTS5、EventBus 幂等、ProactiveEngine、PersonaRenderer、SafetyGate）与 `pc-worker` Go 单测

---

## Week 1：骨架通电 + Live2D 桌宠上线（第 7 天即可日常挂载使用）

### Day 1：协议与 Core Hub 底座联调
- [ ] 配置真实 `.env`（DeepSeek / Gemini API Key）并在 `ModelGateway` 实现真实 HTTP 流式适配器
- [ ] 验证 Tailscale Mesh 下手机与 Core Hub 的 `ws://` 连通性与 `hub.ping` 心跳
- [ ] 验收：`curl /health` 正常，自然语言测试消息可经 `ModelGateway` 返回结构化回复

### Day 2–3：Android 悬浮窗 + Live2D 进阶渲染
- [ ] 在 Android Studio 导入 `apps/android` 并放入官方 Live2D Cubism SDK（按 `apps/android/live2d-core/README.md`）
- [ ] 完善 `Live2DTextureRenderer` 真实 Cubism 模型加载、透明背景、60/20/10/0 FPS 自适应切换
- [ ] 完善 `CompanionOverlayService` 拖拽、左右贴边潜伏（`PEEK_IDLE`）、透明区域点击透传与 **职场防社死一键隐身开关**
- [ ] 验收：桌宠稳定挂在手机边缘不挡屏，点按切换展示与气泡弹窗，锁屏/隐身时 GPU 降为 0

### Day 4–5：零摩擦输入双引擎（即时语音 + 截图吞噬）
- [ ] 在 `VoicePipeline` 接入火山引擎流式 ASR（或 FunASR），实现长按桌宠说话、松手发送、上滑取消（附 Sherpa-ONNX 离线兜底接口）
- [ ] 完善 `ShareIntakeHandler` 与服务端截图上传接口，接入 Vision 模型解析截图为日程 / 死线 / 账单 / 灵感待确认卡片
- [ ] 验收：微信/邮件截完图点“分享给助理”，3 秒内桌宠弹出解析卡片并一键写入库

### Day 6–7：第一条完整主动提醒闭环
- [ ] 完善 `HybridScheduler` 持久化时间/间隔调度与 `fire_key` 防重落库
- [ ] 打通 Core Hub → WebSocket `directive.push` → Android 桌宠抱水杯气泡 `[喝完啦]` / `[5m后]` 反馈回传
- [ ] 在 Android 端接入 `AlarmManager` 作为断网离线基础兜底闹钟
- [ ] 验收：语音说“每小时提醒我喝水”，到点桌宠主动弹出，点击反馈写入 `proactive_feedback` 与 `world_state`

---

## Week 2：生活场景丰满 + 实用痛点拉满

### Day 8–9：每日 3 秒剧本 + 防社死自动化 + 可选 TTS
- [ ] 实现每日首次解锁弹出“今日 3 秒剧本卡”（`office` / `wfh` / `interview` / `holiday`）及晨间简报插件
- [ ] 接入 `WifiMonitor` 监听公司/实验室 Wi-Fi SSID 自动切换 `STEALTH_OFFICE`
- [ ] 实现可选 TTS 模块（默认关闭/仅居家开启），提前缓存 20+ 条 CosyVoice/豆包情绪短句音频驱动 Live2D 口型同步
- [ ] 验收：连上公司 Wi-Fi 自动隐身静音；在家切 `wfh` 剧本开启温和语音提醒

### Day 10–11：死线雷达 + 考前速览 + 碎片/周报管理
- [ ] 接入 Android `NotificationListenerService` 捕获笔试/面试/测评/快递通知（严守零逆向原则）
- [ ] 实现 `prospective_items` 24h/2h/30m 梯度死线升级提醒，及考前/会前 30 分钟秘书速览卡（不做八股抽查）
- [ ] 实现下班 30 秒语音攒实习流水账插件（周五汇总周报与 STAR 简历素材）及消费续费/物品保质期记录
- [ ] 验收：面试通知截图/短信自动生成梯度提醒与考前速览；语音碎碎念可按标签归档

### Day 12–14：专注防摸鱼监工 + 六维记忆可视化 + 主动策略自学习
- [ ] 接入 `UsageStatsManager` 检测专注期内刷短视频/娱乐 App 超阈值，触发 `ANTI_PROCRASTINATE` 桌宠中心抓包动画
- [ ] 在 Android Compose UI 实现记忆（`memories`）与前瞻事项（`prospective_items`）的 FTS5 搜索、查看、编辑与软删除界面
- [ ] 落地 `ProactiveEngine` 基于 `proactive_feedback` 历史点击率微调打扰阈值
- [ ] 验收：论文模式刷 B 站 5 分钟桌宠跳出劝回；App 内可随时检索并删除历史记忆

---

## Week 3：指挥官秘书态 & 多台电脑多智能体编排

### Day 15–16：多机 `pc-worker` 实战部署
- [ ] 为 `packages/pc-worker` 补齐 Windows/Linux 原生键鼠空闲时间采集与真实 WebSocket 客户端长连接
- [ ] 部署至多台目标电脑（实验 GPU 机、台式机、笔记本），打通 **三源融合久坐识别**
- [ ] 接入论文/Obsidian 目录白名单同步 MCP 与 GPU/实验日志哨兵告警推送
- [ ] 验收：手机放桌上人离开不会误报久坐；实验室训练报错 5 秒内推到手机桌宠

### Day 17–18：指挥官秘书工作台 & 多智能体审批流
- [ ] 在 `pc-worker` 适配 T3 Code MCP / DeepSeek Harness (`dsh`) / Codex / Claude Code 任务托管与流式日志转发
- [ ] 在 Android 上线**秘书态一键换装（眼镜+干练职业装）**、多机 `agent_threads` 状态看板及 `L2` 级 `SafetyGate` Diff 审批弹窗
- [ ] 验收：手机语音向指定电脑 Codex 派发重构任务，遇 `git push` 挂起并在手机端一键审批通过

### Day 19–21：扩展生态 + 可靠性压测 + 开源打磨
- [ ] 按需接入 Home Assistant MCP、GitHub Release / Arxiv 论文监控插件、横屏 `DOCK_STANDBY` 桌搭陪写模式
- [ ] 完成断网重连幂等补发、手机重启 `BOOT_COMPLETED` 自恢复、Live2D 挂机功耗压测
- [ ] 检查 `.gitignore` 确保无闭源库/密钥残留，进入日常稳定自用与持续迭代阶段
