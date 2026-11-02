package com.glasssign.app.login

/**
 * In-process handoff between the login screen and the app.
 *
 * The credential string never travels through an Intent: the activity returns only a result code,
 * and the payload is picked up from memory by the caller that launched it. If the process dies the
 * result is simply lost instead of being written to disk or a log.
 */
data class LoginPayload(val provider: String, val nickname: String, val credentials: String)

/**
 * A session that has been captured but is not bound yet: the real character list was read from the
 * platform and the user still has to confirm it before the account becomes active.
 */
data class PendingLogin(
    val accountId: String,
    val provider: String,
    val nickname: String,
    val roles: List<String>,
    val message: String,
    val availableGameIds: List<String> = emptyList(),
    val selectedGameIds: Set<String> = availableGameIds.toSet(),
    val existingAccount: Boolean = false,
    val roleChoices:List<com.glasssign.app.data.RoleChoice> = emptyList(),
    val selectedRoleIds:Set<String> = roleChoices.map{it.targetId}.toSet(),
    val providerUserId:String="",
    val autoSignEnabled:Boolean=true,
    val confirming:Boolean=false,

) {
    val ready: Boolean get() = roles.isNotEmpty() && (availableGameIds.isEmpty() || selectedGameIds.isNotEmpty()) && (roleChoices.isEmpty() || roleChoices.any{it.gameId in selectedGameIds && it.targetId in selectedRoleIds})
}

object LoginBridge {
    private var pending: LoginPayload? = null

    fun deliver(payload: LoginPayload) { pending = payload }

    fun consume(): LoginPayload? = pending.also { pending = null }

    fun clear() { pending = null }
}
