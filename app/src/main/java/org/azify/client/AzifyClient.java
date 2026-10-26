package org.azify.client;

import android.content.Context;

import org.json.JSONException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import okio.ForwardingSource;
import okio.Okio;
import okio.Source;

final class AzifyClient {
    static final String DEFAULT_SERVER = "";
    private final Context usageContext;

    AzifyClient() {
        this(null);
    }

    AzifyClient(Context context) {
        usageContext = context == null ? null : context.getApplicationContext();
    }

    final OkHttpClient http = new OkHttpClient.Builder()
            .dispatcher(createDispatcher())
            .connectionPool(new ConnectionPool(24, 5, TimeUnit.MINUTES))
            .protocols(Collections.singletonList(Protocol.HTTP_1_1))
            .addNetworkInterceptor(createUsageInterceptor())
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build();
    final OkHttpClient streamingHttp = http.newBuilder().callTimeout(0, TimeUnit.MILLISECONDS).build();

    private static Dispatcher createDispatcher() {
        Dispatcher dispatcher = new Dispatcher();
        dispatcher.setMaxRequests(64);
        dispatcher.setMaxRequestsPerHost(16);
        return dispatcher;
    }

    private Interceptor createUsageInterceptor() {
        return chain -> {
            Response response = chain.proceed(chain.request());
            ResponseBody body = response.body();
            if (usageContext == null || body == null) {
                return response;
            }
            Source source = body.source();
            CountingSource counting = new CountingSource(source, usageContext);
            BufferedSource buffered = Okio.buffer(counting);
            ResponseBody wrapped = ResponseBody.create(body.contentType(), body.contentLength(), buffered);
            return response.newBuilder().body(wrapped).build();
        };
    }

    private static final class CountingSource extends ForwardingSource {
        private final Context context;
        private long total;
        private boolean committed;

        CountingSource(Source delegate, Context context) {
            super(delegate);
            this.context = context;
        }

        @Override public long read(Buffer sink, long byteCount) throws java.io.IOException {
            long read = super.read(sink, byteCount);
            if (read > 0) {
                total += read;
            }
            if (read == -1) {
                commit();
            }
            return read;
        }

        @Override public void close() throws java.io.IOException {
            try {
                super.close();
            } finally {
                commit();
            }
        }

        private void commit() {
            if (committed || total <= 0) {
                return;
            }
            committed = true;
            BandwidthUsage.recordBytes(context, total);
        }
    }

    static final class AuthenticationException extends IOException {
        AuthenticationException() {
            super("Access denied. Enter the server password in Settings on your phone.");
        }
    }

    static final class Connection {
        final Catalog catalog;
        final String token;
        final boolean requiresPassword;

        Connection(Catalog catalog, String token, boolean requiresPassword) {
            this.catalog = catalog;
            this.token = token;
            this.requiresPassword = requiresPassword;
        }
    }

    Connection connect(String server, String token) throws IOException {
        try {
            return new Connection(fetch(server, ""), "", false);
        } catch (AuthenticationException required) {
            if (token.isEmpty()) {
                throw required;
            }
            return new Connection(fetch(server, token), token, true);
        }
    }

    static String normalizeServer(String input) {
        if (input.trim().isEmpty()) {
            throw new IllegalArgumentException("Enter the AZify Server URL.");
        }
        HttpUrl url = HttpUrl.get(input.trim());
        if (!url.username().isEmpty() || !url.password().isEmpty()
                || url.query() != null || url.fragment() != null) {
            throw new IllegalArgumentException("Use an HTTP or HTTPS base URL without credentials, query parameters or fragments.");
        }
        return url.encodedPath().endsWith("/") ? url.toString()
                : url.newBuilder().addPathSegment("").build().toString();
    }

    static boolean hasExplicitScheme(String input) {
        String value = input == null ? "" : input.trim().toLowerCase(Locale.ROOT);
        return value.startsWith("https://") || value.startsWith("http://");
    }

    static List<String> connectionCandidates(String input) {
        String value = input == null ? "" : input.trim();
        List<String> candidates = new ArrayList<>(2);
        if (hasExplicitScheme(value)) {
            candidates.add(normalizeServer(value));
            return candidates;
        }
        candidates.add(normalizeServer("https://" + value));
        candidates.add(normalizeServer("http://" + value));
        return candidates;
    }

    static HttpUrl endpoint(String server, String relativePath) {
        return HttpUrl.get(normalizeServer(server)).newBuilder().addPathSegments(relativePath).build();
    }

    static boolean isArtworkUrl(String server, String target) {
        try {
            mediaHeaders(server, "", target);
            HttpUrl requested = HttpUrl.get(target);
            return requested.query() == null && requested.fragment() == null
                    && (requested.encodedPath().startsWith(endpoint(server, "music/").encodedPath())
                    || requested.encodedPath().equals(endpoint(server, "nocover.jpg").encodedPath()));
        } catch (IOException | IllegalArgumentException invalid) {
            return false;
        }
    }

    static String passwordHash(String password) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(password.trim().getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte value : bytes) {
                result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static Map<String, String> headers(String token) {
        return token.isEmpty() ? Collections.emptyMap()
                : Collections.singletonMap("X-Azify-Pass", token);
    }

    static Map<String, String> mediaHeaders(String server, String token, String target) throws IOException {
        HttpUrl configured;
        try {
            configured = HttpUrl.get(normalizeServer(server));
        } catch (IllegalArgumentException invalid) {
            throw new IOException("The AZify Server URL is not configured.", invalid);
        }
        HttpUrl requested = HttpUrl.parse(target);
        if (requested == null || !configured.scheme().equals(requested.scheme())
                || !configured.host().equals(requested.host()) || configured.port() != requested.port()
                || !requested.username().isEmpty() || !requested.password().isEmpty()
                || !(requested.encodedPath().startsWith(endpoint(server, "music/").encodedPath())
                || requested.encodedPath().equals(endpoint(server, "nocover.jpg").encodedPath()))) {
            throw new IOException("This resource does not belong to the configured AZify Server URL.");
        }
        return headers(token);
    }

    Catalog fetch(String server, String token) throws IOException {
        String base = normalizeServer(server);
        Request.Builder request = new Request.Builder()
            .url(endpoint(base, "api/music"));
        headers(token).forEach(request::header);
        try (Response response = http.newCall(request.build()).execute()) {
            if (response.code() == 401) {
                throw new AuthenticationException();
            }
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("The server returned HTTP " + response.code() + ".");
            }
            try {
                return Catalog.parse(response.body().string(), base);
            } catch (JSONException | IllegalArgumentException malformed) {
                throw new IOException("The server did not return a valid Azify catalog.", malformed);
            }
        }
    }
}
