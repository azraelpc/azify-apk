package org.azify.client;

import android.os.SystemClock;
import androidx.media3.common.util.UnstableApi;

import java.io.IOException;

@UnstableApi
final class CatalogRepository {
    private final Settings settings;
    private final AzifyClient client;
    private final Downloads downloads;
    private volatile Catalog cached;
    private volatile Settings.Snapshot account;
    private long fetchedAt;

    CatalogRepository(Settings settings, AzifyClient client, Downloads downloads) {
        this.settings = settings;
        this.client = client;
        this.downloads = downloads;
    }

    Catalog get() throws IOException {
        Settings.Snapshot current = settings.current();
        if (!current.configured) {
            if (current.offlineAuthorized) {
                Catalog local = downloads.library(true);
                if (!local.tracks().isEmpty()) { return local; }
            }
            if (current.server.isEmpty()) {
                throw new IOException("Set the AZify Server URL on your phone.");
            }
            throw new AzifyClient.AuthenticationException();
        }
        if (!downloads.online() && current.offlineAuthorized) {
            Catalog local = downloads.library(true);
            if (!local.tracks().isEmpty()) { return local; }
        }
        Catalog existing = cached;
        Settings.Snapshot previous = account;
        if (existing != null && previous != null && current.sameAccount(previous)
                && SystemClock.elapsedRealtime() - fetchedAt < 60_000) {
            return existing;
        }
        Catalog loaded;
        try {
            loaded = client.fetch(current.server, current.token);
        } catch (AzifyClient.AuthenticationException denied) {
            throw denied;
        } catch (IOException unavailable) {
            try {
                Catalog local = downloads.library(true);
                if (!local.tracks().isEmpty()) { return local; }
            } catch (IOException noOfflineLibrary) {
                throw unavailable;
            }
            throw unavailable;
        }
        if (!current.sameAccount(settings.current())) {
            throw new IOException("The connection has changed. Refresh the library.");
        }
        downloads.syncSubscribedCollections(loaded);
        downloads.saveCatalog(current.server, loaded);
        account = current;
        fetchedAt = SystemClock.elapsedRealtime();
        cached = loaded;
        return loaded;
    }

    void invalidate() {
        cached = null;
    }
}
