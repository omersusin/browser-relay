package com.browserrelay.app.core;

import android.annotation.SuppressLint;
import android.system.Os;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;
import java.security.Provider;
import java.security.Security;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Runs inside {@code app_process} under Trestle's root identity, drops to the
 * source browser's Linux UID, and decrypts the Firefox Account state that
 * Nightly keeps in Android Keystore.
 *
 * <p>AndroidKeyStore keys are owned by a Linux UID. Copying Nightly's encrypted
 * preference file to another browser therefore cannot work: the target app
 * cannot use Nightly's key. This helper does not export the key. It runs the
 * decrypt operation under Nightly's UID, which is the same identity check
 * Android applies to Nightly itself, and writes only the resulting plaintext
 * account XML into a root-only temporary file opened by the migration script.
 *
 * <p>The encrypted value follows Mozilla's {@code SecureAbove22Preferences}
 * format: URL-safe Base64 without padding, decoded to
 * {@code 0x02 || 12-byte nonce || AES-GCM ciphertext+tag}. The key alias is the
 * source package name. The plaintext is serialized as the exact
 * {@code fxaAppState.xml} shape that Firefox Beta/Release reads.
 */
public final class FxaKeystoreBridge {

    private static final int ENCRYPTED_VERSION = 0x02;
    private static final int NONCE_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String CIPHER = "AES/GCM/NoPadding";

    private static final Pattern FXA_STATE = Pattern.compile(
            "<string\\s+name=\"fxaState\"[^>]*>(.*?)</string>",
            Pattern.DOTALL
    );

    private FxaKeystoreBridge() {
    }

    public static void main(String[] args) {
        int uid = -1;
        String alias = null;
        String outputPath = null;
        boolean dropIdentity = true;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--uid".equals(arg) && i + 1 < args.length) {
                uid = parseInt(args[++i], -1);
            } else if ("--alias".equals(arg) && i + 1 < args.length) {
                alias = args[++i];
            } else if ("--out".equals(arg) && i + 1 < args.length) {
                outputPath = args[++i];
            } else if ("--no-drop".equals(arg)) {
                // The su fallback starts the helper already switched to the
                // source UID. Keep that identity instead of calling setuid()
                // again.
                dropIdentity = false;
            }
        }

        if (uid <= 0 || outputPath == null || outputPath.isEmpty()) {
            fail("usage: FxaKeystoreBridge --uid UID --alias ALIAS --out PATH (XML on stdin)", 3);
        }

        System.err.println("bridge: start uid=" + Os.getuid() + " context=" + currentContext());
        // app_process is not forked from zygote, so the AndroidKeyStore JCA
        // provider has not been installed in this runtime. App processes get
        // it from ZygoteInit.warmUpJcaProviders(); a raw app_process must do
        // the same before KeyStore.getInstance("AndroidKeyStore").
        installAndroidKeyStoreProvider();
        FileOutputStream output = null;
        try {
            // Read the encrypted XML while this process is still root. The
            // migration script feeds it through a pipe, so the source app's
            // private directory never has to be traversed by the dropped-UID
            // process. Open the destination before dropping privileges too.
            byte[] input = readAll(System.in);
            File outputFile = new File(outputPath);
            output = new FileOutputStream(outputFile, false);

            String encryptedValue = extractEncryptedValue(new String(input, StandardCharsets.UTF_8));
            if (encryptedValue == null || encryptedValue.isEmpty()) {
                fail("the source has no non-empty fxaState value", 4);
            }

            byte[] encrypted = null;
            try {
                encrypted = Base64.decode(
                        encryptedValue,
                        Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP
                );
            } catch (IllegalArgumentException e) {
                fail("fxaState is not valid URL-safe Base64", 4);
            }
            if (encrypted == null || encrypted.length <= NONCE_LENGTH) {
                fail("fxaState is too short to contain a nonce and ciphertext", 4);
            }
            if ((encrypted[0] & 0xff) != ENCRYPTED_VERSION) {
                fail("unsupported FxA encryption version " + (encrypted[0] & 0xff), 4);
            }

            if (dropIdentity) {
                dropToUid(uid);
            } else {
                System.err.println("bridge: using existing uid=" + Os.getuid()
                        + " context=" + currentContext());
            }

            byte[] plaintext = decryptWithSourceKey(encrypted, alias);
            if (plaintext == null || plaintext.length == 0) {
                fail("no AndroidKeyStore key of Nightly could decrypt fxaState", 2);
            }

            String state = new String(plaintext, StandardCharsets.UTF_8);
            if (!looksLikeAccountState(state)) {
                fail("decrypted state is not a Firefox Account document", 2);
            }

            // The output is SharedPreferences XML, not raw JSON. Escape text
            // content so arbitrary tokens in the account document cannot break
            // the XML parser in Firefox.
            Writer writer = new OutputStreamWriter(output, StandardCharsets.UTF_8);
            writer.write("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n");
            writer.write("<map>\n");
            writer.write("    <string name=\"fxaState\">");
            writer.write(xmlEscape(state));
            writer.write("</string>\n");
            writer.write("</map>\n");
            writer.flush();
            output.getFD().sync();
            writer.close();
            output = null;
        } catch (Throwable t) {
            fail("decrypt failed: " + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : ": " + t.getMessage()), 2);
        } finally {
            if (output != null) {
                try {
                    output.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    // The helper runs from app_process, outside the app sandbox. The
    // AndroidKeyStore JCA provider is a hidden framework API, so lint's
    // app-facing private API check does not apply here.
    @SuppressLint("BlockedPrivateApi")
    private static void installAndroidKeyStoreProvider() {
        try {
            Class<?> provider = Class.forName(
                    "android.security.keystore2.AndroidKeyStoreProvider");
            Method install = provider.getDeclaredMethod("install");
            install.setAccessible(true);
            install.invoke(null);
            System.err.println("bridge: AndroidKeyStore provider installed (keystore2)");
            return;
        } catch (Throwable first) {
            System.err.println("bridge: keystore2 provider install failed: "
                    + first.getClass().getSimpleName());
        }

        // Older Android versions expose the provider in the keystore package
        // and install it by adding an instance to Security.
        try {
            Class<?> provider = Class.forName(
                    "android.security.keystore.AndroidKeyStoreProvider");
            Object instance = provider.getDeclaredConstructor().newInstance();
            Security.addProvider((Provider) instance);
            System.err.println("bridge: AndroidKeyStore provider installed (legacy)");
        } catch (Throwable second) {
            System.err.println("bridge: legacy provider install failed: "
                    + second.getClass().getSimpleName());
        }
    }

    private static void dropToUid(int uid) throws Exception {
        if (Os.getuid() == uid) {
            return;
        }
        // setgid must happen before setuid, otherwise the process can no
        // longer change its primary group. Keystore authorization only checks
        // the UID, but dropping the group keeps the helper as narrow as
        // possible while it is running under the source identity.
        try {
            Os.setgid(uid);
        } catch (Exception e) {
            // Some root managers already place the helper in a restricted
            // profile. The UID below is the part AndroidKeyStore enforces.
            System.err.println("bridge: setgid failed: " + e.getClass().getSimpleName());
        }
        Os.setuid(uid);
        System.err.println("bridge: switched uid=" + Os.getuid() + " context=" + currentContext());
    }

    private static String currentContext() {
        FileInputStream input = null;
        try {
            input = new FileInputStream("/proc/self/attr/current");
            byte[] value = readAll(input);
            return new String(value, StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return "?";
        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static byte[] decryptWithSourceKey(byte[] encrypted, String preferredAlias) {
        KeyStore keyStore = null;
        try {
            keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
            keyStore.load(null);
        } catch (Exception e) {
            return null;
        }

        List<String> aliases = new ArrayList<>();
        if (preferredAlias != null && !preferredAlias.isEmpty()) {
            aliases.add(preferredAlias);
        }
        try {
            Enumeration<String> all = keyStore.aliases();
            while (all.hasMoreElements()) {
                String next = all.nextElement();
                if (!aliases.contains(next)) {
                    aliases.add(next);
                }
            }
        } catch (Exception ignored) {
        }

        byte[] nonce = new byte[NONCE_LENGTH];
        System.arraycopy(encrypted, 1, nonce, 0, NONCE_LENGTH);
        int cipherLength = encrypted.length - 1 - NONCE_LENGTH;
        byte[] ciphertext = new byte[cipherLength];
        System.arraycopy(encrypted, 1 + NONCE_LENGTH, ciphertext, 0, cipherLength);

        for (String candidate : aliases) {
            try {
                Key key = keyStore.getKey(candidate, null);
                if (!(key instanceof SecretKey)) {
                    continue;
                }
                Cipher aes = Cipher.getInstance(CIPHER);
                aes.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
                byte[] plaintext = aes.doFinal(ciphertext);
                if (plaintext.length > 0) {
                    return plaintext;
                }
            } catch (GeneralSecurityException | IllegalArgumentException ignored) {
                // Wrong alias, key invalidated, or a non-AES entry. Try the
                // next key. The GCM tag makes a wrong key fail closed.
            }
        }
        return null;
    }

    private static String extractEncryptedValue(String xml) {
        Matcher matcher = FXA_STATE.matcher(xml);
        if (!matcher.find()) {
            return null;
        }
        String value = matcher.group(1);
        return value == null ? null : value.trim();
    }

    private static boolean looksLikeAccountState(String state) {
        if (state == null) {
            return false;
        }
        String trimmed = state.trim();
        return trimmed.length() > 2 && (trimmed.startsWith("{") || trimmed.startsWith("["));
    }

    private static String xmlEscape(String value) {
        StringBuilder out = new StringBuilder(value.length() + 32);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&':
                    out.append("&amp;");
                    break;
                case '<':
                    out.append("&lt;");
                    break;
                case '>':
                    out.append("&gt;");
                    break;
                default:
                    out.append(c);
            }
        }
        return out.toString();
    }

    private static byte[] readAll(InputStream input) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(16384);
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static void fail(String message, int code) {
        System.err.println(message);
        System.exit(code);
    }
}
