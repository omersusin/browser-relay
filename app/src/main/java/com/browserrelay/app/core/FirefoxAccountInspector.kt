package com.browserrelay.app.core

import com.browserrelay.app.root.RootHelper

/**
 * Reads only the small, non-secret account metadata needed by the UI.
 *
 * Firefox release/beta stores the Firefox Account state as plain XML in
 * `shared_prefs/fxaAppState.xml` (shared preference name `fxaAppState`, key
 * `fxaState`). Nightly/debug normally uses SecureAbove22Preferences and a
 * Keystore key bound to the source UID, which cannot be transplanted.
 *
 * Nightly builds often keep an older but still readable plain-text
 * `fxaAppState.xml` next to the secure store. The first release copied that
 * file and the account opened, so prefer it whenever it has real content.
 * `secureOnly` is reserved for profiles that only have the undecryptable
 * Keystore store.
 */
data class FirefoxAccountState(
    val found: Boolean = false,
    val email: String? = null,
    val secureOnly: Boolean = false,
    val secureFallback: Boolean = false
) {
    val canTransfer: Boolean get() = found && !secureOnly
}

object FirefoxAccountInspector {

    suspend fun inspect(packageName: String): FirefoxAccountState {
        val dataDir = findDataDir(packageName) ?: return FirefoxAccountState()
        val prefs = "$dataDir/shared_prefs"
        val plain = "$prefs/fxaAppState.xml"
        val secureA = "$prefs/fxaStateAC.xml"
        val secureB = "$prefs/fxaStatePrefAC.xml"
        val securePost = "$prefs/fxaStateAC_kp_post_m.xml"
        val securePre = "$prefs/fxaStateAC_kp_pre_m.xml"

        val hasPlain = BrowserPaths.exists(plain)
        val hasSecure = BrowserPaths.exists(secureA) || BrowserPaths.exists(secureB) ||
            BrowserPaths.exists(securePost) || BrowserPaths.exists(securePre)
        val secureChannel = isSecureAccountPackage(packageName)
        val plainHasState = hasPlain && try {
            // Require actual content: an empty <string name="fxaState" /> is
            // what a Nightly build leaves behind after moving to its secure
            // store, and copying that would look like a successful transfer.
            RootHelper.exec(
                "grep -q 'name=\"fxaState\"[^>]*>[^<]' ${BrowserPaths.q(plain)} " +
                    "2>/dev/null && echo YES || echo NO"
            ).stdout.contains("YES")
        } catch (_: Exception) {
            false
        }

        // Only a profile without a usable plain-text state is untransferable.
        // Nightly keeps its secure store in `fxaStatePrefAC.xml` and
        // `fxaStateAC_kp_*`, but a stale plain-text fxaState is still the
        // account the target can actually read. This is what v1 copied.
        if (hasSecure && !plainHasState) {
            return FirefoxAccountState(found = true, secureOnly = true)
        }
        if (!plainHasState) {
            return FirefoxAccountState()
        }

        val raw = try {
            RootHelper.exec("cat ${BrowserPaths.q(plain)}").stdout
        } catch (_: Exception) {
            ""
        }
        return FirefoxAccountState(
            found = true,
            email = extractEmail(raw),
            secureOnly = false,
            secureFallback = hasSecure || secureChannel
        )
    }

    /**
     * Firefox Nightly's package is `org.mozilla.fenix` (the word "nightly"
     * does not appear in it). Its build channel enables SecureAbove22Prefs.
     * Debug builds and forks with "nightly"/"debug" in the package use the
     * same UID-bound store.
     */
    private fun isSecureAccountPackage(packageName: String): Boolean {
        val pkg = packageName.lowercase()
        return pkg == "org.mozilla.fenix" ||
            pkg == "org.mozilla.fenix.nightly" ||
            pkg.contains("nightly") ||
            pkg.contains("debug")
    }

    private suspend fun findDataDir(packageName: String): String? {
        val result = RootHelper.exec(
            """for p in "/data/data/${packageName}" "/data/user/0/${packageName}"; do
                [ -d "${'$'}p" ] && echo "${'$'}p" && exit 0
            done"""
        )
        return result.outputLines.firstOrNull { it.startsWith("/") }
    }

    private fun extractEmail(raw: String): String? {
        // Quotes may be XML-escaped depending on the platform's XmlSerializer.
        val normalized = raw
            .replace("&quot;", "\"")
            .replace("&#34;", "\"")
            .replace("&amp;", "&")
        val patterns = listOf(
            Regex("\"email\"\\s*:\\s*\"([^\"]+)\""),
            Regex("\"email\"\\s*:\\s*\"([^\"]+)\""),
            Regex("email=([^,\\s]+)")
        )
        return patterns.firstNotNullOfOrNull { regex ->
            regex.find(normalized)?.groupValues?.getOrNull(1)
                ?.takeIf { it.contains('@') }
        }
    }
}
