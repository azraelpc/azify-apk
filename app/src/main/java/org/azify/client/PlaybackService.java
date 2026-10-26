package org.azify.client;

import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;

import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSourceBitmapLoader;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.ResolvingDataSource;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.session.CacheBitmapLoader;
import androidx.media3.session.LibraryResult;
import androidx.media3.session.MediaLibraryService;
import androidx.media3.session.MediaSession;
import androidx.media3.session.SessionCommand;
import androidx.media3.session.SessionError;
import androidx.media3.session.SessionResult;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;

@UnstableApi
public final class PlaybackService extends MediaLibraryService {
    static final SessionCommand RELOAD = new SessionCommand("org.azify.client.RELOAD", Bundle.EMPTY);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ListeningExecutorService catalogExecutor =
            MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());
    private final ListeningExecutorService artworkExecutor =
            MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(2));
    private MediaLibrarySession session;
    private ExoPlayer player;
    private Settings settings;
    private CatalogRepository repository;
    private Downloads downloads;
    private SharedPreferences resume;
    private PendingIntent openApp;
    private final Runnable downloadsChanged = () -> {
        if (session != null) { session.notifyChildrenChanged(Catalog.DOWNLOADS, Integer.MAX_VALUE, null); }
    };

    private final Runnable checkpoint = new Runnable() {
        @Override public void run() {
            savePosition();
            main.postDelayed(this, 5_000);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        settings = Settings.get(this);
        AzifyClient client = new AzifyClient();
        downloads = Downloads.get(this);
        repository = new CatalogRepository(settings, client, downloads);
        resume = getSharedPreferences("playback", MODE_PRIVATE);
        DataSource.Factory sources = new DefaultDataSource.Factory(this, downloads.playbackFactory());
        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(sources))
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
                .setHandleAudioBecomingNoisy(true)
                .setWakeMode(C.WAKE_MODE_NETWORK)
                .setSeekBackIncrementMs(10_000)
                .setSeekForwardIncrementMs(10_000)
                .build();
        player.setRepeatMode(resume.getInt("repeat", Player.REPEAT_MODE_ALL));
        player.setShuffleModeEnabled(resume.getBoolean("shuffle", false));
        player.addListener(new Player.Listener() {
            @Override public void onEvents(Player current, Player.Events events) {
                savePosition();
                if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)
                        || events.contains(Player.EVENT_TIMELINE_CHANGED)
                        || events.contains(Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED)
                        || events.contains(Player.EVENT_REPEAT_MODE_CHANGED)) {
                    prefetchNextTracks(current, 3);
                }
            }
        });
        Intent intent = new Intent(Intent.ACTION_MAIN)
                .setClassName(this, getPackageName() + ".MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        openApp = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        session = new MediaLibrarySession.Builder(this, player, new LibraryCallback())
                .setSessionActivity(openApp)
                .setBitmapLoader(new CacheBitmapLoader(new DataSourceBitmapLoader(
                        artworkExecutor, sources, null, 512)))
                .build();
            downloads.addListener(downloadsChanged);
        main.post(checkpoint);
    }

    @Override public MediaLibrarySession onGetSession(MediaSession.ControllerInfo controllerInfo) {
        return session;
    }

    private void savePosition() {
        if (player == null || resume == null || player.getCurrentMediaItem() == null) {
            return;
        }
        resume.edit().putString("id", player.getCurrentMediaItem().mediaId)
                .putLong("position", Math.max(0, player.getCurrentPosition()))
                .putInt("repeat", player.getRepeatMode())
                .putBoolean("shuffle", player.getShuffleModeEnabled()).apply();
    }

    @Override public void onDestroy() {
        main.removeCallbacksAndMessages(null);
        if (downloads != null) { downloads.removeListener(downloadsChanged); }
        savePosition();
        if (session != null) {
            session.release();
        }
        if (player != null) {
            player.release();
        }
        catalogExecutor.shutdownNow();
        artworkExecutor.shutdownNow();
        super.onDestroy();
    }

    private MediaItem item(Catalog.Entry entry) {
        MediaMetadata.Builder metadata = new MediaMetadata.Builder()
                .setTitle(entry.title).setSubtitle(entry.subtitle)
                .setIsBrowsable(entry.browsable()).setIsPlayable(entry.playable())
                .setMediaType(entry.kind == Catalog.Kind.TRACK ? MediaMetadata.MEDIA_TYPE_MUSIC
                        : entry.kind == Catalog.Kind.ALBUM ? MediaMetadata.MEDIA_TYPE_ALBUM
                        : MediaMetadata.MEDIA_TYPE_FOLDER_MIXED);
        if (entry.album != null) {
            metadata.setAlbumTitle(entry.album).setArtist(entry.album);
        }
        if (entry.artwork != null) {
            metadata.setArtworkUri(ArtworkProvider.uri(this, entry.artwork));
        }
        MediaItem.Builder builder = new MediaItem.Builder().setMediaId(entry.id)
                .setMediaMetadata(metadata.build());
        if (entry.url != null) {
            builder.setUri(entry.url);
            Bundle extras = new Bundle();
            extras.putString("downloadUrl", entry.url);
            builder.setMediaMetadata(metadata.setExtras(extras).build());
        }
        return builder.build();
    }

    private List<MediaItem> items(List<Catalog.Entry> entries) {
        List<MediaItem> result = new ArrayList<>(entries.size());
        for (Catalog.Entry entry : entries) {
            result.add(item(entry));
        }
        return result;
    }

    private List<MediaItem> itemsOffline(List<Catalog.Entry> entries) {
        List<MediaItem> result = new ArrayList<>(entries.size());
        for (Catalog.Entry entry : entries) {
            result.add(item(entry).buildUpon().setMediaId(Catalog.OFFLINE + entry.id).build());
        }
        return result;
    }

    private <T> ListenableFuture<LibraryResult<T>> library(LibraryWork<T> work) {
        return library(false, work);
    }

    private <T> ListenableFuture<LibraryResult<T>> library(boolean offline, LibraryWork<T> work) {
        return catalogExecutor.submit(() -> {
            try {
                return work.run(offline ? downloads.library(false) : repository.get());
            } catch (AzifyClient.AuthenticationException expired) {
                Bundle extras = new Bundle();
                extras.putString("android.media.extras.ERROR_RESOLUTION_ACTION_LABEL", "Open AZify");
                extras.putParcelable("android.media.extras.ERROR_RESOLUTION_ACTION_INTENT", openApp);
                return LibraryResult.ofError(new SessionError(
                        SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED, expired.getMessage(), extras));
            } catch (IOException failure) {
                return LibraryResult.ofError(new SessionError(SessionError.ERROR_IO,
                        "Cannot connect to Azify. Check your connection and try again."));
            } catch (IllegalArgumentException failure) {
                return LibraryResult.ofError(SessionError.ERROR_BAD_VALUE);
            }
        });
    }

    private interface LibraryWork<T> {
        LibraryResult<T> run(Catalog catalog);
    }

    private void prefetchNextTracks(Player current, int count) {
        if (downloads == null || current.getMediaItemCount() == 0 || count < 1) {
            return;
        }
        Timeline timeline = current.getCurrentTimeline();
        int index = current.getCurrentMediaItemIndex();
        if (index == C.INDEX_UNSET || timeline.isEmpty()) {
            return;
        }
        List<String> urls = new ArrayList<>(count);
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < count; i++) {
            index = timeline.getNextWindowIndex(index, current.getRepeatMode(), current.getShuffleModeEnabled());
            if (index == C.INDEX_UNSET || !seen.add(index)) {
                break;
            }
            MediaItem candidate = current.getMediaItemAt(index);
            if (candidate.localConfiguration != null && candidate.localConfiguration.uri != null) {
                urls.add(candidate.localConfiguration.uri.toString());
            }
        }
        downloads.prefetchUpcoming(urls);
    }

    private MediaSession.MediaItemsWithStartPosition queue(Catalog catalog, String id, long position) {
        int index = catalog.startIndex(id);
        if (index < 0) {
            throw new IllegalArgumentException("This track is unavailable or the library is empty.");
        }
        return new MediaSession.MediaItemsWithStartPosition(items(catalog.tracks()), index,
                position == C.TIME_UNSET ? 0 : Math.max(0, position));
    }

    private String resolveId(Catalog catalog, MediaItem requested) {
        String query = requested.requestMetadata.searchQuery;
        if (query != null && !query.trim().isEmpty()) {
            for (Catalog.Entry entry : catalog.search(query)) {
                if (catalog.startIndex(entry.id) >= 0) {
                    return entry.id;
                }
            }
            throw new IllegalArgumentException("No results found.");
        }
        return requested.mediaId.isEmpty() || requested.mediaId.equals(Catalog.ROOT)
                ? Catalog.ALL : requested.mediaId;
    }

    private final class LibraryCallback implements MediaLibrarySession.Callback {
        @Override public MediaSession.ConnectionResult onConnect(MediaSession current,
                MediaSession.ControllerInfo controller) {
            if (controller.getUid() != Process.myUid() && !controller.isTrusted()) {
                return MediaSession.ConnectionResult.reject();
            }
            if (controller.getUid() != Process.myUid()) {
                try {
                    grantUriPermission(controller.getPackageName(), ArtworkProvider.root(PlaybackService.this),
                            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
                } catch (IllegalArgumentException | SecurityException unavailable) {
                    return MediaSession.ConnectionResult.reject();
                }
            }
            MediaSession.ConnectionResult.AcceptedResultBuilder result =
                    new MediaSession.ConnectionResult.AcceptedResultBuilder(current);
            if (controller.getUid() == Process.myUid()) {
                result.setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                        .buildUpon().add(RELOAD).build());
            }
            return result.build();
        }

        @Override public ListenableFuture<LibraryResult<MediaItem>> onGetLibraryRoot(
                MediaLibrarySession current, MediaSession.ControllerInfo browser, @Nullable LibraryParams params) {
            Bundle extras = new Bundle();
            extras.putBoolean("android.media.browse.SEARCH_SUPPORTED", true);
            extras.putInt("android.media.browse.CONTENT_STYLE_BROWSABLE_HINT", 1);
            extras.putInt("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT", 1);
            MediaItem root = new MediaItem.Builder().setMediaId(Catalog.ROOT)
                    .setMediaMetadata(new MediaMetadata.Builder().setTitle("AZify")
                            .setIsBrowsable(true).setIsPlayable(false).build()).build();
            return Futures.immediateFuture(LibraryResult.ofItem(root,
                    new LibraryParams.Builder().setExtras(extras).build()));
        }

        @Override public ListenableFuture<LibraryResult<MediaItem>> onGetItem(
                MediaLibrarySession current, MediaSession.ControllerInfo browser, String mediaId) {
            if (mediaId.startsWith(Catalog.OFFLINE)) {
                return library(true, catalog -> {
                    Catalog.Entry entry = catalog.get(mediaId.substring(Catalog.OFFLINE.length()));
                    return entry == null ? LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                            : LibraryResult.ofItem(item(entry).buildUpon().setMediaId(mediaId).build(), null);
                });
            }
            return library(catalog -> catalog.get(mediaId) == null
                    ? LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                    : LibraryResult.ofItem(item(catalog.get(mediaId)), null));
        }

        @Override public ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> onGetChildren(
                MediaLibrarySession current, MediaSession.ControllerInfo browser, String parentId,
                int page, int pageSize, @Nullable LibraryParams params) {
            if (parentId.equals(Catalog.DOWNLOADS)) {
                return library(true, catalog -> {
                    return LibraryResult.ofItemList(itemsOffline(
                            Catalog.page(catalog.children(Catalog.ALBUMS), page, pageSize)), params);
                });
            }
            if (parentId.startsWith(Catalog.OFFLINE)) {
                return library(true, catalog -> {
                    String localParent = parentId.substring(Catalog.OFFLINE.length());
                    Catalog.Entry parent = catalog.get(localParent);
                    if (parent == null || !parent.browsable()) {
                        return LibraryResult.ofError(SessionError.ERROR_BAD_VALUE);
                    }
                    return LibraryResult.ofItemList(itemsOffline(
                            Catalog.page(catalog.children(localParent), page, pageSize)), params);
                });
            }
            return library(catalog -> {
                Catalog.Entry parent = catalog.get(parentId);
                if (parent == null || !parent.browsable()) {
                    return LibraryResult.ofError(SessionError.ERROR_BAD_VALUE);
                }
                return LibraryResult.ofItemList(items(Catalog.page(catalog.children(parentId), page, pageSize)), params);
            });
        }

        @Override public ListenableFuture<LibraryResult<Void>> onSearch(MediaLibrarySession current,
                MediaSession.ControllerInfo browser, String query, @Nullable LibraryParams params) {
            return Futures.transform(library(catalog -> LibraryResult.ofItemList(
                    items(catalog.search(query)), params)), result -> {
                if (result.resultCode != LibraryResult.RESULT_SUCCESS || result.value == null) {
                    return result.sessionError == null ? LibraryResult.ofError(result.resultCode)
                            : LibraryResult.ofError(result.sessionError);
                }
                current.notifySearchResultChanged(browser, query, result.value.size(), params);
                return LibraryResult.ofVoid(params);
            }, main::post);
        }

        @Override public ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> onGetSearchResult(
                MediaLibrarySession current, MediaSession.ControllerInfo browser, String query,
                int page, int pageSize, @Nullable LibraryParams params) {
            return library(catalog -> LibraryResult.ofItemList(
                    items(Catalog.page(catalog.search(query), page, pageSize)), params));
        }

        @Override public ListenableFuture<MediaSession.MediaItemsWithStartPosition> onSetMediaItems(
                MediaSession current, MediaSession.ControllerInfo controller, List<MediaItem> requested,
                int startIndex, long startPositionMs) {
            return catalogExecutor.submit(() -> {
                if (requested.size() == 1 && requested.get(0).mediaId.startsWith(Catalog.OFFLINE)) {
                    return queue(downloads.library(true),
                            requested.get(0).mediaId.substring(Catalog.OFFLINE.length()), startPositionMs);
                }
                if (requested.isEmpty()) {
                    return new MediaSession.MediaItemsWithStartPosition(ImmutableList.of(), 0, 0);
                }
                Catalog catalog = repository.get();
                if (requested.size() == 1) {
                    return queue(catalog, resolveId(catalog, requested.get(0)), startPositionMs);
                }
                boolean hasOffline = false;
                for (MediaItem request : requested) {
                    if (request.mediaId.startsWith(Catalog.OFFLINE)) {
                        hasOffline = true;
                        break;
                    }
                }
                Catalog source = hasOffline ? downloads.library(true) : catalog;
                List<MediaItem> resolved = new ArrayList<>();
                for (MediaItem request : requested) {
                    String id = request.mediaId.startsWith(Catalog.OFFLINE)
                            ? request.mediaId.substring(Catalog.OFFLINE.length())
                            : resolveId(source, request);
                    Catalog.Entry entry = source.get(id);
                    if (entry == null || entry.kind != Catalog.Kind.TRACK) {
                        throw new IllegalArgumentException("Invalid track.");
                    }
                    resolved.add(item(entry));
                }
                return new MediaSession.MediaItemsWithStartPosition(resolved,
                        Math.max(0, Math.min(startIndex, resolved.size() - 1)),
                        startPositionMs == C.TIME_UNSET ? 0 : Math.max(0, startPositionMs));
            });
        }

        @Override public ListenableFuture<List<MediaItem>> onAddMediaItems(MediaSession current,
                MediaSession.ControllerInfo controller, List<MediaItem> requested) {
            return catalogExecutor.submit(() -> {
                Catalog catalog = repository.get();
                List<MediaItem> resolved = new ArrayList<>();
                for (MediaItem request : requested) {
                    Catalog.Entry entry = catalog.get(resolveId(catalog, request));
                    if (entry == null || entry.kind != Catalog.Kind.TRACK) {
                        throw new IllegalArgumentException("Invalid track.");
                    }
                    resolved.add(item(entry));
                }
                return resolved;
            });
        }

        @Override public ListenableFuture<MediaSession.MediaItemsWithStartPosition> onPlaybackResumption(
                MediaSession current, MediaSession.ControllerInfo controller) {
            String id = resume.getString("id", Catalog.ALL);
            long position = resume.getLong("position", 0);
            return catalogExecutor.submit(() -> {
                Catalog catalog = repository.get();
                return catalog.startIndex(id) < 0 ? queue(catalog, Catalog.ALL, 0)
                        : queue(catalog, id, position);
            });
        }

        @Override public ListenableFuture<SessionResult> onCustomCommand(MediaSession current,
                MediaSession.ControllerInfo controller, SessionCommand command, Bundle args) {
            if (!command.equals(RELOAD) || controller.getUid() != Process.myUid()) {
                return Futures.immediateFuture(new SessionResult(SessionError.ERROR_PERMISSION_DENIED));
            }
            if (args.getBoolean("reset", false)) {
                player.stop();
                player.clearMediaItems();
                resume.edit().clear().apply();
            }
            repository.invalidate();
            session.clearReplicatedLibraryError();
            return Futures.transform(library(catalog -> LibraryResult.ofVoid()), result -> {
                session.notifyChildrenChanged(Catalog.ROOT, 4, null);
                session.notifyChildrenChanged(Catalog.ALBUMS, Integer.MAX_VALUE, null);
                session.notifyChildrenChanged(Catalog.FOLDERS, Integer.MAX_VALUE, null);
                session.notifyChildrenChanged(Catalog.DOWNLOADS, Integer.MAX_VALUE, null);
                return new SessionResult(result.resultCode);
            }, main::post);
        }
    }
}
