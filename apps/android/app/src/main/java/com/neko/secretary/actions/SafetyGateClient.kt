package com.neko.secretary.actions

data class PendingApprovalUi(
    val approvalId: String,
    val nodeId: String,
    val toolName: String,
    val summary: String,
    val diffPreview: String?
)

/**
 * Receives L2 SafetyGate approval prompts from Core Hub so the user can approve
 * or reject remote PC worker operations directly from the phone overlay.
 */
object SafetyGateClient {
    fun formatApprovalBanner(item: PendingApprovalUi): String {
        return "[${item.nodeId}] ${item.toolName}: ${item.summary}"
    }
}
