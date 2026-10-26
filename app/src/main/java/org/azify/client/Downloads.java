package org.azify.client;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.AtomicFile;

import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.ResolvingDataSource;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.NoOpCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.offline.Download;
import androidx.media3.exoplayer.offline.DownloadCursor;
import androidx.media3.exoplayer.offline.DownloadManager;
import androidx.media3.exoplayer.offline.DownloadRequest;
import androidx.media3.exoplayer.offline.DownloadService;

import org.json.JSONException;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
    private static Downloads instance;
    private final Context context;
    private final Settings settings;
    private final SimpleCache cache;
    private final DataSource.Factory upstream;
    private final DownloadManager manager;
    private final Map<String, Download> states = new ConcurrentHashMap<>();
    private final Set<Runnable> listeners = new CopyOnWriteArraySet<>();
    private final ExecutorService disk = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final File catalogs;
    private final CountDownLatch indexed = new CountDownLatch(1);

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
        cache = new SimpleCache(new File(context.getFilesDir(), "downloaded-audio"),
                new NoOpCacheEvictor(), database);
        AzifyClient client = new AzifyClient();
        upstream = new ResolvingDataSource.Factory(new OkHttpDataSource.Factory(client.streamingHttp), spec -> {
            Settings.Snapshot account = settings.current();
            if (!account.configured) {
                throw new AzifyClient.AuthenticationException();
            }
            return spec.withRequestHeaders(AzifyClient.mediaHeaders(account.server, account.token, spec.uri.toString()));
        });
        manager = new DownloadManager(context, database, cache, upstream, Runnable::run);
        manager.setMaxParallelDownloads(2);
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
        for (Runnable listener : listeners) { listener.run(); }
    }

    boolean online() {
        ConnectivityManager connectivity = context.getSystemService(ConnectivityManager.class);
        NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(connectivity.getActiveNetwork());
        return capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    DataSource.Factory playbackFactory() {
        CacheDataSource.Factory cached = new CacheDataSource.Factory().setCache(cache)
                .setUpstreamDataSourceFactory(upstream).setCacheWriteDataSinkFactory(null)
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
                && (download.contentLength > 0 ? cache.isCached(url, 0, download.contentLength)
                : !cache.getCachedSpans(url).isEmpty());
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
    }

    private AtomicFile catalogFile(String server) {
        return new AtomicFile(new File(catalogs, AzifyClient.passwordHash(server) + ".json"));
    }

    synchronized Catalog savedCatalog(String server) throws IOException {
        try {
            return Catalog.parse(new String(catalogFile(server).readFully(), StandardCharsets.UTF_8), server);
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
}