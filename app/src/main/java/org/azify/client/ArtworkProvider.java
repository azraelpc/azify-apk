package org.azify.client;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;

public final class ArtworkProvider extends ContentProvider {
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final AzifyClient client = new AzifyClient();

    static Uri root(Context context) {
        return new Uri.Builder().scheme("content").authority(context.getPackageName() + ".artwork")
                .appendPath("image").build();
    }

    static Uri uri(Context context, String source) {
        return root(context).buildUpon().appendQueryParameter("source", source).build();
    }

    @Override public boolean onCreate() {
        return true;
    }

    @Override public ParcelFileDescriptor openFile(@NonNull Uri uri, @NonNull String mode)
            throws FileNotFoundException {
        if (!mode.equals("r")) {
            throw new FileNotFoundException("Read-only artwork");
        }
        Settings.Snapshot account = Settings.get(Objects.requireNonNull(getContext())).current();
        if (!account.configured || account.server.isEmpty()) {
            throw new FileNotFoundException("Artwork is not available");
        }
        HttpUrl source = HttpUrl.parse(Objects.toString(uri.getQueryParameter("source"), ""));
        if (source == null || !AzifyClient.isArtworkUrl(account.server, source.toString())) {
            throw new FileNotFoundException("Artwork is not available");
        }
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createReliablePipe();
            executor.execute(() -> {
                Request.Builder request = new Request.Builder().url(source);
                AzifyClient.headers(account.token).forEach(request::header);
                try (Response response = client.http.newCall(request.build()).execute();
                     ParcelFileDescriptor.AutoCloseOutputStream output =
                             new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                    if (!response.isSuccessful() || response.body() == null) {
                        pipe[1].closeWithError("Artwork download failed");
                        return;
                    }
                    try (InputStream input = response.body().byteStream()) {
                        byte[] buffer = new byte[8192];
                        int count;
                        int total = 0;
                        while ((count = input.read(buffer)) != -1) {
                            total += count;
                            if (total > 10 * 1024 * 1024) {
                                throw new IOException("Artwork exceeds size limit");
                            }
                            output.write(buffer, 0, count);
                        }
                    }
                } catch (IOException failure) {
                    try {
                        pipe[1].closeWithError("Artwork unavailable");
                    } catch (IOException closed) {
                        return;
                    }
                }
            });
            return pipe[0];
        } catch (IOException failure) {
            throw new FileNotFoundException("Could not open artwork");
        }
    }

    @Override public String getType(@NonNull Uri uri) { return "image/*"; }
    @Override public Cursor query(@NonNull Uri uri, @Nullable String[] projection,
            @Nullable String selection, @Nullable String[] selectionArgs, @Nullable String sortOrder) { return null; }
    @Override public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) { return null; }
    @Override public int delete(@NonNull Uri uri, @Nullable String selection, @Nullable String[] selectionArgs) { return 0; }
    @Override public int update(@NonNull Uri uri, @Nullable ContentValues values,
            @Nullable String selection, @Nullable String[] selectionArgs) { return 0; }
}
