package com.browserrelay.app.core

import android.content.Context
import com.browserrelay.app.model.BrowserInfo
import com.browserrelay.app.model.BrowserType
import com.browserrelay.app.model.MigrationResult
import com.browserrelay.app.root.RootHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class DataMover(private val context: Context) {

    companion object {
        private const val WORK_DIR = "/data/local/tmp/browser_relay"
        private const val SCRIPTS_DIR = "$WORK_DIR/scripts"
        private const val LOG_FILE = "$WORK_DIR/migration.log"
        private const val PUBLIC_LOG_DIR = "/sdcard/Download/BrowserRelay"

        private val SCRIPT_FILES = listOf(
            "scripts/common.sh",
            "scripts/gecko_migrate.sh",
            "scripts/chromium_migrate.sh",
            "scripts/rollback.sh"
        )

        private val KNOWN_GECKO = setOf(
            "org.mozilla.firefox", "org.mozilla.firefox_beta",
            "org.mozilla.fenix", "org.mozilla.fenix.nightly",
            "org.mozilla.focus", "org.mozilla.klar",
            "org.mozilla.fennec_fdroid",
            "org.torproject.torbrowser", "org.torproject.torbrowser_alpha",
            "us.spotco.fennec_dos",
            "io.github.nicothin.nicofox",
            "io.github.nicothin.nicofox.debug",
            "io.github.forkmaintainers.iceraven",
            "org.gnu.icecat"
        )

        private val KNOWN_CHROMIUM = setOf(
            "com.android.chrome", "com.chrome.beta", "com.chrome.dev",
            "com.brave.browser", "com.brave.browser_beta",
            "com.kiwibrowser.browser",
            "com.vivaldi.browser",
            "com.microsoft.emmx",
            "com.opera.browser",
            "com.sec.android.app.sbrowser",
            "com.duckduckgo.mobile.android",
            "org.barebrowser"
        )
    }

    private val jsonPatcher = JsonPatcher(context)
    private val crossEngineMigrator = CrossEngineMigrator(context)

    private suspend fun detectEngine(pkg: String): BrowserType {
        if (pkg in KNOWN_GECKO) return BrowserType.GECKO
        if (pkg in KNOWN_CHROMIUM) return BrowserType.CHROMIUM

        try {
            val res = RootHelper.exec(
                """dd=""
                for p in "/data/data/$pkg" "/data/user/0/$pkg"; do
                    [ -d "${'$'}p" ] && dd="${'$'}p" && break
                done
                [ -z "${'$'}dd" ] && exit 1
                if [ -d "${'$'}dd/files/mozilla" ]; then echo GECKO; exit 0; fi
                for d in app_chrome app_chromium app_brave app_vivaldi app_edge app_kiwi app_quark; do
                    [ -d "${'$'}dd/${'$'}d" ] && echo CHROMIUM && exit 0
                done"""
            )
            if ("GECKO" in res.stdout) return BrowserType.GECKO
            if ("CHROMIUM" in res.stdout) return BrowserType.CHROMIUM
        } catch (_: Exception) { }

        return BrowserType.UNKNOWN
    }

    suspend fun migrate(
        sourcePkg: String,
        targetPkg: String,
        options: MigrationOptions = MigrationOptions(),
        onProgress: suspend (MigrationResult.Progress) -> Unit
    ): MigrationResult = withContext(Dispatchers.IO) {

        try {
            PackageValidator.validateOrThrow(sourcePkg, "Source")
            PackageValidator.validateOrThrow(targetPkg, "Target")

            if (sourcePkg == targetPkg) {
                return@withContext MigrationResult.Failure("Source and target cannot be the same!")
            }

            if (!RootHelper.checkRoot()) {
                return@withContext MigrationResult.Failure(
                    "Root access is required. Grant root permission to Trestle, " +
                        "or use a Shizuku server started with root (Shizuku's ADB/UID 2000 backend " +
                        "cannot read app-private browser data)."
                )
            }

            emitProgress(onProgress, 0, "Detection", "Detecting browser engines...", 3)
            val srcEngine = detectEngine(sourcePkg)
            val dstEngine = detectEngine(targetPkg)

            if (srcEngine != BrowserType.UNKNOWN &&
                dstEngine != BrowserType.UNKNOWN &&
                srcEngine != dstEngine
            ) {
                emitProgress(
                    onProgress, 0, "Cross-engine",
                    "Cross-engine transfer: ${srcEngine.name} -> ${dstEngine.name}", 6
                )
                return@withContext crossEngineMigrator.migrate(
                    sourcePkg, targetPkg, srcEngine, dstEngine, options, onProgress
                )
            }

            val engine = when {
                srcEngine != BrowserType.UNKNOWN -> srcEngine
                dstEngine != BrowserType.UNKNOWN -> dstEngine
                else -> return@withContext MigrationResult.Failure(
                    "Could not determine the browser engine for $sourcePkg or $targetPkg. " +
                        "Open both browsers once and retry."
                )
            }

            emitProgress(onProgress, 0, "Preparation", "Extracting scripts...", 5)
            if (!extractScripts()) {
                return@withContext MigrationResult.Failure("Script extraction failed!")
            }

            extractSqlite3Binary()

            val scriptName = when (engine) {
                BrowserType.GECKO -> "gecko_migrate.sh"
                BrowserType.CHROMIUM -> "chromium_migrate.sh"
                else -> return@withContext MigrationResult.Failure("Engine selection failed")
            }

            emitProgress(onProgress, 0, "Initializing", "Engine: ${engine.name}", 8)
            val logLines = mutableListOf<String>()
            val warnings = mutableListOf<String>()
            val errors = mutableListOf<String>()
            var phase = 0
            var phaseName = ""

            val exitCode = RootHelper.execStreaming(
                scriptPath = "$SCRIPTS_DIR/$scriptName",
                args = listOf(
                    sourcePkg,
                    targetPkg,
                    if (options.copyFirefoxAccount) "1" else "0",
                    if (options.includeExtensions) "1" else "0",
                    if (options.includeOpenTabs) "1" else "0",
                    context.packageName,
                    context.applicationInfo.sourceDir
                )
            ) { line ->
                logLines.add(line)
                val phaseMatch = Regex("""PHASE\s+(\d+):\s*(.*)""").find(line)
                if (phaseMatch != null) {
                    phase = phaseMatch.groupValues[1].toIntOrNull() ?: phase
                    phaseName = phaseMatch.groupValues[2].trim()
                }
                emitProgress(onProgress, phase, phaseName, line, progressPct(phase, engine))
                if ("[WARN]" in line) warnings.add(line.substringAfter("[WARN]").trim())
                if ("[ERR]" in line) errors.add(line.substringAfter("[ERR]").trim())
            }

            if (engine == BrowserType.CHROMIUM) {
                kotlinPatchChromium(sourcePkg, targetPkg).forEach { if (!it.success) warnings.add(it.message) }
            } else if (engine == BrowserType.GECKO) {
                kotlinPatchGecko(sourcePkg, targetPkg).forEach { if (!it.success) warnings.add(it.message) }
                if (options.includeExtensions) {
                    verifyGeckoExtensions(sourcePkg, targetPkg)?.let { warnings.add(it) }
                }
                if (options.copyFirefoxAccount) {
                    repairFirefoxAccount(sourcePkg, targetPkg)?.let { warnings.add(it) }
                    verifyFirefoxAccount(sourcePkg, targetPkg)?.let { warnings.add(it) }
                }
            }

            exportDiagnostics(sourcePkg, targetPkg, exitCode, errors, warnings, logLines)
            buildResult(exitCode, errors, warnings, logLines)

        } catch (e: Exception) {
            exportDiagnostics(
                sourcePkg, targetPkg, -1,
                listOf(e.message ?: e.javaClass.simpleName),
                emptyList(), emptyList()
            )
            MigrationResult.Failure("Error: ${e.message}", e.stackTraceToString().take(1000), LOG_FILE)
        }
    }

    suspend fun listBackups(): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        try {
            val res = RootHelper.exec("ls -d ${WORK_DIR}/backup_* 2>/dev/null | sort -r")
            val pattern = Regex("""backup_(\d{8})_(\d{6})""")
            res.outputLines.mapNotNull { path ->
                val match = pattern.find(path) ?: return@mapNotNull null
                val (datePart, timePart) = match.destructured
                val date = "${datePart.substring(0, 4)}/${datePart.substring(4, 6)}/${datePart.substring(6, 8)}"
                val time = "${timePart.substring(0, 2)}:${timePart.substring(2, 4)}:${timePart.substring(4, 6)}"
                path to "$date $time"
            }
        } catch (_: Exception) { emptyList() }
    }

    suspend fun clearBackups(): Int = withContext(Dispatchers.IO) {
        try {
            val count = RootHelper.exec("ls -d ${WORK_DIR}/backup_* 2>/dev/null | wc -l").stdout.trim().toIntOrNull() ?: 0
            if (count > 0) {
                RootHelper.exec("rm -rf ${WORK_DIR}/backup_*")
            }
            count
        } catch (_: Exception) { 0 }
    }

    suspend fun rollback(backupPath: String, onLine: suspend (String) -> Unit): Boolean = withContext(Dispatchers.IO) {
        extractScripts()
        val code = RootHelper.execStreaming("$SCRIPTS_DIR/rollback.sh", listOf(backupPath)) { line ->
            withContext(Dispatchers.Main) { onLine(line) }
        }
        code == 0
    }

    private suspend fun extractScripts(): Boolean = withContext(Dispatchers.IO) {
        try {
            RootHelper.exec("mkdir -p '$SCRIPTS_DIR'")
            for (path in SCRIPT_FILES) {
                val name = File(path).name
                val target = "$SCRIPTS_DIR/$name"
                val content = try { context.assets.open(path).bufferedReader().readText() } catch (e: Exception) { return@withContext false }
                val tmp = File(context.cacheDir, "s_$name").apply { writeText(content) }
                val result = RootHelper.execMultiple(listOf("cp -f '${tmp.absolutePath}' '$target'", "chmod 755 '$target'", "sed -i 's/\\r$//' '$target'"))
                tmp.delete()
                if (!result.success) return@withContext false
            }
            true
        } catch (_: Exception) { false }
    }

    private suspend fun extractSqlite3Binary() = withContext(Dispatchers.IO) {
        try {
            val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
            val input = try { context.assets.open("bin/$abi/sqlite3") } catch (e: Exception) { return@withContext }
            val tmp = File(context.cacheDir, "sqlite3")
            tmp.outputStream().use { input.copyTo(it) }
            RootHelper.execMultiple(listOf("cp -f '${tmp.absolutePath}' '$WORK_DIR/sqlite3'", "chmod 755 '$WORK_DIR/sqlite3'"))
            tmp.delete()
        } catch (_: Exception) { }
    }

    private suspend fun kotlinPatchChromium(srcPkg: String, dstPkg: String): List<JsonPatcher.PatchResult> {
        val results = mutableListOf<JsonPatcher.PatchResult>()
        val dst = BrowserPaths.chromiumProfile(dstPkg)
        if (dst != null) {
            val dstBaseName = dst.baseDir.substringAfterLast('/')
            // Secure Preferences is created lazily by Chromium. Its absence is
            // normal and must not turn a successful migration into a warning.
            if (BrowserPaths.exists(dst.securePreferences)) {
                val result = jsonPatcher.neutralizeSecurePreferences(
                    dst.securePreferences, srcPkg, dstPkg
                )
                if (!result.success) results.add(result)
            }
            if (BrowserPaths.exists(dst.preferences)) {
                val result = jsonPatcher.patchPreferences(
                    dst.preferences, srcPkg, dstPkg, dstBaseName, dstBaseName
                )
                if (!result.success) results.add(result)
                val sanitized = jsonPatcher.sanitizeChromiumAccountState(dst.preferences)
                if (!sanitized.success) results.add(sanitized)
            }
        } else {
            results.add(JsonPatcher.PatchResult(false, "Could not locate Chromium profile for $dstPkg"))
        }
        return results
    }

    /**
     * The shell script copies fxaAppState.xml, but a failed cp/chown or a
     * leftover Keystore file on the target can still leave the account card
     * signed out. Retry the small, plaintext part in Kotlin once the heavy
     * profile copy has finished. Secure Nightly/debug state is UID-bound and
     * is intentionally never touched here.
     */
    private suspend fun repairFirefoxAccount(srcPkg: String, dstPkg: String): String? {
        val source = FirefoxAccountInspector.inspect(srcPkg)
        // Nightly's live state is handled by the migration script's Keystore
        // bridge. Do not overwrite that result with an older plaintext
        // fallback in the post-migration repair pass.
        if (!source.found || source.secureOnly || source.secureFallback) return null
        // Always rewrite the plaintext state from the source. The target may
        // already contain its own account (or a half-copied file), and leaving
        // it in place would silently keep the wrong account signed in.

        val dstData = BrowserPaths.dataDir(dstPkg) ?: return null
        val dstPrefs = "$dstData/shared_prefs"
        // fxaAppState.xml is the Beta/Release location; Nightly may only have
        // the same state in fxaStateAC_kp_pre_m.xml. Both use the fxaState key.
        val srcFile = source.plainFile ?: return null
        if (!BrowserPaths.exists(srcFile)) return null

        val dstFile = "$dstPrefs/fxaAppState.xml"
        val targetUid = BrowserPaths.uid(dstPkg)
        val prefsFile = "$dstPrefs/fenix_preferences.xml"
        val pkgPrefsFile = "$dstPrefs/${dstPkg}_preferences.xml"
        val commands = mutableListOf(
            "mkdir -p ${BrowserPaths.q(dstPrefs)} 2>/dev/null || true",
            "cp -f ${BrowserPaths.q(srcFile)} ${BrowserPaths.q(dstFile)} 2>/dev/null",
            // A target secure store wins over the plaintext one in the
            // inspector and can hide the transplanted account.
            "rm -f ${BrowserPaths.q("$dstPrefs/fxaStateAC.xml")} " +
                "${BrowserPaths.q("$dstPrefs/fxaStatePrefAC.xml")} " +
                "${BrowserPaths.q("$dstPrefs/fxaStateAC_kp_post_m.xml")} " +
                "${BrowserPaths.q("$dstPrefs/fxaStateAC_kp_pre_m.xml")} " +
                "2>/dev/null || true",
            "for f in ${BrowserPaths.q(prefsFile)} ${BrowserPaths.q(pkgPrefsFile)}; do " +
                "if [ -f \"${'$'}f\" ]; then " +
                "sed -i 's/name=\"pref_key_fxa_signed_in\" value=\"false\"/" +
                "name=\"pref_key_fxa_signed_in\" value=\"true\"/' \"${'$'}f\" " +
                "2>/dev/null || true; fi; done"
        )
        if (!targetUid.isNullOrBlank()) {
            commands.add(
                "chown ${targetUid}:${targetUid} ${BrowserPaths.q(dstFile)} " +
                    "${BrowserPaths.q(prefsFile)} ${BrowserPaths.q(pkgPrefsFile)} " +
                    "2>/dev/null || true"
            )
        }
        commands.add(
            "chmod 660 ${BrowserPaths.q(dstFile)} ${BrowserPaths.q(prefsFile)} " +
                "${BrowserPaths.q(pkgPrefsFile)} 2>/dev/null || true"
        )
        // cp/sed create files as root; restore the SELinux label too, or
        // Firefox's own process cannot read the transplanted account.
        commands.add(
            "command -v restorecon >/dev/null 2>&1 && " +
                "restorecon -F ${BrowserPaths.q(dstFile)} ${BrowserPaths.q(prefsFile)} " +
                "${BrowserPaths.q(pkgPrefsFile)} 2>/dev/null || true"
        )
        RootHelper.execMultiple(commands)

        val after = FirefoxAccountInspector.inspect(dstPkg)
        return if (after.found && !after.secureOnly) {
            null
        } else {
            "Firefox Account: the copied plaintext state is still missing in the target. " +
                "Open Firefox once; if it shows signed out, sign in (the rest of the data is intact)."
        }
    }

    private suspend fun verifyFirefoxAccount(srcPkg: String, dstPkg: String): String? {
        val target = FirefoxAccountInspector.inspect(dstPkg)
        val source = FirefoxAccountInspector.inspect(srcPkg)
        if (!source.found) return null
        return when {
            target.found && !target.secureOnly -> null
            target.secureOnly ->
                "Firefox Account: the target still has a secure-store file. " +
                    "Open Firefox, sign out/in once if the account screen is blank."
            source.secureOnly ->
                "Firefox Account: Nightly's Keystore state could not be written to the target. " +
                    "The migration log contains the bridge error; the rest of the data is intact."
            else ->
                "Firefox Account: the copied state is missing in the target. " +
                    "Open Firefox once; if it shows signed out, sign in (the rest of the data is intact)."
        }
    }

    /**
     * Count real extensions after the target has been patched. A source
     * profile with extensions and an empty target manifest is the failure the
     * user sees as "extensions did not move"; surface it instead of finishing
     * with a green result.
     */
    private suspend fun verifyGeckoExtensions(srcPkg: String, dstPkg: String): String? {
        val src = BrowserPaths.geckoProfile(srcPkg) ?: return null
        val dst = BrowserPaths.geckoProfile(dstPkg) ?: return null
        val source = ExtensionMigrator.collectGecko(src)
        if (source.isEmpty()) return null
        val target = ExtensionMigrator.collectGecko(dst)
        if (target.isNotEmpty()) return null
        return "Extensions: ${source.size} found in the source but none in the target. " +
            "Open Firefox once and check about:addons; some unsigned add-ons must be enabled again."
    }

    private suspend fun kotlinPatchGecko(srcPkg: String, dstPkg: String): List<JsonPatcher.PatchResult> {
        val results = mutableListOf<JsonPatcher.PatchResult>()
        val dst = BrowserPaths.geckoProfile(dstPkg)
        val src = BrowserPaths.geckoProfile(srcPkg)
        val dstProfile = dst?.profileDir
        val srcProfile = src?.profileDir
        if (dstProfile != null && srcProfile != null) {
            val srcExtRoot = geckoExtensionRoot(src) ?: srcProfile
            val dstExtRoot = geckoExtensionRoot(dst) ?: dstProfile
            val srcExtensions = "$srcExtRoot/extensions.json"
            val dstExtensions = "$dstExtRoot/extensions.json"
            if (BrowserPaths.exists(srcExtensions) && BrowserPaths.exists(dstExtensions)) {
                val result = jsonPatcher.patchGeckoExtensionsJson(
                    dstExtensions,
                    srcPkg,
                    dstPkg,
                    srcExtRoot.substringAfterLast('/'),
                    dstExtRoot.substringAfterLast('/')
                )
                if (!result.success) results.add(result)
            }
            val srcPrefs = "$srcExtRoot/prefs.js"
            val dstPrefs = "$dstExtRoot/prefs.js"
            if (BrowserPaths.exists(srcPrefs) && BrowserPaths.exists(dstPrefs)) {
                val result = jsonPatcher.syncGeckoUuids(srcPrefs, dstPrefs)
                if (!result.success) results.add(result)
            }
        } else {
            results.add(JsonPatcher.PatchResult(false, "Could not locate Gecko profile for $srcPkg/$dstPkg"))
        }
        return results
    }

    /**
     * A few Fenix forks keep extensions.json/extensions under files/ instead
     * of the active profile. Pick whichever root actually holds them so the
     * post-copy JSON and UUID repair does not silently no-op.
     */
    private suspend fun geckoExtensionRoot(profile: GeckoProfile): String? {
        val roots = listOfNotNull(profile.profileDir, profile.filesDir).distinct()
        return roots.firstOrNull { root ->
            BrowserPaths.exists("$root/extensions.json") ||
                BrowserPaths.exists("$root/extensions")
        }
    }

    private suspend fun findChromiumBase(pkg: String): String {
        val r = RootHelper.exec(
            """dd=""
            for p in "/data/data/$pkg" "/data/user/0/$pkg"; do
                [ -d "${'$'}p" ] && dd="${'$'}p" && break
            done
            [ -z "${'$'}dd" ] && exit 1
            for d in app_chrome app_chromium app_brave app_vivaldi; do
                [ -d "${'$'}dd/${'$'}d" ] && echo "${'$'}d" && exit 0
            done"""
        )
        return r.stdout.trim()
    }

    private suspend fun findGeckoProfile(pkg: String): String {
        val r = RootHelper.exec(
            """dd=""
            for p in "/data/data/$pkg" "/data/user/0/$pkg"; do
                [ -d "${'$'}p" ] && dd="${'$'}p" && break
            done
            [ -z "${'$'}dd" ] && exit 1
            md="${'$'}dd/files/mozilla"
            if [ -d "${'$'}md" ]; then
                for d in "${'$'}md"/*/; do
                    [ -d "${'$'}d" ] && echo "${'$'}{d%/}" && exit 0
                done
            fi
            f=${'$'}(find "${'$'}dd" -name "places.sqlite" -type f 2>/dev/null | head -1)
            if [ -n "${'$'}f" ]; then
                dirname "${'$'}f"
                exit 0
            fi
            f=${'$'}(find "${'$'}dd" -name "prefs.js" -type f 2>/dev/null | head -1)
            if [ -n "${'$'}f" ]; then
                dirname "${'$'}f"
                exit 0
            fi"""
        )
        return r.stdout.trim()
    }

    /**
     * 2.1.3 could only show the log in a dialog, which is hard to copy from a
     * phone. Write the same information to shared storage, together with the
     * profile paths and source/target file sizes needed to tell "copied to an
     * inactive profile" apart from "the migration did not run at all".
     */
    private suspend fun exportDiagnostics(
        srcPkg: String,
        dstPkg: String,
        exit: Int,
        errs: List<String>,
        warns: List<String>,
        lines: List<String>
    ) {
        val access = try { RootHelper.accessLabel() } catch (_: Exception) { "unknown" }
        val srcData = try { BrowserPaths.dataDir(srcPkg) } catch (_: Exception) { null }
        val dstData = try { BrowserPaths.dataDir(dstPkg) } catch (_: Exception) { null }
        val srcGecko = try { BrowserPaths.geckoProfile(srcPkg) } catch (_: Exception) { null }
        val dstGecko = try { BrowserPaths.geckoProfile(dstPkg) } catch (_: Exception) { null }
        val srcAccount = try { FirefoxAccountInspector.inspect(srcPkg) } catch (_: Exception) { null }
        val dstAccount = try { FirefoxAccountInspector.inspect(dstPkg) } catch (_: Exception) { null }

        val text = buildString {
            appendLine("=== Trestle diagnostics ===")
            appendLine("time=${System.currentTimeMillis()}")
            appendLine("source=$srcPkg")
            appendLine("target=$dstPkg")
            appendLine("exit=$exit")
            appendLine("access=$access")
            appendLine("sourceData=$srcData")
            appendLine("targetData=$dstData")
            appendLine("sourceProfile=${srcGecko?.profileDir}")
            appendLine("targetProfile=${dstGecko?.profileDir}")
            appendLine("sourcePlaces=${srcGecko?.placesDb}")
            appendLine("targetPlaces=${dstGecko?.placesDb}")
            appendLine("sourceAccountFound=${srcAccount?.found} secureOnly=${srcAccount?.secureOnly}")
            appendLine("targetAccountFound=${dstAccount?.found} secureOnly=${dstAccount?.secureOnly}")
            appendLine("errors=${errs.size} warnings=${warns.size} lines=${lines.size}")
            appendLine("--- warnings ---")
            warns.take(40).forEach { appendLine(it.take(500)) }
            appendLine("--- log tail ---")
            lines.takeLast(120).forEach { appendLine(it.take(500)) }
        }
        val tmp = File(context.cacheDir, "browser_relay_diagnostic.txt")
        try { tmp.writeText(text) } catch (_: Exception) { }
        try {
            RootHelper.execMultiple(
                listOf(
                    "mkdir -p '$PUBLIC_LOG_DIR' 2>/dev/null || true",
                    "cp -f '${tmp.absolutePath}' '$PUBLIC_LOG_DIR/diagnostic-latest.txt' 2>/dev/null || true",
                    "cp -f '$LOG_FILE' '$PUBLIC_LOG_DIR/migration-latest.log' 2>/dev/null || true",
                    "chmod 644 '$PUBLIC_LOG_DIR/diagnostic-latest.txt' " +
                        "'$PUBLIC_LOG_DIR/migration-latest.log' 2>/dev/null || true"
                )
            )
        } catch (_: Exception) { }
        try { tmp.delete() } catch (_: Exception) { }
    }

    private fun progressPct(p: Int, e: BrowserType) = if (e == BrowserType.GECKO) intArrayOf(12,22,45,70,85,92).getOrElse(p){92} else intArrayOf(8,22,38,52,62,75,85,92).getOrElse(p){95}
    private suspend fun emitProgress(cb: suspend (MigrationResult.Progress) -> Unit, ph: Int, nm: String, d: String, pct: Int) = withContext(Dispatchers.Main) { cb(MigrationResult.Progress(ph, nm, d, pct)) }
    private fun buildResult(exit: Int, errs: List<String>, warns: List<String>, lines: List<String>) =
        if (exit == 0 && errs.isEmpty()) {
            MigrationResult.Success("Migration successful!", warns, LOG_FILE)
        } else {
            val detail = (errs.take(6) + lines.takeLast(10)).joinToString("\n")
            MigrationResult.Failure("Failed (exit=$exit)", detail, LOG_FILE)
        }
}
