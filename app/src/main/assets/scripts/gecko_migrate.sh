#!/system/bin/sh
# ============================================================
#  gecko_migrate.sh v9
#  Fenix fix: DBs and tabs under files/
# ============================================================
SCRIPT_DIR="$(cd "$(dirname "$0")" 2>/dev/null && pwd)"
if [ -z "$SCRIPT_DIR" ]; then
    SCRIPT_DIR="/data/local/tmp/browser_relay/scripts"
fi
. "${SCRIPT_DIR}/common.sh"

SRC="$1"
DST="$2"
# Optional feature flags supplied by the app:
#   $3 = copy Firefox Account state (Gecko release/beta plaintext store)
#   $4 = migrate extensions
#   $5 = migrate open tabs
MIGRATE_ACCOUNT="${3:-1}"
MIGRATE_EXTENSIONS="${4:-1}"
MIGRATE_TABS="${5:-1}"

G_PROFILE=""
G_DATA_DIR=""

# Set by main() before the copy phases. Kept global so the helpers below can
# resolve per-file locations: Fenix keeps places/cookies under files/, while
# classic Firefox keeps them in the profile directory.
G_SRC_PROFILE=""
G_SRC_FILES=""
G_DST_PROFILE=""
G_DST_FILES=""
G_DST_DB_DIR=""

gecko_src_path() {
    local name="$1"
    if [ -n "$G_SRC_FILES" ] && [ -f "${G_SRC_FILES}/${name}" ]; then
        echo "${G_SRC_FILES}/${name}"
        return 0
    fi
    if [ -n "$G_SRC_PROFILE" ] && [ -f "${G_SRC_PROFILE}/${name}" ]; then
        echo "${G_SRC_PROFILE}/${name}"
        return 0
    fi
    return 1
}

gecko_dst_path() {
    local name="$1"
    if [ -n "$G_DST_DB_DIR" ] && [ -f "${G_DST_DB_DIR}/${name}" ]; then
        echo "${G_DST_DB_DIR}/${name}"
        return 0
    fi
    if [ -n "$G_DST_FILES" ] && [ -f "${G_DST_FILES}/${name}" ]; then
        echo "${G_DST_FILES}/${name}"
        return 0
    fi
    if [ -n "$G_DST_PROFILE" ] && [ -f "${G_DST_PROFILE}/${name}" ]; then
        echo "${G_DST_PROFILE}/${name}"
        return 0
    fi
    if [ -n "$G_DST_DB_DIR" ]; then
        echo "${G_DST_DB_DIR}/${name}"
        return 0
    fi
    if [ -n "$G_DST_FILES" ]; then
        echo "${G_DST_FILES}/${name}"
        return 0
    fi
    echo "${G_DST_PROFILE}/${name}"
}

# Fenix stores its own settings in fenix_preferences.xml. Keep the account
# flag in sync with the account state we actually wrote, otherwise the account
# card can say "signed out" while the encrypted/plain state is present.
gecko_set_pref_bool() {
    local file="$1" key="$2" value="$3"
    [ -f "$file" ] || return 1
    if grep -q "name=\"${key}\"" "$file" 2>/dev/null; then
        # Basic sed syntax only: some Android su shells ship an old toybox sed
        # without -E, and a failed patch would leave the UI showing signed out.
        sed -i "s#name=\"${key}\" value=\"[^\"]*\"#name=\"${key}\" value=\"${value}\"#" \
            "$file" 2>/dev/null || true
    else
        sed -i "s#</map>#    <boolean name=\"${key}\" value=\"${value}\" />\n</map>#" \
            "$file" 2>/dev/null || true
    fi
}

gecko_pref_has_true() {
    local file="$1" key="$2"
    [ -f "$file" ] || return 1
    grep -Eq "<boolean name=\"${key}\" value=\"true\" ?/>" "$file" 2>/dev/null
}

# ============================================================
#  DATA DIRECTORY + PROFILE DISCOVERY
# ============================================================
resolve_data_dir() {
    local pkg="$1"
    G_DATA_DIR=""
    G_DATA_DIR=$(find_data_dir "$pkg")
    if [ -z "$G_DATA_DIR" ]; then
        log_error "Data directory not found: $pkg"
        return 1
    fi
    log_ok "Data directory: $G_DATA_DIR"
    return 0
}

# Pick the profile Firefox actually uses. The first Path= entry is not
# necessarily the active profile after an update or a beta/nightly switch.
# Prefer [Install*] Default=, then the [ProfileN] marked Default=1, then the
# profile whose places.sqlite was modified most recently.
gecko_profile_from_ini() {
    local ini="$1" mozilla="$2"
    [ -f "$ini" ] || return 1

    local inst def candidate
    inst=$(awk '
        /^\[/ { sec=$0; next }
        sec ~ /^\[Install/ && /^Default=/ { sub(/^Default=/, ""); print; exit }
    ' "$ini" 2>/dev/null)
    def=$(awk '
        /^\[/ { sec=$0; path=""; next }
        /^Path=/ { path=substr($0, 6) }
        sec ~ /^\[Profile[0-9]+\]/ && /^Default=1$/ { print path; exit }
    ' "$ini" 2>/dev/null)

    for candidate in "$inst" "$def"; do
        [ -n "$candidate" ] || continue
        case "$candidate" in
            /*)
                if [ -d "$candidate" ]; then
                    printf '%s\n' "$candidate"
                    return 0
                fi
                ;;
            *)
                if [ -d "${mozilla}/${candidate}" ]; then
                    printf '%s\n' "${mozilla}/${candidate}"
                    return 0
                fi
                ;;
        esac
    done

    local best="" best_t=0 d t
    for d in "$mozilla"/*/; do
        [ -d "$d" ] || continue
        t=0
        [ -f "${d}places.sqlite" ] && t=$(stat -c %Y "${d}places.sqlite" 2>/dev/null || echo 0)
        if [ "$t" -gt "$best_t" ] 2>/dev/null; then
            best_t="$t"
            best="${d%/}"
        fi
    done
    [ -n "$best" ] && printf '%s\n' "$best" && return 0
    return 1
}

find_gecko_profile() {
    local pkg="$1"
    G_PROFILE=""

    resolve_data_dir "$pkg"
    if [ -z "$G_DATA_DIR" ]; then return 1; fi

    local dd="$G_DATA_DIR"

    # 1. Search for profile under files/mozilla/
    local mozilla_dir="$dd/files/mozilla"
    if [ -d "$mozilla_dir" ]; then
        log_info "files/mozilla/ found"

        if [ -f "$mozilla_dir/profiles.ini" ]; then
            local ini_profile
            ini_profile=$(gecko_profile_from_ini "$mozilla_dir/profiles.ini" "$mozilla_dir" 2>/dev/null)
            if [ -n "$ini_profile" ] && [ -d "$ini_profile" ]; then
                G_PROFILE="$ini_profile"
                log_ok "Profile (profiles.ini): $G_PROFILE"
                return 0
            fi
        fi

        for d in "$mozilla_dir"/*/; do
            [ ! -d "$d" ] && continue
            local dname=$(basename "$d")
            case "$dname" in
                *.default*|*.svc*|*.release*|*.nightly*)
                    G_PROFILE="${d%/}"
                    log_ok "Profile (glob): $G_PROFILE"
                    return 0
                    ;;
            esac
        done

        for d in "$mozilla_dir"/*/; do
            if [ -d "$d" ]; then
                G_PROFILE="${d%/}"
                log_warn "Profile (first directory): $G_PROFILE"
                return 0
            fi
        done
    fi

    # 2. Broad search
    if [ -d "$dd/files" ]; then
        local found
        found=$(find "$dd/files" -name "places.sqlite" -type f 2>/dev/null | head -1)
        if [ -n "$found" ]; then
            G_PROFILE=$(dirname "$found")
            log_ok "Profile (find): $G_PROFILE"
            return 0
        fi
    fi

    log_error "PROFILE NOT FOUND: $pkg"
    return 1
}

ensure_dst_profile() {
    local pkg="$1"

    find_gecko_profile "$pkg"
    if [ -n "$G_PROFILE" ]; then
        log_ok "Target profile exists: $G_PROFILE"
        return 0
    fi

    log_info "Target profile missing, starting browser..."
    local attempt=1
    while [ "$attempt" -le 3 ]; do
        local intent
        intent=$(cmd package resolve-activity --brief "$pkg" 2>/dev/null | tail -1)
        if [ -n "$intent" ]; then
            am start -n "$intent" >/dev/null 2>&1
        else
            log_info "Starting with monkey (attempt $attempt)"
            monkey -p "$pkg" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
        fi
        sleep 9
        stop_pkg "$pkg"
        sleep 2

        find_gecko_profile "$pkg"
        if [ -n "$G_PROFILE" ]; then
            log_ok "Clean profile created: $G_PROFILE"
            return 0
        fi
        attempt=$((attempt + 1))
    done

    # Android may block background launches. Create the conventional profile
    # directory; Firefox initialises it on the next launch.
    local dd
    dd=$(find_data_dir "$pkg")
    if [ -n "$dd" ]; then
        local md="${dd}/files/mozilla"
        if [ ! -f "${md}/profiles.ini" ]; then
            local name="migrator.$(date +%s).default"
            mkdir -p "${md}/${name}" 2>/dev/null
            cat > "${md}/profiles.ini" << INIEOF
[Profile0]
Name=default
IsRelative=1
Path=$name
Default=1
[General]
StartWithLastProfile=1
Version=2
INIEOF
            G_PROFILE="${md}/${name}"
            log_warn "Browser did not create a profile; using empty profile: $G_PROFILE"
            return 0
        fi
    fi

    log_error "Could not create profile: $pkg"
    return 1
}

# ============================================================
#  MAIN FLOW
# ============================================================
main() {
    log_init

    log_info "============================================"
    log_info "  Gecko Migration v8 (Fenix compatible)"
    log_info "  Source: $SRC"
    log_info "  Target: $DST"
    log_info "  Options: account=$MIGRATE_ACCOUNT extensions=$MIGRATE_EXTENSIONS tabs=$MIGRATE_TABS"
    log_info "  Android: $(getprop ro.build.version.release 2>/dev/null) (SDK $(getprop ro.build.version.sdk 2>/dev/null))"
    log_info "  UID: $(id 2>/dev/null)"
    log_info "  SELinux: $(getenforce 2>/dev/null || echo unknown)"
    log_info "============================================"

    check_root
    check_sqlite3
    check_package "$SRC" || exit 1
    check_package "$DST" || exit 1

    stop_pkg "$DST"

    # ==== PHASE 0: Discovery ====
    log_info "PHASE 0: DISCOVERY"

    # Fenix writes its live tab session from onStop(). Do this before reading
    # the source profile so tabs opened in the last autosave interval are kept.
    graceful_stop_pkg "$SRC"

    find_gecko_profile "$SRC"
    local src_profile="$G_PROFILE"
    local src_dd="$G_DATA_DIR"
    local src_files="$src_dd/files"

    if [ -z "$src_profile" ]; then
        log_error "SOURCE PROFILE NOT FOUND!"
        exit 1
    fi
    log_ok "Source profile: $src_profile"
    log_ok "Source files: $src_files"
    log_info "Source UID: $(get_uid "$SRC") Target UID: $(get_uid "$DST")"
    local src_name=$(basename "$src_profile")

    local ini="${src_files}/mozilla/profiles.ini"
    if [ -f "$ini" ]; then
        log_info "Source profiles.ini:"
        while IFS= read -r line; do
            log_info "  ini| $line"
        done < "$ini"
    fi
    log_info "Source key files:"
    for n in places.sqlite cookies.sqlite tabs.sqlite prefs.js extensions.json; do
        for d in "$src_files" "$src_profile"; do
            if [ -f "${d}/${n}" ]; then
                log_info "  $(stat -c %s "${d}/${n}" 2>/dev/null || echo '?') bytes  ${d}/${n}"
                break
            fi
        done
    done

    check_disk "$src_files"

    # ==== PHASE 1: Target Profile ====
    log_info "PHASE 1: TARGET PROFILE"

    ensure_dst_profile "$DST"
    local dst_profile="$G_PROFILE"
    local dst_dd="$G_DATA_DIR"
    local dst_files="$dst_dd/files"

    if [ -z "$dst_profile" ]; then
        log_error "TARGET PROFILE NOT READY!"
        exit 1
    fi
    log_ok "Target profile: $dst_profile"
    log_ok "Target files: $dst_files"
    local dst_name=$(basename "$dst_profile")

    local dst_ini="${dst_files}/mozilla/profiles.ini"
    if [ -f "$dst_ini" ]; then
        log_info "Target profiles.ini:"
        while IFS= read -r line; do
            log_info "  ini| $line"
        done < "$dst_ini"
    fi
    log_info "Target key files:"
    for n in places.sqlite cookies.sqlite tabs.sqlite prefs.js extensions.json; do
        for d in "$dst_files" "$dst_profile"; do
            if [ -f "${d}/${n}" ]; then
                log_info "  $(stat -c %s "${d}/${n}" 2>/dev/null || echo '?') bytes  ${d}/${n}"
                break
            fi
        done
    done

    G_SRC_PROFILE="$src_profile"
    G_SRC_FILES="$src_files"
    G_DST_PROFILE="$dst_profile"
    G_DST_FILES="$dst_files"

    # Fenix stores its databases in files/; classic Gecko stores them in the
    # profile. Follow whichever layout the target already uses.
    if [ -f "${dst_files}/places.sqlite" ]; then
        G_DST_DB_DIR="$dst_files"
    elif [ -f "${dst_profile}/places.sqlite" ]; then
        G_DST_DB_DIR="$dst_profile"
    else
        G_DST_DB_DIR="$dst_files"
    fi
    log_info "Target database directory: $G_DST_DB_DIR"

    # Backup
    log_info "Backing up target..."
    mkdir -p "${BACKUP_DIR}/target_files" 2>/dev/null
    cp -rf "$dst_files/." "${BACKUP_DIR}/target_files/" 2>/dev/null
    mkdir -p "${BACKUP_DIR}/target_shared_prefs" 2>/dev/null
    cp -rf "${dst_dd}/shared_prefs/." "${BACKUP_DIR}/target_shared_prefs/" 2>/dev/null || true
    save_manifest "$DST" "GECKO" "$dst_profile" "$dst_dd" "" "files"
    log_ok "Backup: $BACKUP_DIR"

    stop_pkg "$DST"
    sleep 1

    # ==== PHASE 2: Fenix files/ Level DBs ====
    log_info "PHASE 2: FILES-LEVEL DATA"

    # --- places.sqlite (History + Bookmarks) ---
    local src_places dst_places
    src_places=$(gecko_src_path "places.sqlite" 2>/dev/null || true)
    if [ -n "$src_places" ]; then
        dst_places=$(gecko_dst_path "places.sqlite")
        log_info "places.sqlite: $src_places -> $dst_places"
        copy_db_with_sidecars "$src_places" "$dst_places" "places.sqlite"
    fi

    # --- logins.json + key4.db (Passwords) ---
    local src_file dst_file
    for name in logins.json key4.db; do
        src_file=$(gecko_src_path "$name" 2>/dev/null || true)
        if [ -n "$src_file" ]; then
            dst_file=$(gecko_dst_path "$name")
            if [ "$name" = "key4.db" ]; then
                copy_db_with_sidecars "$src_file" "$dst_file" "$name"
            else
                safe_cp "$src_file" "$dst_file" "$name"
            fi
        fi
    done

    # --- Open tabs (optional) ---
    if [ "$MIGRATE_TABS" = "1" ]; then
    # --- tabs.sqlite (Tabs) ---
    src_file=$(gecko_src_path "tabs.sqlite" 2>/dev/null || true)
    if [ -n "$src_file" ]; then
        dst_file=$(gecko_dst_path "tabs.sqlite")
        copy_db_with_sidecars "$src_file" "$dst_file" "tabs.sqlite"
    fi

    # --- Open-tab session storage (Fenix/Firefox Android) ---
    # tabs.sqlite is not the live tab session. Fenix stores the open-tab
    # session in mozilla_components_session_storage_gecko.json, and some
    # builds also have sessionstore.jsonlz4. Without these files the target
    # starts with an empty tab tray even though tabs.sqlite was copied.
    #
    # The old v5 script skipped this file because an old Iceraven/Gecko build
    # crashed on a mismatched JSON schema. Keep the guard for those legacy
    # targets, but copy it for current Fenix/Firefox Beta profiles.
    local session_skip=0
    case "$SRC $DST" in
        *iceraven*|*Iceraven*|*fennec*|*Fennec*|*tor*|*Tor*) session_skip=1 ;;
    esac

    # Remove the target's stale session before installing the source session.
    rm -f "${dst_profile}/sessionstore.jsonlz4" 2>/dev/null
    rm -rf "${dst_profile}/sessionstore-backups" 2>/dev/null

    if [ "$session_skip" -eq 1 ]; then
        log_warn "Open-tab session JSON skipped for legacy Gecko target"
    else
        local src_session="${src_files}/mozilla_components_session_storage_gecko.json"
        [ -f "$src_session" ] || src_session="${src_profile}/mozilla_components_session_storage_gecko.json"
        local dst_session="${dst_files}/mozilla_components_session_storage_gecko.json"
        rm -f "${dst_session}" "${dst_session}.bak" 2>/dev/null
        if [ -f "$src_session" ]; then
            safe_cp "$src_session" "$dst_session" "open-tab session storage"
            if [ -f "$dst_session" ]; then
                sed -i "s|${src_dd}|${dst_dd}|g; s|${src_profile}|${dst_profile}|g" \
                    "$dst_session" 2>/dev/null || true
                if grep -q "sessionStateTuples" "$dst_session" 2>/dev/null; then
                    log_ok "Open-tab session installed and validated"
                else
                    log_warn "Open-tab session file has no sessionStateTuples; target may start with no tabs"
                fi
            fi
        else
            log_info "Open-tab session storage not present in source"
        fi

        # Fallback for builds that still use a jsonlz4 sessionstore.
        if [ -f "${src_profile}/sessionstore.jsonlz4" ]; then
            safe_cp "${src_profile}/sessionstore.jsonlz4" \
                "${dst_profile}/sessionstore.jsonlz4" "sessionstore.jsonlz4"
        fi
    fi
    else
        log_info "Open-tab migration disabled; target tabs preserved"
    fi

    # --- push.sqlite ---
    src_file=$(gecko_src_path "push.sqlite" 2>/dev/null || true)
    if [ -n "$src_file" ]; then
        dst_file=$(gecko_dst_path "push.sqlite")
        copy_db_with_sidecars "$src_file" "$dst_file" "push.sqlite"
    fi

    # --- Other files-level files ---
    for f in "nimbus_messages_metadata.json" "profileInstalled" "mozilla_components_service_mars_tiles.json"; do
        safe_cp "${src_files}/${f}" "${dst_files}/${f}" "$f"
    done

    # ==== PHASE 3: Profile-Level Data ====
    log_info "PHASE 3: PROFILE-LEVEL DATA"

    for f in \
        favicons.sqlite \
        formhistory.sqlite \
        cookies.sqlite \
        cert9.db permissions.sqlite content-prefs.sqlite \
        webappsstore.sqlite \
    ; do
        src_file=$(gecko_src_path "$f" 2>/dev/null || true)
        if [ -n "$src_file" ]; then
            dst_file=$(gecko_dst_path "$f")
            copy_db_with_sidecars "$src_file" "$dst_file" "$f"
        fi
    done

    # ==== PHASE 4: Extensions ====
    log_info "PHASE 4: EXTENSION MIGRATION"

    if [ "$MIGRATE_EXTENSIONS" = "1" ]; then
    # Most Fenix builds keep extensions in the active profile. A few builds
    # and old forks keep the same files under files/ instead. Always install
    # the package into the target profile, and mirror a files/-root source
    # into the target files/ root as well so absolute paths in extensions.json
    # resolve in both layouts.
    local src_ext_root="$src_profile"
    local dst_ext_root="$dst_profile"
    local mirror_files=0
    if [ ! -d "${src_profile}/extensions" ] && [ ! -f "${src_profile}/extensions.json" ] && \
       { [ -d "${src_files}/extensions" ] || [ -f "${src_files}/extensions.json" ]; }; then
        src_ext_root="$src_files"
        mirror_files=1
        log_info "Extensions found under files/; installing into the target profile and files/"
    fi

    safe_cp "${src_ext_root}/extensions" "${dst_profile}/extensions" "extensions/"
    safe_cp "${src_ext_root}/extensions.json" "${dst_profile}/extensions.json" "extensions.json"
    safe_cp "${src_ext_root}/extension-preferences.json" "${dst_profile}/extension-preferences.json" "extension-preferences.json"
    safe_cp "${src_ext_root}/browser-extension-data" "${dst_profile}/browser-extension-data" "browser-extension-data/"

    if [ "$mirror_files" = "1" ]; then
        safe_cp "${src_files}/extensions" "${dst_files}/extensions" "extensions/ (files mirror)"
        safe_cp "${src_files}/extensions.json" "${dst_files}/extensions.json" "extensions.json (files mirror)"
        safe_cp "${src_files}/extension-preferences.json" "${dst_files}/extension-preferences.json" "extension-preferences.json (files mirror)"
        safe_cp "${src_files}/browser-extension-data" "${dst_files}/browser-extension-data" "browser-extension-data/ (files mirror)"
        # The manifest may live under files/ while its per-extension data is
        # still in the profile. Copy the profile copy too when it exists.
        safe_cp "${src_profile}/extension-preferences.json" "${dst_profile}/extension-preferences.json" "extension-preferences.json (profile)"
        safe_cp "${src_profile}/browser-extension-data" "${dst_profile}/browser-extension-data" "browser-extension-data/ (profile)"
    fi

    if [ -f "${src_profile}/storage-sync-v2.sqlite" ]; then
        safe_cp "${src_profile}/storage-sync-v2.sqlite" "${dst_profile}/storage-sync-v2.sqlite" "storage-sync (profile)"
    fi
    if [ -f "${src_files}/storage-sync-v2.sqlite" ]; then
        safe_cp "${src_files}/storage-sync-v2.sqlite" "${dst_files}/storage-sync-v2.sqlite" "storage-sync (files/)"
    fi

    # extensions.json path patching. Paths may be written with either
    # /data/data or /data/user/0, and the profile directory name differs
    # between source and target. Patch the profile segment globally so a
    # path under /data/user/0 is not left pointing at the source profile.
    local ext_jsons="${dst_profile}/extensions.json"
    if [ "$mirror_files" = "1" ]; then
        ext_jsons="$ext_jsons ${dst_files}/extensions.json"
    fi
    for ej in $ext_jsons; do
        [ -f "$ej" ] || continue
        log_info "Patching extensions.json paths: $ej"
        sed -i "s|/files/mozilla/${src_name}|/files/mozilla/${dst_name}|g" "$ej" 2>/dev/null
        sed -i "s|${src_dd}/files/mozilla/${src_name}|${dst_dd}/files/mozilla/${dst_name}|g" "$ej" 2>/dev/null
        sed -i "s|${src_dd}/|${dst_dd}/|g" "$ej" 2>/dev/null
        sed -i "s|${SRC}|${DST}|g" "$ej" 2>/dev/null
    done
    log_ok "extensions.json patched"

    # UUID synchronization. Read prefs.js from the same root as the package
    # source, but always write the UUID map into the target profile prefs.
    log_info "UUID synchronization..."
    local src_prefs="${src_ext_root}/prefs.js"
    [ -f "$src_prefs" ] || src_prefs="${src_profile}/prefs.js"
    local dst_prefs="${dst_profile}/prefs.js"
    if [ -f "$src_prefs" ]; then
        [ ! -f "$dst_prefs" ] && touch "$dst_prefs"
        for pn in "extensions.webextensions.uuids" "extensions.webextensions.ExtensionStorageIDB.enabled" "extensions.enabledScopes" "xpinstall.signatures.required"; do
            local pl=$(grep "\"${pn}\"" "$src_prefs" 2>/dev/null | head -1)
            if [ -n "$pl" ]; then
                local tmp="${dst_prefs}.tmp.$$"
                grep -v "\"${pn}\"" "$dst_prefs" > "$tmp" 2>/dev/null
                mv -f "$tmp" "$dst_prefs" 2>/dev/null
                echo "$pl" >> "$dst_prefs"
                log_ok "Pref: $pn"
            fi
        done
    fi

    # Extension IndexedDB. Firefox stores extension storage under
    # storage/default on current builds and keeps long-lived data under
    # storage/permanent on some versions.
    local ext_count=0
    for base in default permanent; do
        for sd in "${src_profile}/storage/${base}/moz-extension+++"*; do
            if [ -d "$sd" ]; then
                local dn=$(basename "$sd")
                safe_cp "$sd" "${dst_profile}/storage/${base}/${dn}" "ext-idb: $dn"
                ext_count=$((ext_count + 1))
            fi
        done
        for sd in "${src_files}/storage/${base}/moz-extension+++"*; do
            if [ -d "$sd" ]; then
                local dn=$(basename "$sd")
                safe_cp "$sd" "${dst_files}/storage/${base}/${dn}" "ext-idb (files): $dn"
                safe_cp "$sd" "${dst_profile}/storage/${base}/${dn}" "ext-idb (files to profile): $dn"
                ext_count=$((ext_count + 1))
            fi
        done
    done
    log_ok "Extension stores: $ext_count"

    # Make a failed extension copy visible instead of ending with a silent
    # empty target profile.
    local src_ext_n=0
    local dst_ext_n=0
    if [ -f "${src_ext_root}/extensions.json" ]; then
        src_ext_n=$(tr ',' '\n' < "${src_ext_root}/extensions.json" 2>/dev/null | grep -c '"id"')
    fi
    if [ -f "${dst_ext_root}/extensions.json" ]; then
        dst_ext_n=$(tr ',' '\n' < "${dst_ext_root}/extensions.json" 2>/dev/null | grep -c '"id"')
    fi
    log_info "Extension manifest entries: source=$src_ext_n target=$dst_ext_n"
    if [ "$src_ext_n" -gt 0 ] && [ "$dst_ext_n" -eq 0 ]; then
        log_warn "Source has $src_ext_n extension entries but the target manifest is empty; open Firefox once and check about:addons"
    fi
    else
        log_info "Extension migration disabled; target extensions preserved"
    fi

    # ==== PHASE 5: Firefox Account / Sync ====
    log_info "PHASE 5: FIREFOX ACCOUNT AND SYNC"

    local src_sp="${src_dd}/shared_prefs"
    local dst_sp="${dst_dd}/shared_prefs"
    log_info "Source account files:"
    if [ -d "$src_sp" ]; then
        for f in "$src_sp"/fxa*.xml "$src_sp"/Fxa*.xml "$src_sp"/sync*.xml "$src_sp"/Sync*.xml; do
            [ -f "$f" ] || continue
            log_info "  $(stat -c %s "$f" 2>/dev/null || echo '?') bytes  $(basename "$f")"
        done
    fi
    local account_transferable=0
    local src_plain=""
    if [ "$MIGRATE_ACCOUNT" = "1" ]; then
        # The first release copied the plain-text fxaAppState.xml even from
        # Nightly. Nightly usually keeps that file as a fallback next to its
        # Keystore store, and it is the only account state the target can read.
        # Older SecureAbove22 builds also left the same fxaState key in
        # fxaStateAC_kp_pre_m.xml; accept that as a second plain-text source.
        for f in "$src_sp/fxaAppState.xml" "$src_sp/fxaStateAC_kp_pre_m.xml"; do
            [ -f "$f" ] || continue
            if grep -q 'name="fxaState"[^>]*>[^<]' "$f" 2>/dev/null; then
                src_plain="$f"
                break
            fi
        done
        if [ -n "$src_plain" ]; then
            account_transferable=1
            if [ -f "$src_sp/fxaStatePrefAC.xml" ] || \
               [ -f "$src_sp/fxaStateAC_kp_post_m.xml" ] || \
               [ -f "$src_sp/fxaStateAC_kp_pre_m.xml" ]; then
                log_info "Nightly secure account store detected; copying its plain-text fallback ($(basename "$src_plain"))"
            fi
        fi
    fi

    if [ -d "$src_sp" ]; then
        mkdir -p "$dst_sp" 2>/dev/null
        for f in "$src_sp"/*; do
            [ ! -f "$f" ] && continue
            local fname=$(basename "$f")

            # Account state is handled explicitly below. Crypto files are
            # Keystore-bound and must never be transplanted.
            case "$fname" in
                Fxa*|fxa*|SyncAuthInfoCache.xml|syncPrefs.xml|syncEngines.xml|sync.logins.prefs.xml|mozac_feature_accounts_push.xml|*Crypto.xml)
                    continue
                    ;;
                "fenix_preferences.xml")
                    # Fenix keeps pref_key_fxa_signed_in here. Copy it only
                    # when we are also copying a usable account state, so the
                    # target can never claim to be signed in without a state.
                    if [ "$account_transferable" = "1" ]; then
                        safe_cp "$f" "${dst_sp}/${fname}" "shared_pref: $fname"
                    else
                        log_info "Keeping target Fenix settings (no transferable account state)"
                    fi
                    continue
                    ;;
                "${SRC}_preferences.xml")
                    # Older Firefox builds name the settings file after the
                    # package. Rename it, otherwise the target never reads it.
                    if [ "$account_transferable" = "1" ]; then
                        safe_cp "$f" "${dst_sp}/${DST}_preferences.xml" "settings (renamed)"
                    fi
                    continue
                    ;;
                "${DST}_preferences.xml")
                    # This is the target's own settings file. Preserve it.
                    continue
                    ;;
            esac

            safe_cp "$f" "${dst_sp}/${fname}" "shared_pref: $fname"
        done
    fi

    if [ "$MIGRATE_ACCOUNT" != "1" ]; then
        log_info "Firefox Account copy disabled; target account state preserved"
    elif [ "$account_transferable" = "1" ]; then
        # Firefox release/beta use the plain-text shared preference store:
        # fxaAppState.xml contains the serialized FxA state under "fxaState".
        #
        # Remove any leftover secure store from the target. SharedPrefAccountStorage
        # migrates from SecureAbove22Preferences on startup and would otherwise
        # overwrite the state we just copied with the target's old account.
        rm -f "${dst_sp}/fxaStateAC.xml" "${dst_sp}/fxaStatePrefAC.xml" \
              "${dst_sp}/fxaStateAC_kp_post_m.xml" "${dst_sp}/fxaStateAC_kp_pre_m.xml" \
              "${dst_sp}/fxaAppState.xml.bak" "${dst_sp}/fenix_preferences.xml.bak" 2>/dev/null
        if [ -n "$src_plain" ]; then
            safe_cp "$src_plain" "$dst_sp/fxaAppState.xml" "Firefox Account state (plain-text fallback)"
        else
            log_warn "No plain-text Firefox Account state found; sign in once in the target"
        fi
        for n in FxaDeviceSettingsCache.xml SyncAuthInfoCache.xml syncPrefs.xml syncEngines.xml sync.logins.prefs.xml mozac_feature_accounts_push.xml; do
            [ -f "$src_sp/$n" ] && safe_cp "$src_sp/$n" "$dst_sp/$n" "sync pref: $n"
        done
        if grep -q 'name="fxaState"' "$dst_sp/fxaAppState.xml" 2>/dev/null; then
            if [ ! -f "${dst_sp}/fenix_preferences.xml" ]; then
                # A very fresh target may not have created its settings file
                # yet. Without this flag Fenix draws the account card as
                # signed out even though fxaState is present.
                cat > "${dst_sp}/fenix_preferences.xml" <<'PREFEOF'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <boolean name="pref_key_fxa_signed_in" value="true" />
</map>
PREFEOF
            else
                gecko_set_pref_bool "${dst_sp}/fenix_preferences.xml" "pref_key_fxa_signed_in" "true"
            fi
            # v1 also copied the package-named settings file. Keep its
            # signed-in flag consistent in case this Firefox build reads it.
            if [ -f "${dst_sp}/${DST}_preferences.xml" ]; then
                gecko_set_pref_bool "${dst_sp}/${DST}_preferences.xml" "pref_key_fxa_signed_in" "true"
            fi
            log_ok "Firefox Account state copied and signed-in flag synchronized"
        else
            # Never claim signed-in without a state, or Fenix shows an empty
            # account card until the next launch repairs it.
            gecko_set_pref_bool "${dst_sp}/fenix_preferences.xml" "pref_key_fxa_signed_in" "false"
            log_warn "fxaAppState.xml was copied but has no fxaState value; sign in once in the target"
        fi
        log_info "Target account state was backed up in $BACKUP_DIR/target_shared_prefs"
    elif [ -f "$src_sp/fxaStateAC.xml" ] || [ -f "$src_sp/fxaStatePrefAC.xml" ]; then
        # Firefox Nightly/debug uses SecureAbove22Preferences and a key that is
        # bound to the source app UID. Copying it produces an undecryptable
        # account and can leave the target stuck on the account screen.
        log_warn "Firefox Account uses Keystore-protected Nightly/debug storage; account state was not copied. Sign in once in the target."
    else
        log_info "No Firefox Account state found in source; target account preserved"
    fi

    safe_cp "${src_files}/firefox.settings.services.mozilla.com" "${dst_files}/firefox.settings.services.mozilla.com" "sync settings"
    safe_cp "${src_files}/datastore" "${dst_files}/datastore" "datastore/"
    safe_cp "${src_files}/mozac.feature.recentlyclosed" "${dst_files}/mozac.feature.recentlyclosed" "recently closed"

    # ==== PHASE 6: Package Name Patching ====
    log_info "PHASE 6: PACKAGE NAME PATCHING"
    if [ -d "$dst_sp" ] && [ "$SRC" != "$DST" ]; then
        for f in "$dst_sp"/*.xml; do
            [ ! -f "$f" ] && continue
            case "$(basename "$f")" in
                fxaAppState.xml|fxaStateAC.xml|fxaStatePrefAC.xml) continue ;;
            esac
            if grep -q "$SRC" "$f" 2>/dev/null; then
                sed -i "s|${SRC}|${DST}|g" "$f" 2>/dev/null
            fi
        done
    fi

    # ==== PHASE 7: Cleanup ====
    log_info "PHASE 7: CLEANUP"
    log_info "Post-copy target files:"
    for n in places.sqlite cookies.sqlite tabs.sqlite prefs.js extensions.json; do
        for d in "$dst_files" "$dst_profile"; do
            if [ -f "${d}/${n}" ]; then
                log_info "  $(stat -c %s "${d}/${n}" 2>/dev/null || echo '?') bytes  ${d}/${n}"
                break
            fi
        done
    done
    rm -f  "${dst_profile}/addonStartup.json.lz4" 2>/dev/null
    rm -rf "${dst_profile}/startupCache" 2>/dev/null
    rm -f  "${dst_profile}/compatibility.ini" 2>/dev/null

    rm -rf "${dst_dd}/cache" "${dst_dd}/cache2" "${dst_dd}/code_cache" 2>/dev/null

    local uid=$(get_uid "$DST")
    if [ -n "$uid" ]; then
        fix_perms "${dst_dd}/files" "$uid"
        [ -d "${dst_dd}/shared_prefs" ] && fix_perms "${dst_dd}/shared_prefs" "$uid"
    fi

    stop_pkg "$DST"
    log_ok "GECKO MIGRATION COMPLETED! (v8)"
}

if [ -z "$SRC" ] || [ -z "$DST" ]; then
    echo "Usage: $0 <source> <target>" >&2
    exit 1
fi

main
