package org.azify.client;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.AtomicFile;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.ResolvingDataSource;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.NoOpCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.offline.Download;
import androidx.media3.exoplayer.offline.DownloadCursor;
import androidx.media3.exoplayer.offline.DownloadManager;
import androidx.media3.exoplayer.offline.DownloadRequest;
import androidx.media3.exoplayer.offline.DownloadService;
import androidx.media3.exoplayer.scheduler.Requirements;

import org.json.JSONException;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@UnstableApi
final class Downloads {
    static final int COLLECTION_NONE = 0;
    static final int COLLECTION_ACTIVE = 1;
    static final int COLLECTION_DONE = 2;

    static final class CollectionProgress {
        final String title;
        final int completed;
        final int total;

        CollectionProgress(String title, int completed, int total) {
            this.title = title;
            this.completed = completed;
            this.total = total;
        }
    }

    private static Downloads instance;
    private static final String SYNC_PREFS = "download-sync";
    private static final String SYNC_COLLECTIONS = "collections";
    private final Context context;
    private final Settings settings;
    private final SimpleCache downloadCache;
    private final SimpleCache playbackCache;
    private final DataSource.Factory upstream;
    private final DownloadManager manager;
    private final Map<String, Download> states = new ConcurrentHashMap<>();
    private final Set<Runnable> listeners = new CopyOnWriteArraySet<>();
    private final ExecutorService downloadWorkers = Executors.newFixedThreadPool(8);
    private final ExecutorService disk = Executors.newSingleThreadExecutor();
    private final ExecutorService prefetch = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final File catalogs;
    private final CountDownLatch indexed = new CountDownLatch(1);
    private final Set<String> watchedCollections = ConcurrentHashMap.newKeySet();
    private final Set<String> announcedCollections = ConcurrentHashMap.newKeySet();
    private final Set<String> prefetching = ConcurrentHashMap.newKeySet();
    private String cachedCatalogServer;
    private Catalog cachedCatalog;

    static synchronized Downloads get(Context context) {
        if (instance == null) {
            instance = new Downloads(context.getApplicationContext());
        }
        return instance;
    }

    private Downloads(Context context) {
        this.context = context;
        settings = Settings.get(context);
        catalogs = new File(context.getFilesDir(), "catalogs");
        StandaloneDatabaseProvider database = new StandaloneDatabaseProvider(context);
        downloadCache = new SimpleCache(new File(context.getFilesDir(), "downloaded-audio"),
                new NoOpCacheEvictor(), database);
        playbackCache = new SimpleCache(new File(context.getFilesDir(), "playback-cache"),
            new LeastRecentlyUsedCacheEvictor(settings.current().cacheBytes), database);
        AzifyClient client = new AzifyClient(context);
        upstream = new ResolvingDataSource.Factory(new OkHttpDataSource.Factory(client.streamingHttp), spec -> {
            Settings.Snapshot account = settings.current();
            if (!account.configured) {
                throw new AzifyClient.AuthenticationException();
            }
            return spec.withRequestHeaders(AzifyClient.mediaHeaders(account.server, account.token, spec.uri.toString()));
        });
        manager = new DownloadManager(context, database, downloadCache, upstream, downloadWorkers);
        manager.setMaxParallelDownloads(6);
        applyNetworkPolicy();
        manager.addListener(new DownloadManager.Listener() {
            @Override public void onInitialized(DownloadManager current) {
                disk.execute(() -> {
                    try (DownloadCursor cursor = manager.getDownloadIndex().getDownloads()) {
                        while (cursor.moveToNext()) {
                            Download download = cursor.getDownload();
                            states.putIfAbsent(download.request.id, download);
                        }
                    } catch (IOException unavailable) {
                        states.clear();
                    } finally {
                        indexed.countDown();
                    }
                    main.post(Downloads.this::changed);
                });
            }

            @Override public void onDownloadChanged(DownloadManager current, Download download,
                    @Nullable Exception error) {
                states.put(download.request.id, download);
                changed();
            }

            @Override public void onDownloadRemoved(DownloadManager current, Download download) {
                states.remove(download.request.id);
                changed();
            }
        });
    }

    DownloadManager manager() {
        return manager;
    }

    boolean initialized() { return indexed.getCount() == 0; }

    void addListener(Runnable listener) { listeners.add(listener); }
    void removeListener(Runnable listener) { listeners.remove(listener); }

    private void changed() {
        checkCollectionCompletions();
        for (Runnable listener : listeners) { listener.run(); }
    }

    void watchCollection(String id) {
        String key = id.startsWith(Catalog.OFFLINE) ? id.substring(Catalog.OFFLINE.length()) : id;
        watchedCollections.add(key);
        announcedCollections.remove(key);
        changed();
    }

    static boolean isSyncableCollectionId(String id) {
        if (id == null) {
            return false;
        }
        String key = id.startsWith(Catalog.OFFLINE) ? id.substring(Catalog.OFFLINE.length()) : id;
        return key.startsWith("album:") || key.startsWith(Catalog.FOLDERS);
    }

    void setCollectionAutoSync(String id, boolean enabled) {
        if (!isSyncableCollectionId(id)) {
            return;
        }
        String key = id.startsWith(Catalog.OFFLINE) ? id.substring(Catalog.OFFLINE.length()) : id;
        SharedPreferences prefs = context.getSharedPreferences(SYNC_PREFS, Context.MODE_PRIVATE);
        Set<String> subscribed = new HashSet<>(prefs.getStringSet(SYNC_COLLECTIONS, Collections.emptySet()));
        if (enabled) {
            subscribed.add(key);
        } else {
            subscribed.remove(key);
        }
        prefs.edit().putStringSet(SYNC_COLLECTIONS, subscribed).apply();
    }

    private Set<String> subscribedCollections() {
        SharedPreferences prefs = context.getSharedPreferences(SYNC_PREFS, Context.MODE_PRIVATE);
        return new HashSet<>(prefs.getStringSet(SYNC_COLLECTIONS, Collections.emptySet()));
    }

    synchronized void syncSubscribedCollections(Catalog latest) {
        Settings.Snapshot account = settings.current();
        if (!account.configured) {
            return;
        }
        Set<String> subscribed = subscribedCollections();
        if (subscribed.isEmpty()) {
            return;
        }
        Catalog previous = null;
        try {
            previous = savedCatalog(account.server);
        } catch (IOException ignored) {
            // No previous catalog means first sync pass.
        }

        Set<String> desired = new HashSet<>();
        Set<String> removed = new HashSet<>();
        for (String id : subscribed) {
            for (Catalog.Entry track : latest.tracksUnder(id)) {
                if (track.url != null) {
                    desired.add(track.url);
                }
            }
            if (previous != null) {
                Set<String> oldUrls = new HashSet<>();
                for (Catalog.Entry track : previous.tracksUnder(id)) {
                    if (track.url != null) {
                        oldUrls.add(track.url);
                    }
                }
                oldUrls.removeAll(desired);
                removed.addAll(oldUrls);
            }
        }

        List<DownloadRequest> additions = new ArrayList<>();
        for (String url : desired) {
            if (completed(url)) {
                continue;
            }
            Download state = states.get(url);
            if (state != null && state.state != Download.STATE_FAILED && state.state != Download.STATE_STOPPED) {
                continue;
            }
            additions.add(new DownloadRequest.Builder(url, Uri.parse(url))
                    .setCustomCacheKey(url).build());
        }

        List<String> removals = new ArrayList<>();
        for (String url : removed) {
            if (states.containsKey(url)) {
                removals.add(url);
            }
        }

        if (!removals.isEmpty()) {
            remove(removals);
        }
        if (!additions.isEmpty()) {
            add(additions);
        }
    }

    int collectionState(String id) {
        Settings.Snapshot account = settings.current();
        if (!account.configured && !account.offlineAuthorized) {
            return COLLECTION_NONE;
        }
        String key = id.startsWith(Catalog.OFFLINE) ? id.substring(Catalog.OFFLINE.length()) : id;
        try {
            List<Catalog.Entry> tracks = savedCatalog(account.server).tracksUnder(key);
            if (tracks.isEmpty()) {
                return COLLECTION_NONE;
            }
            boolean allDone = true;
            boolean anyActive = false;
            for (Catalog.Entry track : tracks) {
                if (completed(track.url)) {
                    continue;
                }
                allDone = false;
                Download state = states.get(track.url);
                if (state != null && (state.state == Download.STATE_DOWNLOADING
                        || state.state == Download.STATE_QUEUED
                        || state.state == Download.STATE_RESTARTING
                        || state.state == Download.STATE_STOPPED)) {
                    anyActive = true;
                }
            }
            return allDone ? COLLECTION_DONE : anyActive ? COLLECTION_ACTIVE : COLLECTION_NONE;
        } catch (IOException failure) {
            return COLLECTION_NONE;
        }
    }

    @Nullable
    CollectionProgress activeCollectionProgress() {
        Settings.Snapshot account = settings.current();
        if ((!account.configured && !account.offlineAuthorized) || account.server.isEmpty()) {
            return null;
        }
        Catalog catalog;
        try {
            catalog = savedCatalog(account.server);
        } catch (IOException failure) {
            return null;
        }

        Set<String> candidates = new java.util.LinkedHashSet<>();
        candidates.addAll(watchedCollections);
        candidates.addAll(subscribedCollections());
        for (String id : candidates) {
            Catalog.Entry entry = catalog.get(id);
            if (entry == null || !isSyncableCollectionId(id)) {
                continue;
            }
            List<Catalog.Entry> tracks = catalog.tracksUnder(id);
            if (tracks.isEmpty()) {
                continue;
            }
            int completedCount = 0;
            boolean active = false;
            for (Catalog.Entry track : tracks) {
                if (track.url == null) {
                    continue;
                }
                if (completed(track.url)) {
                    completedCount++;
                    continue;
                }
                Download state = states.get(track.url);
                if (state != null && (state.state == Download.STATE_DOWNLOADING
                        || state.state == Download.STATE_QUEUED
                        || state.state == Download.STATE_RESTARTING
                        || state.state == Download.STATE_STOPPED)) {
                    active = true;
                }
            }
            if (active) {
                return new CollectionProgress(entry.title, completedCount, tracks.size());
            }
        }
        return null;
    }

    private void checkCollectionCompletions() {
        if (watchedCollections.isEmpty()) {
            return;
        }
        Settings.Snapshot account = settings.current();
        if (!account.configured && !account.offlineAuthorized) {
            return;
        }
        for (String id : new ArrayList<>(watchedCollections)) {
            if (collectionState(id) != COLLECTION_DONE || announcedCollections.contains(id)) {
                continue;
            }
            try {
                Catalog.Entry entry = savedCatalog(account.server).get(id);
                String title = entry == null ? id : entry.title;
                showFinishedNotification(title);
                announcedCollections.add(id);
                watchedCollections.remove(id);
            } catch (IOException ignored) {
                // Retry next update.
            }
        }
    }

    private void showFinishedNotification(String title) {
        Intent open = new Intent(context, MainActivity.class);
        android.app.PendingIntent openPending = android.app.PendingIntent.getActivity(context,
                title.hashCode(), open,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder notification = new NotificationCompat.Builder(context, "azify-downloads")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(context.getString(R.string.download_finished))
                .setContentText(title)
                .setContentIntent(openPending)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);
        try {
            NotificationManagerCompat.from(context).notify(0xA2F000 + Math.abs(title.hashCode() % 10_000),
                    notification.build());
        } catch (SecurityException ignored) {
            // Notification permission denied.
        }
    }

    boolean online() {
        ConnectivityManager connectivity = context.getSystemService(ConnectivityManager.class);
        NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(connectivity.getActiveNetwork());
        return capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    boolean onMobileData() {
        ConnectivityManager connectivity = context.getSystemService(ConnectivityManager.class);
        if (connectivity == null) {
            return false;
        }
        NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(connectivity.getActiveNetwork());
        return capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
    }

    void applyNetworkPolicy() {
        boolean allowMobile = settings.current().allowMobileDownloads;
        int requiredNetwork = allowMobile ? Requirements.NETWORK : Requirements.NETWORK_UNMETERED;
        manager.setRequirements(new Requirements(requiredNetwork));
    }

    DataSource.Factory playbackFactory() {
        CacheDataSource.Factory streamed = new CacheDataSource.Factory().setCache(playbackCache)
            .setUpstreamDataSourceFactory(upstream)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
        CacheDataSource.Factory cached = new CacheDataSource.Factory().setCache(downloadCache)
            .setUpstreamDataSourceFactory(streamed)
            .setCacheWriteDataSinkFactory(null)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
        return new ResolvingDataSource.Factory(cached, spec -> {
            Settings.Snapshot account = settings.current();
            if (!account.configured && !account.offlineAuthorized) {
                throw new AzifyClient.AuthenticationException();
            }
            AzifyClient.mediaHeaders(account.server, "", spec.uri.toString());
            return spec.buildUpon().setKey(spec.uri.toString()).build();
        });
    }

    @Nullable Download state(String url) { return states.get(url); }

    boolean completed(String url) {
        Download download = state(url);
        return download != null && download.state == Download.STATE_COMPLETED
            && (download.contentLength > 0 ? downloadCache.isCached(url, 0, download.contentLength)
            : !downloadCache.getCachedSpans(url).isEmpty());
    }

    private Set<String> urls(String server, boolean completeOnly) {
        Set<String> urls = new HashSet<>();
        for (Download download : states.values()) {
            String url = download.request.uri.toString();
            try {
                AzifyClient.mediaHeaders(server, "", url);
                if (completeOnly ? completed(url) : download.state != Download.STATE_REMOVING) {
                    urls.add(url);
                }
            } catch (IOException differentServer) {
                continue;
            }
        }
        return urls;
    }

    boolean available() {
        Settings.Snapshot account = settings.current();
        return account.offlineAuthorized && !urls(account.server, true).isEmpty();
    }

    synchronized void saveCatalog(String server, Catalog catalog) throws IOException {
        if (!catalogs.exists() && !catalogs.mkdirs()) {
            throw new IOException("Cannot save the offline library.");
        }
        AtomicFile file = catalogFile(server);
        FileOutputStream output = file.startWrite();
        try {
            output.write(catalog.json().getBytes(StandardCharsets.UTF_8));
            file.finishWrite(output);
        } catch (IOException failure) {
            file.failWrite(output);
            throw failure;
        }
        cachedCatalogServer = server;
        cachedCatalog = catalog;
    }

    private AtomicFile catalogFile(String server) {
        return new AtomicFile(new File(catalogs, AzifyClient.passwordHash(server) + ".json"));
    }

    synchronized Catalog savedCatalog(String server) throws IOException {
        if (cachedCatalog != null && server.equals(cachedCatalogServer)) {
            return cachedCatalog;
        }
        try {
            cachedCatalog = Catalog.parse(new String(catalogFile(server).readFully(), StandardCharsets.UTF_8), server);
            cachedCatalogServer = server;
            return cachedCatalog;
        } catch (JSONException | IllegalArgumentException invalid) {
            throw new IOException("The saved library is not valid.", invalid);
        }
    }

    Catalog library(boolean completeOnly) throws IOException {
        try {
            if (!indexed.await(10, TimeUnit.SECONDS)) {
                throw new IOException("Downloads are still being restored. Please try again.");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Download lookup interrupted.", interrupted);
        }
        Settings.Snapshot account = settings.current();
        if (!account.configured && !account.offlineAuthorized) {
            throw new AzifyClient.AuthenticationException();
        }
        try {
            return savedCatalog(account.server).onlyTracks(account.server, urls(account.server, completeOnly));
        } catch (JSONException invalid) {
            throw new IOException("The saved library is not valid.", invalid);
        }
    }

    List<DownloadRequest> requests(String id) throws IOException {
        Settings.Snapshot account = settings.current();
        if (!account.configured) { throw new AzifyClient.AuthenticationException(); }
        List<DownloadRequest> requests = new ArrayList<>();
        for (Catalog.Entry track : savedCatalog(account.server).tracksUnder(id)) {
            if (!completed(track.url)) {
                requests.add(new DownloadRequest.Builder(track.url, Uri.parse(track.url))
                        .setCustomCacheKey(track.url).build());
            }
        }
        return requests;
    }

    List<String> removalIds(String id) throws IOException {
        List<String> ids = new ArrayList<>();
        for (Catalog.Entry track : savedCatalog(settings.current().server).tracksUnder(id)) {
            if (states.containsKey(track.url)) { ids.add(track.url); }
        }
        return ids;
    }

    void add(List<DownloadRequest> requests) {
        applyNetworkPolicy();
        for (DownloadRequest request : requests) {
            DownloadService.sendAddDownload(context, MusicDownloadService.class, request, true);
        }
        if (!requests.isEmpty()) {
            DownloadService.sendResumeDownloads(context, MusicDownloadService.class, true);
        }
    }

    void remove(List<String> ids) {
        for (String id : ids) {
            DownloadService.sendRemoveDownload(context, MusicDownloadService.class, id, true);
        }
    }

    void pause() {
        manager.setStopReason(null, 1);
        manager.pauseDownloads();
    }

    long cacheBytesUsed() {
        return playbackCache.getCacheSpace();
    }

    synchronized void clearPlaybackCache() throws IOException {
        for (String key : new ArrayList<>(playbackCache.getKeys())) {
            playbackCache.removeResource(key);
        }
    }

    void prefetchUpcoming(List<String> urls) {
        if (urls == null || urls.isEmpty()) {
            return;
        }
        for (String url : urls) {
            if (url == null || url.isEmpty()) {
                continue;
            }
            if (!prefetching.add(url)) {
                continue;
            }
            prefetch.submit(() -> {
                try {
                    if (completed(url) || !playbackCache.getCachedSpans(url).isEmpty()) {
                        return;
                    }
                    if (!online() && downloadCache.getCachedSpans(url).isEmpty()) {
                        return;
                    }
                    DataSource source = playbackFactory().createDataSource();
                    try {
                        source.open(new DataSpec.Builder().setUri(url).setKey(url).build());
                        byte[] buffer = new byte[64 * 1024];
                        while (source.read(buffer, 0, buffer.length) != -1) {
                            // Read to EOF so Media3 writes bytes to playback cache.
                        }
                    } finally {
                        source.close();
                    }
                } catch (Exception ignored) {
                    // Best-effort prefetch.
                } finally {
                    prefetching.remove(url);
                }
            });
        }
    }

    List<String> debugMergedMp3List() {
        Set<String> manual = new HashSet<>();
        try {
            for (Catalog.Entry entry : library(true).tracks()) {
                String source = entry.url == null && entry.id.startsWith("track:")
                        ? entry.id.substring("track:".length()) : entry.url;
                String path = relativeMp3Path(source);
                if (path != null) {
                    manual.add(path);
                }
            }
        } catch (IOException ignored) {
            // Manual list is unavailable without a valid offline catalog.
        }

        Set<String> cached = new HashSet<>();
        for (String key : new ArrayList<>(playbackCache.getKeys())) {
            String path = relativeMp3Path(key);
            if (path != null) {
                cached.add(path);
            }
        }

        Set<String> merged = new HashSet<>();
        merged.addAll(manual);
        merged.addAll(cached);
        List<String> sorted = new ArrayList<>(merged);
        sorted.sort((left, right) -> {
            int leftSlash = left.lastIndexOf('/');
            int rightSlash = right.lastIndexOf('/');
            String leftFolder = leftSlash < 0 ? "" : left.substring(0, leftSlash);
            String rightFolder = rightSlash < 0 ? "" : right.substring(0, rightSlash);
            int byFolder = leftFolder.compareToIgnoreCase(rightFolder);
            if (byFolder != 0) {
                return byFolder;
            }
            String leftName = leftSlash < 0 ? left : left.substring(leftSlash + 1);
            String rightName = rightSlash < 0 ? right : right.substring(rightSlash + 1);
            return leftName.compareToIgnoreCase(rightName);
        });

        List<String> labeled = new ArrayList<>(sorted.size());
        for (String item : sorted) {
            boolean inManual = manual.contains(item);
            boolean inCache = cached.contains(item);
            String label = inManual && inCache ? "USER+CACHE" : inManual ? "USER" : "CACHE";
            labeled.add("[" + label + "] " + item);
        }
        return Collections.unmodifiableList(labeled);
    }

    @Nullable
    private static String relativeMp3Path(@Nullable String source) {
        if (source == null) {
            return null;
        }
        String value = source;
        okhttp3.HttpUrl parsed = okhttp3.HttpUrl.parse(source);
        if (parsed != null) {
            value = parsed.encodedPath();
        }
        if (value.startsWith("/music/")) {
            value = value.substring(7);
        }
        value = Uri.decode(value);
        return value.toLowerCase(java.util.Locale.ROOT).endsWith(".mp3") ? value : null;
    }
}