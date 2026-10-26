package org.azify.client;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.view.View;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.LibraryResult;
import androidx.media3.session.MediaBrowser;
import androidx.media3.session.SessionToken;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleCallback;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListenableFuture;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

@UnstableApi
@RunWith(AndroidJUnit4.class)
public class MediaIntegrationTest {
    private Context context;
    private ActivityScenario<MainActivity> activity;
    private MediaBrowser browser;

    private <T> T onMain(Callable<T> operation) throws Exception {
        FutureTask<T> task = new FutureTask<>(operation);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(task);
        return task.get(10, TimeUnit.SECONDS);
    }

    @Before public void connect() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assumeTrue("Sign in on the phone before running live integration tests.",
                Settings.get(context).current().configured);
        activity = ActivityScenario.launch(MainActivity.class);
        ListenableFuture<MediaBrowser> connection = onMain(() -> new MediaBrowser.Builder(context,
                new SessionToken(context, new ComponentName(context, PlaybackService.class))).buildAsync());
        browser = connection.get(30, TimeUnit.SECONDS);
        onMain(() -> { browser.setVolume(0); return null; });
    }

    @After public void disconnect() throws Exception {
        if (browser != null) {
            onMain(() -> {
                browser.pause();
                browser.setVolume(1);
                browser.release();
                return null;
            });
        }
        if (activity != null) {
            activity.close();
        }
    }

    private List<MediaItem> children(String parent) throws Exception {
        LibraryResult<ImmutableList<MediaItem>> result = onMain(() ->
                browser.getChildren(parent, 0, Integer.MAX_VALUE, null)).get(50, TimeUnit.SECONDS);
        assertEquals("Library request failed", LibraryResult.RESULT_SUCCESS, result.resultCode);
        assertNotNull(result.value);
        return result.value;
    }

    private List<MediaItem> firstAlbumTracks() throws Exception {
        List<MediaItem> albums = children(Catalog.ALBUMS);
        assertFalse("The live library must contain at least one album", albums.isEmpty());
        return children(albums.get(0).mediaId);
    }

    private void orient(int requested, int expected) throws Exception {
        CountDownLatch resumed = new CountDownLatch(1);
        ActivityLifecycleCallback callback = (observed, stage) -> {
            if (observed instanceof MainActivity && stage == Stage.RESUMED
                    && observed.getResources().getConfiguration().orientation == expected) {
                resumed.countDown();
            }
        };
        onMain(() -> {
            ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(callback);
            return null;
        });
        try {
            activity.onActivity(current -> {
                current.setRequestedOrientation(requested);
                if (current.getResources().getConfiguration().orientation == expected) {
                    resumed.countDown();
                }
            });
            assertTrue("Orientation did not settle", resumed.await(15, TimeUnit.SECONDS));
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        } finally {
            onMain(() -> {
                ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(callback);
                return null;
            });
        }
    }

    private void verifyControlsAndCapture(String name) throws Exception {
        activity.onActivity(current -> {
            for (int id : new int[] {androidx.media3.ui.R.id.exo_prev, androidx.media3.ui.R.id.exo_next,
                    androidx.media3.ui.R.id.exo_play_pause, androidx.media3.ui.R.id.exo_progress,
                    androidx.media3.ui.R.id.exo_shuffle, androidx.media3.ui.R.id.exo_repeat_toggle}) {
                View control = current.findViewById(id);
                Rect bounds = new Rect();
                assertTrue("Transport control is hidden", control.isShown());
                assertTrue("Transport control is outside the screen", control.getGlobalVisibleRect(bounds));
                assertEquals("Transport control is vertically clipped", control.getHeight(), bounds.height());
                assertEquals("Transport control is horizontally clipped", control.getWidth(), bounds.width());
            }
            assertTrue("Library has no room to display rows", current.findViewById(R.id.library).getHeight()
                    >= 48 * current.getResources().getDisplayMetrics().density);
        });
        Bitmap capture = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(capture);
        File directory = context.getExternalFilesDir("validation");
        assertNotNull(directory);
        try (FileOutputStream image = new FileOutputStream(new File(directory, name + ".png"))) {
            assertTrue(capture.compress(Bitmap.CompressFormat.PNG, 100, image));
        }
        capture.recycle();
    }

    private void awaitPlayer(Runnable command, BooleanSupplier ready) throws Exception {
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<PlaybackException> failure = new AtomicReference<>();
        Player.Listener listener = new Player.Listener() {
            @Override public void onEvents(Player player, Player.Events events) {
                if (ready.getAsBoolean()) { completed.countDown(); }
            }
            @Override public void onPlayerError(PlaybackException error) {
                failure.set(error);
                completed.countDown();
            }
        };
        onMain(() -> {
            browser.addListener(listener);
            command.run();
            if (ready.getAsBoolean()) { completed.countDown(); }
            return null;
        });
        try {
            assertTrue("Playback state did not arrive", completed.await(50, TimeUnit.SECONDS));
            assertNull("Streaming failed", failure.get());
        } finally {
            onMain(() -> { browser.removeListener(listener); return null; });
        }
    }

    @Test public void modernBrowserLoadsFoldersSearchAndAuthenticatedArtwork() throws Exception {
        LibraryResult<MediaItem> root = onMain(() -> browser.getLibraryRoot(null)).get(10, TimeUnit.SECONDS);
        assertEquals(LibraryResult.RESULT_SUCCESS, root.resultCode);
        assertNotNull(root.value);
        assertEquals(Catalog.ROOT, root.value.mediaId);
        assertEquals(4, children(Catalog.ROOT).size());
        List<MediaItem> albums = children(Catalog.ALBUMS);
        assertFalse(albums.isEmpty());
        assertTrue(Boolean.TRUE.equals(albums.get(0).mediaMetadata.isBrowsable));
        assertTrue(Boolean.TRUE.equals(albums.get(0).mediaMetadata.isPlayable));
        assertFalse(children(Catalog.FOLDERS).isEmpty());
        List<MediaItem> tracks = children(albums.get(0).mediaId);
        assertFalse(tracks.isEmpty());
        assertTrue(Boolean.TRUE.equals(tracks.get(0).mediaMetadata.isPlayable));
        String query = albums.get(0).mediaMetadata.title.toString();
        LibraryResult<ImmutableList<MediaItem>> search = onMain(() ->
                browser.getSearchResult(query, 0, 100, null)).get(50, TimeUnit.SECONDS);
        assertEquals(LibraryResult.RESULT_SUCCESS, search.resultCode);
        assertNotNull(search.value);
        assertFalse(search.value.isEmpty());
        assertNotNull(albums.get(0).mediaMetadata.artworkUri);
        assertEquals("content", albums.get(0).mediaMetadata.artworkUri.getScheme());
        try (InputStream image = context.getContentResolver()
                .openInputStream(albums.get(0).mediaMetadata.artworkUri)) {
            assertNotNull(BitmapFactory.decodeStream(image));
        }
        try {
            orient(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Configuration.ORIENTATION_PORTRAIT);
            verifyControlsAndCapture("portrait");
            orient(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, Configuration.ORIENTATION_LANDSCAPE);
            verifyControlsAndCapture("landscape");
        } finally {
            orient(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Configuration.ORIENTATION_PORTRAIT);
            activity.onActivity(current -> current.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED));
        }
    }

    @Test public void modernTransportPlaysPausesSeeksAndMovesBetweenTracks() throws Exception {
        List<MediaItem> tracks = firstAlbumTracks();
        assertFalse(tracks.isEmpty());
        String first = tracks.get(0).mediaId;
        awaitPlayer(() -> {
            browser.setMediaItem(new MediaItem.Builder().setMediaId(first).build());
            browser.prepare();
            browser.play();
        }, () -> browser.isPlaying() && browser.getPlaybackState() == Player.STATE_READY);
        onMain(() -> {
            assertEquals(first, browser.getCurrentMediaItem().mediaId);
            assertTrue(browser.getMediaItemCount() >= tracks.size());
            assertTrue(browser.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM));
            assertTrue(browser.isCurrentMediaItemSeekable());
            return null;
        });
        awaitPlayer(browser::pause, () -> !browser.getPlayWhenReady());
        long duration = onMain(browser::getDuration);
        assertTrue(duration != C.TIME_UNSET && duration > 0);
        long target = Math.min(10_000, duration / 2);
        awaitPlayer(() -> browser.seekTo(target), () -> browser.getPlaybackState() == Player.STATE_READY
                && Math.abs(browser.getCurrentPosition() - target) < 1_000);
        awaitPlayer(browser::play, browser::isPlaying);
        if (onMain(browser::getMediaItemCount) > 1) {
            awaitPlayer(browser::seekToNextMediaItem,
                    () -> browser.getCurrentMediaItemIndex() == 1 && browser.getPlaybackState() == Player.STATE_READY);
            awaitPlayer(browser::seekToPreviousMediaItem,
                    () -> browser.getCurrentMediaItemIndex() == 0 && browser.getPlaybackState() == Player.STATE_READY);
        }
        downloadAndVerifyOffline(first);
    }

    private void downloadAndVerifyOffline(String id) throws Exception {
        Downloads downloads = Downloads.get(context);
        List<androidx.media3.exoplayer.offline.DownloadRequest> requests = downloads.requests(id);
        Catalog.Entry track = downloads.savedCatalog(Settings.get(context).current().server).get(id);
        assertNotNull(track);
        String url = track.url;
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<Exception> failed = new AtomicReference<>();
        androidx.media3.exoplayer.offline.DownloadManager.Listener listener =
                new androidx.media3.exoplayer.offline.DownloadManager.Listener() {
                    @Override public void onDownloadChanged(androidx.media3.exoplayer.offline.DownloadManager manager,
                            androidx.media3.exoplayer.offline.Download download, Exception error) {
                        if (!download.request.id.equals(url)) { return; }
                        if (download.state == androidx.media3.exoplayer.offline.Download.STATE_FAILED) {
                            failed.set(error == null ? new Exception("Download failed") : error);
                            finished.countDown();
                        } else if (download.state == androidx.media3.exoplayer.offline.Download.STATE_COMPLETED) {
                            finished.countDown();
                        }
                    }
                };
        onMain(() -> {
            downloads.manager().addListener(listener);
            if (downloads.completed(url)) { finished.countDown(); } else { downloads.add(requests); }
            return null;
        });
        try {
            assertTrue("Audio download did not finish", finished.await(90, TimeUnit.SECONDS));
            assertNull("Audio download failed", failed.get());
            assertTrue(downloads.completed(url));
            assertFalse(downloads.library(true).tracks().isEmpty());
        } finally {
            onMain(() -> { downloads.manager().removeListener(listener); return null; });
        }
        Settings settings = Settings.get(context);
        Settings.Snapshot previous = settings.current();
        java.lang.reflect.Field snapshot = Settings.class.getDeclaredField("snapshot");
        snapshot.setAccessible(true);
        onMain(() -> {
            browser.stop();
            browser.clearMediaItems();
            snapshot.set(settings, new Settings.Snapshot(previous.server, "", false,
                    previous.requiresPassword, false, true));
            return null;
        });
        try {
            awaitPlayer(() -> {
                browser.setMediaItem(new MediaItem.Builder().setMediaId(Catalog.OFFLINE + id).build());
                browser.prepare();
                browser.play();
            }, () -> browser.isPlaying() && browser.getPlaybackState() == Player.STATE_READY
                    && browser.getCurrentMediaItem() != null && id.equals(browser.getCurrentMediaItem().mediaId));
            awaitPlayer(browser::pause, () -> !browser.getPlayWhenReady());
        } finally {
            onMain(() -> { snapshot.set(settings, previous); return null; });
        }
    }

    private void awaitLegacy(MediaControllerCompat controller, Runnable command,
            BooleanSupplier ready) throws Exception {
        CountDownLatch complete = new CountDownLatch(1);
        MediaControllerCompat.Callback listener = new MediaControllerCompat.Callback() {
            @Override public void onPlaybackStateChanged(PlaybackStateCompat state) {
                if (ready.getAsBoolean()) { complete.countDown(); }
            }
            @Override public void onMetadataChanged(MediaMetadataCompat metadata) {
                if (ready.getAsBoolean()) { complete.countDown(); }
            }
        };
        onMain(() -> {
            controller.registerCallback(listener, new Handler(Looper.getMainLooper()));
            command.run();
            if (ready.getAsBoolean()) { complete.countDown(); }
            return null;
        });
        try {
            assertTrue("Legacy media command did not complete", complete.await(50, TimeUnit.SECONDS));
        } finally {
            onMain(() -> { controller.unregisterCallback(listener); return null; });
        }
    }

    @Test public void legacyAndroidAutoProtocolBrowsesAndControlsTheSamePlayer() throws Exception {
        List<MediaItem> tracks = firstAlbumTracks();
        assertFalse(tracks.isEmpty());
        CountDownLatch connected = new CountDownLatch(1);
        MediaBrowserCompat legacy = onMain(() -> new MediaBrowserCompat(context,
                new ComponentName(context, PlaybackService.class), new MediaBrowserCompat.ConnectionCallback() {
                    @Override public void onConnected() { connected.countDown(); }
                    @Override public void onConnectionFailed() { connected.countDown(); }
                }, null));
        onMain(() -> { legacy.connect(); return null; });
        try {
            assertTrue(connected.await(15, TimeUnit.SECONDS));
            assertTrue(onMain(legacy::isConnected));
            assertEquals(Catalog.ROOT, onMain(legacy::getRoot));
            CountDownLatch browsed = new CountDownLatch(1);
            AtomicReference<List<MediaBrowserCompat.MediaItem>> albums = new AtomicReference<>();
            onMain(() -> {
                legacy.subscribe(Catalog.ALBUMS, new MediaBrowserCompat.SubscriptionCallback() {
                    @Override public void onChildrenLoaded(String id, List<MediaBrowserCompat.MediaItem> items) {
                        albums.set(items);
                        browsed.countDown();
                    }
                    @Override public void onError(String id) { browsed.countDown(); }
                });
                return null;
            });
            assertTrue(browsed.await(50, TimeUnit.SECONDS));
            assertNotNull(albums.get());
            assertFalse(albums.get().isEmpty());
            MediaControllerCompat controller = onMain(() -> new MediaControllerCompat(context, legacy.getSessionToken()));
            MediaControllerCompat.TransportControls transport = controller.getTransportControls();
            String first = tracks.get(0).mediaId;
            awaitLegacy(controller, () -> transport.playFromMediaId(first, Bundle.EMPTY), () ->
                    controller.getPlaybackState() != null
                            && controller.getPlaybackState().getState() == PlaybackStateCompat.STATE_PLAYING
                            && controller.getMetadata() != null
                            && first.equals(controller.getMetadata().getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID)));
            assertTrue((controller.getPlaybackState().getActions() & PlaybackStateCompat.ACTION_SEEK_TO) != 0);
            awaitLegacy(controller, transport::pause,
                    () -> controller.getPlaybackState().getState() == PlaybackStateCompat.STATE_PAUSED);
            awaitLegacy(controller, () -> transport.seekTo(5_000),
                    () -> Math.abs(controller.getPlaybackState().getPosition() - 5_000) < 1_000);
            awaitLegacy(controller, transport::play,
                    () -> controller.getPlaybackState().getState() == PlaybackStateCompat.STATE_PLAYING);
            if (onMain(browser::getMediaItemCount) > 1) {
                awaitLegacy(controller, transport::skipToNext, () -> controller.getMetadata() != null
                        && !first.equals(controller.getMetadata().getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID)));
                awaitLegacy(controller, transport::skipToPrevious, () -> controller.getMetadata() != null
                        && first.equals(controller.getMetadata().getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID)));
            }
        } finally {
            onMain(() -> { legacy.disconnect(); return null; });
        }
    }
}