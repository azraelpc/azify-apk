package org.azify.client;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.cache.CacheDataSink;
import androidx.media3.datasource.cache.ContentMetadataMutations;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.exoplayer.offline.Download;
import androidx.media3.exoplayer.offline.DownloadRequest;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;

import static org.junit.Assert.*;

@UnstableApi
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class DownloadsTest {
    private Downloads downloads;

    private Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    @After public void releaseResources() throws Exception {
        if (downloads != null) {
            downloads.manager().release();
            ((ExecutorService) field(downloads, "disk")).shutdownNow();
            ((SimpleCache) field(downloads, "cache")).release();
            Field singleton = Downloads.class.getDeclaredField("instance");
            singleton.setAccessible(true);
            singleton.set(null, null);
        }
        Field settings = Settings.class.getDeclaredField("instance");
        settings.setAccessible(true);
        settings.set(null, null);
    }

    @Test public void completedAudioPrefersCacheWithoutCredentialsAndSignOutLocksIt() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        SharedPreferences preferences = context.getSharedPreferences("connection", Context.MODE_PRIVATE);
        preferences.edit().clear().commit();
        Settings settings = Settings.get(context);
        String server = "https://offline.example.invalid/";
        String source = server + "music/song.wav";
        settings.save(server, "temporary-test-token", true, false);
        downloads = Downloads.get(context);
        SimpleCache cache = (SimpleCache) field(downloads, "cache");
        byte[] audio = new byte[] {82, 73, 70, 70, 12, 34, 56, 78};
        CacheDataSink sink = new CacheDataSink(cache, CacheDataSink.DEFAULT_FRAGMENT_SIZE);
        androidx.media3.datasource.cache.CacheSpan lockedSpan = cache.startReadWrite(source, 0, audio.length);
        try {
            sink.open(new DataSpec.Builder().setUri(source).setKey(source).setLength(audio.length).build());
            sink.write(audio, 0, audio.length);
        } finally {
            sink.close();
            cache.releaseHoleSpan(lockedSpan);
        }
        ContentMetadataMutations metadata = new ContentMetadataMutations();
        ContentMetadataMutations.setContentLength(metadata, audio.length);
        cache.applyContentMetadataMutations(source, metadata);
        DownloadRequest request = new DownloadRequest.Builder(source, Uri.parse(source)).setCustomCacheKey(source).build();
        @SuppressWarnings("unchecked") Map<String, Download> states = (Map<String, Download>) field(downloads, "states");
        states.put(source, new Download(request, Download.STATE_COMPLETED, 0, 0, audio.length, 0, 0));
        ((CountDownLatch) field(downloads, "indexed")).countDown();
        downloads.saveCatalog(server, Catalog.parse("[{\"album\":\"Test album\",\"folder\":\"\","
                + "\"tracks\":[{\"title\":\"song.wav\",\"file\":\"/music/song.wav\",\"duration\":\"0:01\"}]}]", server));

        Field snapshot = Settings.class.getDeclaredField("snapshot");
        snapshot.setAccessible(true);
        snapshot.set(settings, new Settings(preferences).current());
        assertFalse(settings.current().configured);
        assertEquals("", settings.current().token);
        assertTrue(downloads.available());
        assertEquals(1, downloads.library(true).tracks().size());

        DataSource local = downloads.playbackFactory().createDataSource();
        try {
            assertEquals(audio.length, local.open(new DataSpec(Uri.parse(source))));
            byte[] read = new byte[audio.length];
            int offset = 0;
            while (offset < read.length) {
                int count = local.read(read, offset, read.length - offset);
                assertTrue(count > 0);
                offset += count;
            }
            assertArrayEquals(audio, read);
        } finally { local.close(); }

        settings.signOut();
        assertFalse(downloads.available());
        DataSource locked = downloads.playbackFactory().createDataSource();
        try {
            locked.open(new DataSpec(Uri.parse(source)));
            fail("Signed-out sessions must not read downloaded audio");
        } catch (AzifyClient.AuthenticationException expected) {
            assertEquals(server, settings.current().server);
        } finally { locked.close(); }
    }
}