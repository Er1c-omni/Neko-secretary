package com.neko.secretary.protocol

enum class AssistantMode(val wireName: String) {
    LIFE("life"),
    SECRETARY("secretary")
}

enum class TodayScript(val wireName: String) {
    OFFICE("office"),
    WFH("wfh"),
    INTERVIEW("interview"),
    HOLIDAY("holiday")
}

enum class CharacterStateId {
    PEEK_IDLE,
    LIFE_GREETING,
    LIFE_WATER,
    LIFE_STANDUP,
    ANTI_PROCRASTINATE,
    SECRETARY_ACTIVE,
    SECRETARY_ALERT,
    SLEEPY_NIGHT,
    DOCK_STANDBY,
    STEALTH_OFFICE
}

data class DirectiveAction(
    val id: String,
    val label: String,
    val feedbackMotion: String? = null
)

data class CharacterDirective(
    val directiveId: String,
    val mode: AssistantMode,
    val state: CharacterStateId,
    val stealthOverride: Boolean,
    val text: String,
    val expression: String,
    val motion: String,
    val costume: String,
    val ttsAllowed: Boolean,
    val ttsCacheKey: String?,
    val actions: List<DirectiveAction> = emptyList()
)
