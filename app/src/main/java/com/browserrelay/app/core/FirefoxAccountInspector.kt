package com.browserrelay.app.core

import com.browserrelay.app.root.RootHelper

/**
 * Reads only the small, non-secret account metadata needed by the UI.
 *
 * Firefox release/beta stores the Firefox Account state as plain XML in
 * `shared_prefs/fxaAppState.xml` (shared preference name `fxaAppState`, key
 * `fxaState`). Nightly/debug normally uses SecureAbove22Preferences and an
 * AndroidKeyStore key bound to the source UID.
 *
 * The account is still transferable: the migration script runs a small helper
 * under the source UID, performs the decrypt with the source key, and writes
 * the resulting plaintext state for the target. `secureOnly` therefore means
 * "the live state exists only in Keystore", not "cannot be copied". A plain
 * fallback may also exist; the migration prefers the live Keystore state and
 * only uses the fallback if decryption fails.
 */
data class FirefoxAccountState(
    val found: Boolean = false,
    val email: String? = null,
    val secureOnly: Boolean = false,
    val secureFallback: Boolean = false,
    /** Plain-text shared_prefs XML the target can read, when one exists. */
    val plainFile: String? = null
) {
    val canTransfer: Boolean get() = found
}

object FirefoxAccountInspector {

    suspend fun inspect(packageName: String): FirefoxAccountState {
        val dataDir = findDataDir(packageName) ?: return FirefoxAccountState()
        val prefs = "$dataDir/shared_prefs"
        val secureCandidates = listOf(
            "$prefs/fxaStateAC_kp_post_m.xml",
            "$prefs/fxaStateAC.xml"
        )

        // fxaStatePrefAC only records that the channel uses a secure store;
        // it does not prove an account exists. The encrypted file is evidence
        // only when it actually contains a non-empty fxaState value. A 65-byte
        // file with an empty <string name="fxaState" /> is just a migration
        // marker, not an account.
        var hasSecure = false
        for (candidate in secureCandidates) {
            if (!BrowserPaths.exists(candidate)) continue
            val content = readFile(candidate)
            if (hasNonEmptyState(content)) {
                hasSecure = true
                break
            }
        }
        val secureChannel = isSecureAccountPackage(packageName)

        // Firefox Beta/Release read `fxaAppState.xml`. Nightly normally writes
        // the secure store, but it can leave an older plaintext copy in either
        // fxaAppState.xml or fxaStateAC_kp_pre_m.xml. Both have the same
        // `fxaState` key, so the pre-M file is a usable account source too.
        val candidates = listOf(
            "$prefs/fxaAppState.xml",
            "$prefs/fxaStateAC_kp_pre_m.xml"
        )
        var plainFile: String? = null
        var raw = ""
        for (candidate in candidates) {
            if (!BrowserPaths.exists(candidate)) continue
            val content = readFile(candidate)
            if (hasNonEmptyState(content)) {
                plainFile = candidate
                raw = content
                break
            }
        }

        if (plainFile == null && !hasSecure) {
            return FirefoxAccountState()
        }

        return FirefoxAccountState(
            found = true,
            email = extractEmail(raw),
            secureOnly = plainFile == null && hasSecure,
            secureFallback = hasSecure || secureChannel,
            plainFile = plainFile
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

    private fun readFile(path: String): String = try {
        RootHelper.exec("cat ${BrowserPaths.q(path)}").stdout
    } catch (_: Exception) {
        ""
    }

    /**
     * Require real content. An empty `<string name="fxaState" />` is what a
     * Nightly build leaves behind after moving the account into Keystore.
     */
    private fun hasNonEmptyState(content: String): Boolean =
        content.contains("name=\"fxaState\"") &&
            Regex("""name="fxaState"[^>]*>[^<]+""").containsMatchIn(content)

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
