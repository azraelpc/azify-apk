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
    }

    @Test public void initialFormHasAnEmptyServerAndDoesNotAskForAPassword() {
        RuntimeEnvironment.getApplication().getSharedPreferences("connection", Context.MODE_PRIVATE)
                .edit().clear().commit();
        ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).create().visible();
        try {
            activity.get().findViewById(R.id.settings).performClick();
            Dialog dialog = ShadowDialog.getLatestDialog();
            assertNotNull(dialog);
            EditText server = dialog.findViewById(R.id.server_url);
            assertEquals("AZify Server URL", server.getHint().toString());
            assertEquals("", server.getText().toString());
            assertFalse(dialog.findViewById(R.id.server_password).isShown());
            assertFalse(((CheckBox) dialog.findViewById(R.id.remember_password)).isChecked());
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
}