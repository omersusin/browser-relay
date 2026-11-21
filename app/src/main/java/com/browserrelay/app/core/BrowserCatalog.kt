package com.browserrelay.app.core

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import com.browserrelay.app.model.BrowserType
import java.util.Locale

/** A browser that is actually installed on the device. */
data class InstalledBrowser(
    val packageName: String,
    val label: String,
    val type: BrowserType,
    val icon: Drawable?,
    val isKnown: Boolean = true
)

/**
 * Known browser packages plus a package-manager lookup. The manifest declares
 * these packages in <queries>, so no QUERY_ALL_PACKAGES permission is needed.
 */
object BrowserCatalog {

    private data class Known(val pkg: String, val label: String, val type: BrowserType)

    private val KNOWN = listOf(
        Known("org.mozilla.firefox", "Firefox", BrowserType.GECKO),
        Known("org.mozilla.firefox_beta", "Firefox Beta", BrowserType.GECKO),
        Known("org.mozilla.fenix.nightly", "Firefox Nightly", BrowserType.GECKO),
        Known("org.mozilla.fenix", "Firefox Preview", BrowserType.GECKO),
        Known("org.mozilla.focus", "Firefox Focus", BrowserType.GECKO),
        Known("org.mozilla.klar", "Klar", BrowserType.GECKO),
        Known("org.mozilla.fennec_fdroid", "Fennec F-Droid", BrowserType.GECKO),
        Known("org.torproject.torbrowser", "Tor Browser", BrowserType.GECKO),
        Known("org.torproject.torbrowser_alpha", "Tor Browser Alpha", BrowserType.GECKO),
        Known("us.spotco.fennec_dos", "Mull", BrowserType.GECKO),
        Known("io.github.forkmaintainers.iceraven", "Iceraven", BrowserType.GECKO),
        Known("org.gnu.icecat", "IceCat", BrowserType.GECKO),
        Known("com.android.chrome", "Chrome", BrowserType.CHROMIUM),
        Known("com.chrome.beta", "Chrome Beta", BrowserType.CHROMIUM),
        Known("com.chrome.dev", "Chrome Dev", BrowserType.CHROMIUM),
        Known("com.chrome.canary", "Chrome Canary", BrowserType.CHROMIUM),
        Known("org.barebrowser", "Bare Browser", BrowserType.CHROMIUM),
        Known("com.brave.browser", "Brave", BrowserType.CHROMIUM),
        Known("com.brave.browser_beta", "Brave Beta", BrowserType.CHROMIUM),
        Known("com.kiwibrowser.browser", "Kiwi Browser", BrowserType.CHROMIUM),
        Known("com.vivaldi.browser", "Vivaldi", BrowserType.CHROMIUM),
        Known("com.microsoft.emmx", "Microsoft Edge", BrowserType.CHROMIUM),
        Known("com.opera.browser", "Opera", BrowserType.CHROMIUM),
        Known("com.sec.android.app.sbrowser", "Samsung Internet", BrowserType.CHROMIUM),
        Known("com.duckduckgo.mobile.android", "DuckDuckGo", BrowserType.CHROMIUM),
        Known("com.yandex.browser", "Yandex Browser", BrowserType.CHROMIUM),
        Known("com.mi.globalbrowser", "Mi Browser", BrowserType.CHROMIUM),
        Known("com.uc.browser.en", "UC Browser", BrowserType.CHROMIUM),
        Known("com.quark.browser", "Quark", BrowserType.CHROMIUM),
        Known("io.github.jqssun.helium", "Helium", BrowserType.CHROMIUM)
    )

    private val knownByPackage = KNOWN.associateBy { it.pkg }

    fun installed(context: Context): List<InstalledBrowser> {
        val pm = context.packageManager
        val result = LinkedHashMap<String, InstalledBrowser>()

        KNOWN.forEach { known ->
            val info = applicationInfo(pm, known.pkg) ?: return@forEach
            result[known.pkg] = InstalledBrowser(
                packageName = known.pkg,
                label = runCatching { pm.getApplicationLabel(info).toString() }
                    .getOrDefault(known.label),
                type = known.type,
                icon = runCatching { pm.getApplicationIcon(info) }.getOrNull()
            )
        }

        // Android 11+ hides undeclared packages from the package manager.
        // The manifest asks for web-link handlers, so browser forks that are
        // not hard-coded in the catalog still show up here.
        queryWebBrowsers(pm).forEach { pkg ->
            if (pkg == context.packageName || result.containsKey(pkg)) return@forEach
            val info = applicationInfo(pm, pkg) ?: return@forEach
            val label = runCatching { pm.getApplicationLabel(info).toString() }
                .getOrDefault(pkg)
            result[pkg] = InstalledBrowser(
                packageName = pkg,
                label = label,
                type = detectType(pkg, label),
                icon = runCatching { pm.getApplicationIcon(info) }.getOrNull(),
                isKnown = false
            )
        }

        return result.values.sortedWith(
            compareBy({ it.type.ordinal }, { it.label.lowercase(Locale.ROOT) })
        )
    }

    fun findByPackage(context: Context, pkg: String): InstalledBrowser? {
        val pm = context.packageManager
        val info = applicationInfo(pm, pkg) ?: return null
        val known = knownByPackage[pkg]
        val label = runCatching { pm.getApplicationLabel(info).toString() }
            .getOrDefault(known?.label ?: pkg)
        return InstalledBrowser(
            packageName = pkg,
            label = label,
            type = known?.type ?: detectType(pkg, label),
            icon = runCatching { pm.getApplicationIcon(info) }.getOrNull(),
            isKnown = known != null
        )
    }

    fun labelFor(pkg: String): String = knownByPackage[pkg]?.label ?: pkg

    fun typeFor(pkg: String): BrowserType =
        knownByPackage[pkg]?.type ?: detectType(pkg, labelFor(pkg))

    fun isInstalled(context: Context, pkg: String): Boolean =
        applicationInfo(context.packageManager, pkg) != null

    private fun queryWebBrowsers(pm: PackageManager): Set<String> {
        val packages = LinkedHashSet<String>()
        listOf("https", "http").forEach { scheme ->
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("$scheme://example.com"))
                .addCategory(Intent.CATEGORY_BROWSABLE)
            val resolved = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.queryIntentActivities(
                        intent,
                        PackageManager.ResolveInfoFlags.of(0)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    pm.queryIntentActivities(intent, 0)
                }
            }.getOrDefault(emptyList())
            resolved.forEach { info ->
                info.activityInfo?.packageName?.let(packages::add)
            }
        }
        return packages
    }

    private fun detectType(pkg: String, label: String): BrowserType {
        val value = "$pkg $label".lowercase(Locale.ROOT)
        val gecko = listOf(
            "firefox", "mozilla", "gecko", "fennec", "mull", "iceraven",
            "tor browser", "icecat", "klar", "focus"
        )
        val chromium = listOf(
            "chrom", "helium", "titanium", "brave", "chrome", "edge",
            "opera", "vivaldi", "samsung", "duckduckgo", "yandex", "quark",
            "uc browser"
        )
        return when {
            gecko.any { value.contains(it) } -> BrowserType.GECKO
            chromium.any { value.contains(it) } -> BrowserType.CHROMIUM
            else -> BrowserType.UNKNOWN
        }
    }

    private fun applicationInfo(pm: PackageManager, pkg: String): android.content.pm.ApplicationInfo? =
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(pkg, 0)
            }
        }.getOrNull()
}
