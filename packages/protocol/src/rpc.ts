export type SafetyLevel = "L0" | "L1" | "L2";

export type AgentProviderName =
  | "t3code"
  | "dsh"
  | "codex"
  | "claude-code"
  | "opencode"
  | "shell"
  | "local_llm";

export interface RpcError {
  code: number;
  message: string;
  data?: unknown;
}

export interface RpcRequest<TParams = unknown> {
  jsonrpc: "2.0";
  id: string | number;
  method: string;
  params?: TParams;
}

export interface RpcResponse<TResult = unknown> {
  jsonrpc: "2.0";
  id: string | number;
  result?: TResult;
  error?: RpcError;
}

export interface RpcNotification<TParams = unknown> {
  jsonrpc: "2.0";
  method: string;
  params?: TParams;
}

export interface WorkerNodeInfo {
  nodeId: string;
  displayName: string;
  tags: string[];
  providers: AgentProviderName[];
  tailscaleIp?: string;
  status?: "online" | "offline" | "busy";
}

export interface WorkerMetrics {
  cpuPercent?: number;
  memoryPercent?: number;
  gpuPercent?: number;
  vramUsedMb?: number;
}

export interface WorkerHeartbeatPayload {
  nodeId: string;
  lastInputActiveAt: number;
  metrics?: WorkerMetrics;
  status?: "online" | "offline" | "busy";
}

export type ThreadStatus =
  | "queued"
  | "running"
  | "waiting_approval"
  | "completed"
  | "failed";

export interface AgentThreadRecord {
  threadId: string;
  parentThreadId: string | null;
  nodeId: string;
  provider: AgentProviderName;
  title: string;
  status: ThreadStatus;
  summary: string | null;
  pendingApproval: ApprovalRequest | null;
  createdAt: number;
  updatedAt: number;
}

export interface ApprovalRequest {
  approvalId: string;
  threadId: string;
  nodeId: string;
  toolName: string;
  safetyLevel: SafetyLevel;
  summary: string;
  diffPreview?: string;
  expiresAt: number;
}

export interface ApprovalDecision {
  approvalId: string;
  approved: boolean;
  note?: string;
  decidedAt: number;
}

/**
 * Pluggable transport seam: defaults to TailscaleDirectTransport,
 * replaceable with CloudRelayTransport for future OSS cloud deployments.
 */
export interface TransportAdapter {
  readonly kind: "tailscale_direct" | "cloud_relay";
  connect(): Promise<void>;
  disconnect(): Promise<void>;
  sendNotification<TParams>(method: string, params: TParams): Promise<void>;
  request<TParams, TResult>(method: string, params: TParams): Promise<TResult>;
}
