package com.glasssign.app.plugins

import org.json.JSONObject
import java.net.URI

/** V1 only admits reviewed, bundled implementations. An external manifest never executes code. */
enum class PluginCategory { GAME, UTILITY, COMMUNITY }
enum class PluginAuth { OFFICIAL_BROWSER, DEVICE_CODE, AUTH_IMPORT }
enum class PluginResultStatus { SUCCESS, ALREADY, NO_ACTIVITY, NEEDS_ACTION, WAITING_NETWORK, UNCERTAIN, FAILED }

data class PluginDescriptor(
    val id: String,
    val displayName: String,
    val version: Int,
    val category: PluginCategory,
    val auth: PluginAuth,
    val allowedHosts: Set<String>
) {
    init {
        require(id.matches(Regex("[a-z][a-z0-9]*(\\.[a-z][a-z0-9-]*)+"))) { "Invalid stable plugin ID" }
        require(displayName.isNotBlank() && displayName.length <= 80) { "Invalid plugin name" }
        require(version > 0) { "Version must be positive" }
        require(allowedHosts.isNotEmpty()) { "No HTTPS hosts declared" }
        require(allowedHosts.all { host ->
            host == host.lowercase() && host.length <= 253 &&
                host.matches(Regex("[a-z0-9]+(?:[.-][a-z0-9-]+)+")) &&
                !host.contains("..") && !host.startsWith("xn--")
        }) { "Invalid host allowlist" }
    }
}

/** Always scoped to a single platform/account. Never transfer credentials across plugins. */
data class PluginAccountRef(val pluginId: String, val accountId: String)
data class PluginTask(val id: String, val title: String)
data class PluginOutcome(val status: PluginResultStatus, val message: String, val claimedAmount: Long? = null)

/** Privileged host API. Only bundled trusted plugins may obtain an account-scoped credential. */
interface TrustedPluginHost {
    fun readAccountCredential(account: PluginAccountRef): JSONObject
    fun persistAccountCredential(account: PluginAccountRef, updated: JSONObject)
    fun httpFor(descriptor: PluginDescriptor): PluginHttp
}

enum class PluginMethod { GET, POST }
data class PluginRequest(
    val method: PluginMethod,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null
)
data class PluginResponse(val statusCode: Int, val json: JSONObject)

/** Host-side implementation must disable redirects and cap response sizes. */
interface PluginHttp {
    fun execute(request: PluginRequest): PluginResponse
}

/** Host gate: no wildcard hosts, HTTP, userinfo, redirect-based escalation or arbitrary ports. */
object PluginNetworkPolicy {
    fun requireAllowed(descriptor: PluginDescriptor, url: String) {
        val target = try { URI(url) } catch (_: Exception) { throw IllegalArgumentException("Malformed plugin URL") }
        require(target.scheme?.lowercase() == "https" && target.host != null) { "HTTPS required" }
        require(target.host.lowercase() in descriptor.allowedHosts) { "Unapproved plugin host" }
        require(target.port == -1 || target.port == 443) { "Unapproved port" }
        require(target.rawUserInfo == null && target.rawFragment == null) { "Unsafe URL component" }
    }
}

/**
 * Plugin implementation contract, separate from the existing game-role ProviderAdapter.
 * Only a fully implemented plugin enters PluginRegistry; metadata alone is not a working plugin.
 */
interface CheckinPlugin {
    val descriptor: PluginDescriptor
    fun discoverTasks(account: PluginAccountRef, host: TrustedPluginHost): List<PluginTask>
    fun checkStatus(account: PluginAccountRef, task: PluginTask, host: TrustedPluginHost): PluginOutcome
    fun claim(account: PluginAccountRef, task: PluginTask, host: TrustedPluginHost): PluginOutcome
}

class PluginRegistry(plugins: Iterable<CheckinPlugin>) {
    private val byId: Map<String, CheckinPlugin>
    init {
        val values = plugins.toList()
        require(values.map { it.descriptor.id }.distinct().size == values.size) { "Duplicate plugin ID" }
        byId = values.associateBy { it.descriptor.id }
    }
    fun all(): List<PluginDescriptor> = byId.values.map { it.descriptor }.sortedBy { it.displayName }
    fun find(id: String): CheckinPlugin? = byId[id]
}

/** Built-in candidate metadata; these do NOT count as installed or usable until implemented. */
object PluginCandidates {
    val workbuddyCn = PluginDescriptor(
        "cn.workbuddy.daily", "WorkBuddy 国内版", 1, PluginCategory.UTILITY,
        PluginAuth.OFFICIAL_BROWSER, setOf("www.codebuddy.cn")
    )
    val qoderCn = PluginDescriptor(
        "cn.qoder.daily", "Qoder CN", 1, PluginCategory.UTILITY,
        PluginAuth.DEVICE_CODE, setOf("openapi.qoder.com.cn")
    )
}
