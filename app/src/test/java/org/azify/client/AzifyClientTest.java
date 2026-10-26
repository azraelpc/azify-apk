package org.azify.client;

import org.junit.Test;

import java.io.IOException;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

import static org.junit.Assert.*;

public class AzifyClientTest {
    @Test public void noServerIsPreconfigured() {
        assertEquals("", AzifyClient.DEFAULT_SERVER);
    }

    @Test public void streamingOnlyUsesCredentialsForTheConfiguredOrigin() throws Exception {
        assertEquals("test-token", AzifyClient.mediaHeaders("https://example.com/", "test-token",
                "https://example.com/music/track.mp3").get("X-Azify-Pass"));
        assertTrue(AzifyClient.mediaHeaders("https://example.com/", "",
                "https://example.com/music/track.mp3").isEmpty());
    }

    @Test public void changingServerNeverSendsItsCredentialToAnOldQueueUrl() {
        for (String target : new String[] {"https://other.example/music/track.mp3",
                "http://example.com/music/track.mp3", "https://example.com:8443/music/track.mp3",
                "https://user:secret@example.com/music/track.mp3"}) {
            try {
                AzifyClient.mediaHeaders("https://example.com/", "test-token", target);
                fail("Expected foreign origin to be rejected");
            } catch (IOException expected) {
                assertNotNull(expected);
            }
        }
    }

    @Test(expected = IOException.class)
    public void unconfiguredServerCannotSupplyStreamingCredentials() throws Exception {
        AzifyClient.mediaHeaders("", "test-token", "https://example.com/music/track.mp3");
    }

    @Test public void publicServerDiscardsAnyPreviouslySavedCredential() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("[]"));
            AzifyClient.Connection connection = new AzifyClient().connect(server.url("/").toString(), "old-token");
            assertFalse(connection.requiresPassword);
            assertEquals("", connection.token);
            assertNull(server.takeRequest().getHeader("X-Azify-Pass"));
            assertEquals(1, server.getRequestCount());
        }
    }

    @Test public void protectedServerIsProbedBeforeSendingAPassword() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(401));
            server.enqueue(new MockResponse().setBody("[]"));
            AzifyClient.Connection connection = new AzifyClient().connect(server.url("/").toString(), "test-token");
            assertTrue(connection.requiresPassword);
            assertEquals("test-token", connection.token);
            assertNull(server.takeRequest().getHeader("X-Azify-Pass"));
            assertEquals("test-token", server.takeRequest().getHeader("X-Azify-Pass"));
        }
    }

    @Test public void passwordChallengeDoesNotRetryWithAnEmptyCredential() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(401));
            try {
                new AzifyClient().connect(server.url("/").toString(), "");
                fail("Expected password challenge");
            } catch (AzifyClient.AuthenticationException expected) {
                assertEquals(1, server.getRequestCount());
                assertNull(server.takeRequest().getHeader("X-Azify-Pass"));
            }
        }
    }

    @Test public void wrongPasswordKeepsTheAuthenticationChallenge() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(401));
            server.enqueue(new MockResponse().setResponseCode(401));
            try {
                new AzifyClient().connect(server.url("/").toString(), "wrong-token");
                fail("Expected authentication failure");
            } catch (AzifyClient.AuthenticationException expected) {
                assertEquals(2, server.getRequestCount());
            }
        }
    }

    @Test public void publicServerConnectsWithoutSendingCredentials() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("[]"));
            assertTrue(new AzifyClient().fetch(server.url("/").toString(), "").tracks().isEmpty());
            assertNull(server.takeRequest().getHeader("X-Azify-Pass"));
        }
    }

    @Test public void forbiddenIsNotMistakenForAPasswordChallenge() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(403));
            try {
                new AzifyClient().fetch(server.url("/").toString(), "");
                fail("Expected forbidden response");
            } catch (IOException expected) {
                assertFalse(expected instanceof AzifyClient.AuthenticationException);
                assertTrue(expected.getMessage().contains("403"));
            }
        }
    }

    @Test public void streamingHasNoWholeTrackTimeoutButApiRequestsAreBounded() {
        AzifyClient client = new AzifyClient();
        assertEquals(0, client.streamingHttp.callTimeoutMillis());
        assertEquals(40_000, client.http.callTimeoutMillis());
        assertTrue(client.streamingHttp.readTimeoutMillis() > 0);
    }

    @Test public void hashMatchesBrowserSha256AndTrimsInput() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                AzifyClient.passwordHash(" abc "));
    }

    @Test public void serverMustBeHttpsWithoutEmbeddedSecretsOrPaths() {
        assertEquals("https://example.com/", AzifyClient.normalizeServer(" https://example.com "));
        for (String invalid : new String[] {"", "   ", "http://example.com", "https://user:secret@example.com",
                "https://example.com/path", "https://example.com/?password=x"}) {
            try {
                AzifyClient.normalizeServer(invalid);
                fail("Expected invalid server URL");
            } catch (IllegalArgumentException expected) {
                assertNotNull(expected);
            }
        }
    }

    @Test public void sendsCredentialOnlyAsHeaderAndRequestsExactApiRoute() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("[]"));
            Catalog result = new AzifyClient().fetch(server.url("/").toString(), "test-token");
            assertTrue(result.tracks().isEmpty());
            okhttp3.mockwebserver.RecordedRequest request = server.takeRequest();
            assertEquals("/api/music", request.getPath());
            assertEquals("test-token", request.getHeader("X-Azify-Pass"));
        }
    }

    @Test public void unauthorizedIsAnActionableAuthenticationError() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(401));
            try {
                new AzifyClient().fetch(server.url("/").toString(), "wrong-token");
                fail("Expected authentication error");
            } catch (AzifyClient.AuthenticationException expected) {
                assertTrue(expected.getMessage().contains("Settings"));
            }
        }
    }

    @Test public void refusesRedirectsInsteadOfForwardingCredentials() throws Exception {
        try (MockWebServer server = new MockWebServer(); MockWebServer other = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(302)
                    .addHeader("Location", other.url("/api/music")));
            try {
                new AzifyClient().fetch(server.url("/").toString(), "test-token");
                fail("Expected redirect to be refused");
            } catch (IOException expected) {
                assertEquals(0, other.getRequestCount());
            }
        }
    }

    @Test public void reportsMalformedApiInsteadOfTreatingLoginHtmlAsAnEmptyLibrary() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("<html>Sign in</html>"));
            try {
                new AzifyClient().fetch(server.url("/").toString(), "");
                fail("Expected invalid catalog error");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("catalog"));
            }
        }
    }
}