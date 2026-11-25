import type { SafetyLevel } from "@neko/protocol";

const L0_TOOLS = new Set([
  "calendar.read",
  "weather.read",
  "memory.search",
  "git.status",
  "worker.metrics",
  "file.read_summary",
]);

const L1_TOOLS = new Set([
  "reminder.create",
  "alarm.set",
  "prospective.upsert",
  "memory.write",
  "notes.append_markdown",
  "git.create_worktree",
]);

const L2_TOOLS = new Set([
  "file.delete",
  "shell.exec_destructive",
  "git.push",
  "message.send_external",
  "android.ui_automate",
]);

export interface SafetyEvaluation {
  toolName: string;
  level: SafetyLevel;
  requiresApproval: boolean;
  reason: string;
}

export class SafetyGate {
  /**
   * Persona/mode switching MUST NEVER relax tool safety boundaries.
   */
  classifyTool(toolName: string): SafetyLevel {
    if (L0_TOOLS.has(toolName)) {
      return "L0";
    }
    if (L1_TOOLS.has(toolName)) {
      return "L1";
    }
    if (L2_TOOLS.has(toolName)) {
      return "L2";
    }
    // Fail-closed: unknown side-effecting tools default to L2 human approval.
    return "L2";
  }

  evaluate(toolName: string): SafetyEvaluation {
    const level = this.classifyTool(toolName);
    if (level === "L2") {
      return {
        toolName,
        level,
        requiresApproval: true,
        reason: "high_risk_or_unknown_tool_requires_mobile_approval",
      };
    }
    return {
      toolName,
      level,
      requiresApproval: false,
      reason: level === "L0" ? "read_only_safe" : "low_risk_reversible",
    };
  }
}

export function wrapUntrustedExternalData(source: string, content: string): string {
  const safeSource = source.replace(/[^a-zA-Z0-9_.:-]/g, "_");
  const neutralized = content.replaceAll("</untrusted_external_data>", "&lt;/untrusted_external_data&gt;");
  return `<untrusted_external_data source="${safeSource}">\n${neutralized}\n</untrusted_external_data>`;
}
