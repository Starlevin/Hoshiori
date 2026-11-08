package com.glasssign.app.plugins

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * Every Intent this page can fire, in one place, so the "no silent install, no silent uninstall"
 * rule is auditable in one screen.
 *
 * WHAT IS DELIBERATELY ABSENT:
 *  - No `ACTION_INSTALL_PACKAGE` with a `sessionId`/`PackageInstaller.Session` write: that path
 *    needs the `INSTALL_PACKAGES` permission, which a normal app cannot hold and must not request.
 *  - No silent removal of the other app: [uninstallRequest] only raises the system confirmation
 *    dialog, and [appDetails] only opens the system app-details screen. Neither touches 领签's data
 *    without the user confirming it in the system UI, and 星栞 never learns the outcome — the next
 *    resume re-measures whatever really happened.
 */
class PluginIntents(private val context: Context) {

    /** Where the plugin's own facts live, for the user to check. Plain browser navigation.
     *  NEW_TASK is required because PluginsViewModel starts it from the application context. */
    fun source(): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(PluginSpec.SOURCE_URL))
        .addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * 领签 in the user's app store.
     *
     * `market://search` is not in the platform `queries` requirements, but a store app must be
     * *visible* to be resolved — and `Intent.resolveActivity` / `startActivity` now apply package
     * visibility filtering on API 30+. The `<intent><action VIEW/><data scheme="market"/></intent>`
     * block in the manifest is what makes the market branch reachable; without it the query returns
     * null on Android 11+ and [PluginsViewModel.openStore] would always fall back to the browser.
     */
    fun storeSearch(): Intent = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("market://search?q=${PluginSpec.PACKAGE_NAME}"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** System "install unknown apps" screen for THIS app (needed before the package URI is accepted). */
    fun unknownSourcesSettings(): Intent? =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }

    /** True when this app may currently hand an APK to the system installer on API 26+. */
    fun canInstallPackages(): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    /**
     * The system install page for the verified file.
     *
     * The URI must come from [FileProvider]; a raw `file://` URI would be blocked by
     * StrictMode/FileUriExposedException on API 24+, and a `content://` URI from our own provider
     * needs no permission grant beyond the one-time read flag below.
     */
    fun install(apk: File): Intent {
        val authority = context.packageName + PluginSpec.FILE_PROVIDER_SUFFIX
        val uri = FileProvider.getUriForFile(context, authority, apk)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /** Opens the system app-details page (fallback when no installer accepts the uninstall request). */
    fun appDetails(): Intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", PluginSpec.PACKAGE_NAME, null)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * The system "uninstall app" confirmation page for 领签 — the primary, best-effort route.
     *
     * `ACTION_UNINSTALL_PACKAGE` is a *request*: the dialog belongs to the system, the user still has
     * to press 卸载, and any data removal happens only when that dialog says it will. 星栞 passes no
     * `EXTRA_RETURN_RESULT` and reads no result — the next resume simply re-measures the truth.
     *
     * The action is deprecated in the SDK but still honoured by the platform's package installer;
     * `NEW_TASK` is required because this Intent leaves the app even before it is resolved. Some
     * ROMs have no activity that answers it; [PluginsViewModel] then falls back to [appDetails],
     * which carries the same system uninstall button.
     */
    fun uninstallRequest(): Intent = Intent(Intent.ACTION_UNINSTALL_PACKAGE).apply {
        data = Uri.fromParts("package", PluginSpec.PACKAGE_NAME, null)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * True when some system activity answered the uninstall request (checked without launching).
     *
     * A `false` here is NOT proof that uninstalling is impossible — only that this lookup found
     * nothing, which on API 30+ can also mean the package installer is not visible under the current
     * `queries`. That is why the caller treats it as "try the dialog, else app details" instead of as
     * a hard capability check, and why `launch()` failing is the real fallback trigger.
     */
    fun canResolveUninstall(): Boolean = runCatching {
        uninstallRequest().resolveActivity(context.packageManager) != null
    }.getOrDefault(false)

    /**
     * Launch intent for 领签, resolved at call time. `null` means the platform has no launchable
     * activity for it — the caller reports that instead of pretending it opened.
     */
    fun launch(): Intent? = context.packageManager.getLaunchIntentForPackage(PluginSpec.PACKAGE_NAME)?.apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
