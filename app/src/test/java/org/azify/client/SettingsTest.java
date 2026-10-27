package org.azify.client;

import android.content.Context;
import android.content.SharedPreferences;
import android.app.Dialog;
import android.widget.CheckBox;
import android.widget.EditText;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowDialog;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class SettingsTest {
    private static final String SERVER = "https://example.com/";
    private SharedPreferences preferences;

    @Before public void freshPreferences() {
        preferences = RuntimeEnvironment.getApplication()
                .getSharedPreferences("settings-test", Context.MODE_PRIVATE);
        preferences.edit().clear().commit();
    }

    @Test public void freshInstallHasNoServerCredentialOrConsent() {
        Settings.Snapshot account = new Settings(preferences).current();
        assertEquals("", account.server);
        assertEquals("", account.token);
        assertFalse(account.configured);
        assertFalse(account.requiresPassword);
        assertFalse(account.rememberPassword);
        assertFalse(account.offlineAuthorized);
        assertFalse(account.allowMobileDownloads);
    }

    @Test public void mobileDownloadsAreWifiOnlyByDefaultAndCanBePersisted() {
        Settings settings = new Settings(preferences);
        assertFalse(settings.current().allowMobileDownloads);
        settings.setAllowMobileDownloads(true);
        assertTrue(settings.current().allowMobileDownloads);
        assertTrue(new Settings(preferences).current().allowMobileDownloads);
    }

    @Test public void initialFormHasAnEmptyServerAndDoesNotAskForAPassword() {
        RuntimeEnvironment.getApplication().getSharedPreferences("connection", Context.MODE_PRIVATE)
                .edit().clear().commit();
        ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).create().visible();
        try {
            activity.get().findViewById(R.id.settings).performClick();
            Dialog settingsDialog = ShadowDialog.getLatestDialog();
            assertNotNull(settingsDialog);
            ((androidx.appcompat.app.AlertDialog) settingsDialog)
                .getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).performClick();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            Dialog dialog = ShadowDialog.getLatestDialog();
            assertNotNull(dialog);
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            EditText server = dialog.findViewById(R.id.server_url);
            assertEquals("AZify Server URL", server.getHint().toString());
            assertEquals("", server.getText().toString());
            assertFalse(dialog.findViewById(R.id.server_password).isShown());
            assertFalse(((CheckBox) dialog.findViewById(R.id.remember_password)).isChecked());
                CheckBox allowHttp = dialog.findViewById(R.id.allow_insecure_http);
                assertFalse(allowHttp.isChecked());
                assertFalse(allowHttp.isShown());
                server.setText("http://example.com:5155/player/");
                assertTrue(allowHttp.isShown());
                assertFalse(allowHttp.isChecked());
                ((androidx.appcompat.app.AlertDialog) dialog).getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                    .performClick();
                assertEquals(activity.get().getString(R.string.insecure_http_not_allowed),
                    ((android.widget.TextView) dialog.findViewById(R.id.connection_error)).getText().toString());
                allowHttp.setChecked(true);
                server.setText("http://other.example:5155/player/");
                assertFalse(allowHttp.isChecked());
        } finally {
            activity.destroy();
        }
    }

    @Test public void temporaryPasswordExistsOnlyInTheCurrentProcess() throws Exception {
        Settings settings = new Settings(preferences);
        settings.save(SERVER, "temporary-test-token", true, false);
        assertTrue(settings.current().configured);
        assertEquals("temporary-test-token", settings.current().token);
        assertFalse(settings.current().rememberPassword);
        assertFalse(preferences.contains("token"));
        Settings.Snapshot restarted = new Settings(preferences).current();
        assertEquals(SERVER, restarted.server);
        assertTrue(restarted.requiresPassword);
        assertFalse(restarted.configured);
        assertEquals("", restarted.token);
    }

    @Test public void publicServerDoesNotPersistAPasswordEvenWhenRememberWasSelected() throws Exception {
        Settings settings = new Settings(preferences);
        settings.save(SERVER, "unused-test-token", false, true);
        assertEquals("", settings.current().token);
        assertFalse(settings.current().rememberPassword);
        assertFalse(preferences.contains("token"));
        Settings.Snapshot restarted = new Settings(preferences).current();
        assertTrue(restarted.configured);
        assertFalse(restarted.requiresPassword);
        assertEquals(SERVER, restarted.server);
    }

    @Test public void optingOutRemovesAnyPreviouslyPersistedCredential() throws Exception {
        Settings settings = new Settings(preferences);
        preferences.edit().putString("token", "old-encrypted-test-value")
                .putBoolean("rememberPassword", true).commit();
        settings.save(SERVER, "temporary-test-token", true, false);
        assertFalse(preferences.contains("token"));
        assertFalse(preferences.getBoolean("rememberPassword", true));
    }

    @Test public void credentialsWithoutExplicitConsentAreDiscarded() {
        preferences.edit().putString("server", SERVER).putBoolean("requiresPassword", true)
                .putString("token", "legacy-test-value").commit();
        Settings.Snapshot account = new Settings(preferences).current();
        assertEquals("", account.token);
        assertFalse(account.configured);
        assertFalse(preferences.contains("token"));
    }

    @Test public void invalidEncryptedCredentialRequiresSignInAgain() {
        preferences.edit().putString("server", SERVER).putBoolean("requiresPassword", true)
                .putBoolean("rememberPassword", true).putString("token", "invalid-test-value").commit();
        Settings.Snapshot account = new Settings(preferences).current();
        assertFalse(account.configured);
        assertFalse(account.rememberPassword);
        assertEquals("", account.token);
        assertFalse(preferences.contains("token"));
    }

    @Test public void signingOutRemembersOnlyTheUrlAndRequiresANewPassword() throws Exception {
        Settings settings = new Settings(preferences);
        settings.save(SERVER, "temporary-test-token", true, false);
        settings.signOut();
        assertEquals(SERVER, settings.current().server);
        assertEquals("", settings.current().token);
        assertFalse(settings.current().configured);
        assertTrue(settings.current().requiresPassword);
        assertFalse(settings.current().rememberPassword);
        assertFalse(settings.current().offlineAuthorized);
        assertFalse(preferences.contains("token"));
        Settings.Snapshot restarted = new Settings(preferences).current();
        assertEquals(SERVER, restarted.server);
        assertFalse(restarted.configured);
        assertFalse(restarted.offlineAuthorized);
    }

    @Test(expected = IllegalArgumentException.class)
    public void protectedSessionCannotBeSavedWithoutItsCredential() throws Exception {
        new Settings(preferences).save(SERVER, "", true, true);
    }

    @Test(expected = IllegalArgumentException.class)
    public void httpCannotBeSavedWithoutExplicitConsent() throws Exception {
        new Settings(preferences).save("http://example.com:5155/player/", "", false, false);
    }

    @Test public void httpConsentIsRememberedForTheSavedBaseAndClearedOnSignOut() throws Exception {
        Settings settings = new Settings(preferences);
        settings.save("http://example.com:5155/player", "test-token", true, false, true);
        assertTrue(settings.current().allowInsecureHttp);
        assertEquals("http://example.com:5155/player/", settings.current().server);
        assertTrue(new Settings(preferences).current().allowInsecureHttp);
        settings.signOut();
        assertFalse(settings.current().allowInsecureHttp);
        assertFalse(new Settings(preferences).current().allowInsecureHttp);
        settings.save(SERVER, "", false, false);
        assertFalse(settings.current().allowInsecureHttp);
    }

    @Test public void persistedHttpWithoutConsentCannotAuthorizeNetworkOrOfflineAccess() {
        preferences.edit().putString("server", "http://example.com/")
                .putBoolean("offlineAuthorized", true).commit();
        Settings.Snapshot account = new Settings(preferences).current();
        assertFalse(account.configured);
        assertFalse(account.offlineAuthorized);
    }
}