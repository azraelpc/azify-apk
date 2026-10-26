package org.azify.client;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Objects;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class Settings {
    private static final String KEY_ALIAS = "azify.server.token";
    private static Settings instance;

    static final class Snapshot {
        final String server;
        final String token;
        final boolean configured;
        final boolean requiresPassword;
        final boolean rememberPassword;
        final boolean offlineAuthorized;
        final boolean allowInsecureHttp;

        Snapshot(String server, String token, boolean configured,
            boolean requiresPassword, boolean rememberPassword, boolean offlineAuthorized) {
            this(server, token, configured, requiresPassword, rememberPassword, offlineAuthorized, false);
        }

        Snapshot(String server, String token, boolean configured, boolean requiresPassword,
                boolean rememberPassword, boolean offlineAuthorized, boolean allowInsecureHttp) {
            this.server = server;
            this.token = token;
            this.configured = configured;
            this.requiresPassword = requiresPassword;
            this.rememberPassword = rememberPassword;
            this.offlineAuthorized = offlineAuthorized;
            this.allowInsecureHttp = allowInsecureHttp;
        }

        boolean sameAccount(Snapshot other) {
                return server.equals(other.server) && token.equals(other.token)
                    && allowInsecureHttp == other.allowInsecureHttp;
        }
    }

    private final SharedPreferences preferences;
    private volatile Snapshot snapshot;

    static synchronized Settings get(Context context) {
        if (instance == null) {
            instance = new Settings(context.getApplicationContext());
        }
        return instance;
    }

    private Settings(Context context) {
        this(context.getSharedPreferences("connection", Context.MODE_PRIVATE));
    }

    Settings(SharedPreferences preferences) {
        this.preferences = preferences;
        String server = preferences.getString("server", AzifyClient.DEFAULT_SERVER);
        String token = "";
        boolean requiresPassword = preferences.getBoolean("requiresPassword", false);
        boolean rememberPassword = preferences.getBoolean("rememberPassword", false);
        try {
            String encrypted = rememberPassword ? preferences.getString("token", "") : "";
            if (!encrypted.isEmpty()) {
                String[] parts = encrypted.split(":", 2);
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, key(),
                        new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
                token = new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)),
                        StandardCharsets.UTF_8);
            }
        } catch (Exception unavailable) {
            rememberPassword = false;
        }
        if (!rememberPassword) {
            preferences.edit().remove("token").putBoolean("rememberPassword", false).apply();
        }
        boolean allowInsecureHttp = preferences.getBoolean("allowInsecureHttp", false);
        boolean transportAllowed = !server.startsWith("http://") || allowInsecureHttp;
        boolean configured = !server.isEmpty() && transportAllowed && (!requiresPassword || !token.isEmpty());
        snapshot = new Snapshot(server, token, configured, requiresPassword, rememberPassword,
            preferences.getBoolean("offlineAuthorized", false) && transportAllowed, allowInsecureHttp);
    }

    Snapshot current() {
        return snapshot;
    }

    synchronized void save(String server, String token, boolean requiresPassword,
            boolean rememberPassword) throws GeneralSecurityException {
        save(server, token, requiresPassword, rememberPassword, false);
        }

        synchronized void save(String server, String token, boolean requiresPassword,
            boolean rememberPassword, boolean allowInsecureHttp) throws GeneralSecurityException {
        if (server.isEmpty() || (requiresPassword && token.isEmpty())) {
            throw new IllegalArgumentException("The connection is not configured.");
        }
        server = AzifyClient.normalizeServer(server);
        boolean insecure = server.startsWith("http://");
        if (insecure && !allowInsecureHttp) {
            throw new IllegalArgumentException("Enable Allow insecure HTTP before connecting without encryption.");
        }
        boolean remember = requiresPassword && rememberPassword;
        String encrypted = "";
        if (remember) {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            encrypted = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":"
                    + Base64.encodeToString(cipher.doFinal(token.getBytes(StandardCharsets.UTF_8)),
                    Base64.NO_WRAP);
        }
        SharedPreferences.Editor update = preferences.edit().putString("server", server)
            .putBoolean("allowInsecureHttp", insecure && allowInsecureHttp)
                .putBoolean("requiresPassword", requiresPassword)
            .putBoolean("offlineAuthorized", true)
                .putBoolean("rememberPassword", remember).remove("configured");
        if (remember) {
            update.putString("token", encrypted);
        } else {
            update.remove("token");
        }
        if (!update.commit()) {
            throw new GeneralSecurityException("Cannot save the connection.");
        }
        snapshot = new Snapshot(server, requiresPassword ? token : "", true, requiresPassword,
            remember, true, insecure && allowInsecureHttp);
    }

    synchronized void signOut() {
        String server = snapshot.server;
        boolean requiresPassword = snapshot.requiresPassword;
        preferences.edit().clear().putString("server", server)
            .putBoolean("requiresPassword", requiresPassword).apply();
        snapshot = new Snapshot(server, "", false, requiresPassword, false, false);
    }

    private SecretKey key() throws GeneralSecurityException {
        try {
            KeyStore store = KeyStore.getInstance("AndroidKeyStore");
            store.load(null);
            if (store.containsAlias(KEY_ALIAS)) {
                return (SecretKey) Objects.requireNonNull(store.getKey(KEY_ALIAS, null));
            }
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,
                    "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build());
            return generator.generateKey();
        } catch (java.io.IOException error) {
            throw new GeneralSecurityException(error);
        }
    }
}
