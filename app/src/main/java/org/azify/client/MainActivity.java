package org.azify.client;

import android.app.SearchManager;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RadioGroup;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSourceBitmapLoader;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.session.LibraryResult;
import androidx.media3.session.MediaBrowser;
import androidx.media3.session.SessionError;
import androidx.media3.session.SessionResult;
import androidx.media3.session.SessionToken;
import androidx.media3.ui.PlayerControlView;
import androidx.media3.exoplayer.offline.Download;
import androidx.core.app.ActivityCompat;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@UnstableApi
public final class MainActivity extends AppCompatActivity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Executor ui = command -> main.post(command);
    private final ListeningExecutorService workers =
            MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(3));
    private final List<MediaItem> rows = new ArrayList<>();
    private final ArrayList<String> history = new ArrayList<>();
    private final Map<String, ListenableFuture<Bitmap>> pendingImages = new HashMap<>();
    private final LruCache<String, Bitmap> images = new LruCache<>(12 * 1024 * 1024) {
        @Override protected int sizeOf(@NonNull String key, @NonNull Bitmap value) {
            return value.getAllocationByteCount();
        }
    };
    private MediaBrowser browser;
    private ListenableFuture<MediaBrowser> browserFuture;
    private DataSourceBitmapLoader imageLoader;
    private LibraryAdapter adapter;
    private TextView status;
    private TextView breadcrumb;
    private EditText search;
    private PlayerControlView controls;
    private String parent = Catalog.ALBUMS;
    private boolean queueView;
    private int requestVersion;
    private AlertDialog loginDialog;
    private boolean loginOffered;
    private Downloads downloads;
    private final Runnable searchLater = this::load;
    private final Runnable downloadChanged = () -> {
        if (browser != null) {
            if (parent.equals(Catalog.DOWNLOADS)) { load(); }
            adapter.notifyDataSetChanged();
            offerLogin();
        }
    };
    private final Runnable downloadProgress = new Runnable() {
        @Override public void run() {
            if (adapter != null) { adapter.notifyDataSetChanged(); }
            main.postDelayed(this, 1_000);
        }
    };

    @Override protected void onCreate(@Nullable Bundle state) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        configureCompactLandscape();
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root), (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.ime());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        imageLoader = new DataSourceBitmapLoader(workers, new DefaultDataSource.Factory(this), null, 320);
        status = findViewById(R.id.status);
        breadcrumb = findViewById(R.id.breadcrumb);
        search = findViewById(R.id.search);
        controls = findViewById(R.id.controls);
        controls.setShowTimeoutMs(0);
        adapter = new LibraryAdapter();
        ListView list = findViewById(R.id.library);
        list.setAdapter(adapter);
        list.setOnItemClickListener((container, view, position, id) -> {
            MediaItem selected = rows.get(position);
            if (queueView) {
                if (browser != null) {
                    browser.seekToDefaultPosition(position);
                    browser.prepare();
                    browser.play();
                }
            } else if (Boolean.TRUE.equals(selected.mediaMetadata.isBrowsable)) {
                history.add(parent);
                parent = selected.mediaId;
                search.setText("");
                load();
            } else {
                play(selected.mediaId);
            }
        });
        findViewById(R.id.settings).setOnClickListener(view -> showLogin());
        findViewById(R.id.sign_out).setOnClickListener(view -> signOut());
        updateSessionUi();
        findViewById(R.id.refresh).setOnClickListener(view -> refresh(false));
        findViewById(R.id.play_all).setOnClickListener(view ->
            play(parent.equals(Catalog.DOWNLOADS) ? Catalog.OFFLINE + Catalog.ALL : Catalog.ALL));
        findViewById(R.id.back).setOnClickListener(view -> goBack());
        RadioGroup tabs = findViewById(R.id.tabs);
        tabs.setOnCheckedChangeListener((group, checked) -> {
            queueView = checked == R.id.tab_queue;
                parent = checked == R.id.tab_folders ? Catalog.FOLDERS
                    : checked == R.id.tab_downloads ? Catalog.DOWNLOADS : Catalog.ALBUMS;
            history.clear();
            search.setText("");
            search.setVisibility(queueView ? View.GONE : View.VISIBLE);
            load();
        });
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                requestVersion++;
                main.removeCallbacks(searchLater);
                main.postDelayed(searchLater, 300);
            }
            @Override public void afterTextChanged(Editable value) { }
        });
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (!search.getText().toString().isEmpty()) {
                    search.setText("");
                } else if (!history.isEmpty()) {
                    goBack();
                } else {
                    moveTaskToBack(true);
                }
            }
        });
        if (state != null) {
            tabs.check(state.getInt("tab", R.id.tab_albums));
            parent = state.getString("parent", Catalog.ALBUMS);
            ArrayList<String> saved = state.getStringArrayList("history");
            if (saved != null) {
                history.addAll(saved);
            }
            search.setText(state.getString("query", ""));
        }
    }

    private void configureCompactLandscape() {
        android.content.res.Configuration configuration = getResources().getConfiguration();
        if (configuration.orientation != android.content.res.Configuration.ORIENTATION_LANDSCAPE
                || configuration.screenHeightDp >= 480) {
            return;
        }
        LinearLayout root = findViewById(R.id.root);
        LinearLayout header = findViewById(R.id.header);
        LinearLayout navigation = findViewById(R.id.navigation);
        for (int id : new int[] {R.id.refresh, R.id.settings, R.id.sign_out}) {
            View button = findViewById(id);
            header.removeView(button);
            navigation.addView(button, navigation.getChildCount() - 1);
        }
        header.setVisibility(View.GONE);
        View tabs = findViewById(R.id.tabs);
        View input = findViewById(R.id.search);
        root.removeView(tabs);
        root.removeView(input);
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.addView(tabs, new LinearLayout.LayoutParams(0, -1, 1));
        top.addView(input, new LinearLayout.LayoutParams(0, -1, 1));
        root.addView(top, 1, new LinearLayout.LayoutParams(-1,
                (int) (48 * getResources().getDisplayMetrics().density)));
    }

    @Override protected void onStart() {
        super.onStart();
        downloads = Downloads.get(this);
        downloads.addListener(downloadChanged);
        main.post(downloadProgress);
        updateSessionUi();
        browserFuture = new MediaBrowser.Builder(this,
                new SessionToken(this, new ComponentName(this, PlaybackService.class))).buildAsync();
        Futures.addCallback(browserFuture, new FutureCallback<MediaBrowser>() {
            @Override public void onSuccess(MediaBrowser connected) {
                if (isFinishing() || isDestroyed() || !getLifecycle().getCurrentState()
                        .isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
                    return;
                }
                browser = connected;
                controls.setPlayer(browser);
                controls.show();
                browser.addListener(new Player.Listener() {
                    @Override public void onEvents(Player player, Player.Events events) {
                        updatePlaying();
                        if (queueView && events.contains(Player.EVENT_TIMELINE_CHANGED)) {
                            load();
                        }
                    }
                    @Override public void onPlayerError(PlaybackException error) {
                        showStatus(getString(R.string.playback_error));
                    }
                });
                updatePlaying();
                load();
                handleVoiceIntent();
                offerLogin();
            }
            @Override public void onFailure(@NonNull Throwable failure) {
                if (!isDestroyed()) {
                    showStatus(getString(R.string.connect_failed));
                }
            }
        }, ui);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleVoiceIntent();
    }

    private void handleVoiceIntent() {
        Intent intent = getIntent();
        if (browser == null || !"android.media.action.MEDIA_PLAY_FROM_SEARCH".equals(intent.getAction())) {
            return;
        }
        String query = intent.getStringExtra(SearchManager.QUERY);
        intent.setAction(Intent.ACTION_MAIN);
        if (!Settings.get(this).current().configured) {
            return;
        }
        browser.setMediaItem(new MediaItem.Builder().setMediaId(Catalog.ALL)
                .setRequestMetadata(new MediaItem.RequestMetadata.Builder()
                        .setSearchQuery(query == null ? "" : query).build()).build());
        browser.prepare();
        browser.play();
    }

    @Override protected void onStop() {
        requestVersion++;
        main.removeCallbacks(searchLater);
        main.removeCallbacks(downloadProgress);
        if (downloads != null) { downloads.removeListener(downloadChanged); }
        controls.setPlayer(null);
        browser = null;
        if (browserFuture != null) {
            MediaBrowser.releaseFuture(browserFuture);
            browserFuture = null;
        }
        super.onStop();
    }

    @Override protected void onDestroy() {
        if (loginDialog != null) {
            loginDialog.dismiss();
        }
        main.removeCallbacksAndMessages(null);
        workers.shutdownNow();
        images.evictAll();
        super.onDestroy();
    }

    @Override protected void onSaveInstanceState(@NonNull Bundle state) {
        super.onSaveInstanceState(state);
        state.putInt("tab", ((RadioGroup) findViewById(R.id.tabs)).getCheckedRadioButtonId());
        state.putString("parent", parent);
        state.putStringArrayList("history", history);
        state.putString("query", search.getText().toString());
    }

    private void goBack() {
        if (!history.isEmpty()) {
            parent = history.remove(history.size() - 1);
            search.setText("");
            load();
        }
    }

    private void load() {
        if (browser == null) {
            return;
        }
        int version = ++requestVersion;
        String query = search.getText().toString().trim();
        findViewById(R.id.back).setVisibility(history.isEmpty() ? View.INVISIBLE : View.VISIBLE);
        breadcrumb.setText(queueView ? getString(R.string.queue)
            : parent.equals(Catalog.DOWNLOADS) ? getString(R.string.downloads)
                : !query.isEmpty() ? query : parent.equals(Catalog.ALBUMS) ? getString(R.string.albums)
                : parent.equals(Catalog.FOLDERS) ? getString(R.string.folders)
                : parent.substring(parent.indexOf(':') + 1));
        if (queueView) {
            rows.clear();
            for (int index = 0; index < browser.getMediaItemCount(); index++) {
                rows.add(browser.getMediaItemAt(index));
            }
            adapter.notifyDataSetChanged();
            busy(false);
            showStatus(rows.isEmpty() ? getString(R.string.empty) : null);
            return;
        }
        busy(true);
        showStatus(null);
        ListenableFuture<LibraryResult<com.google.common.collect.ImmutableList<MediaItem>>> result =
            query.isEmpty() || parent.equals(Catalog.DOWNLOADS) ? browser.getChildren(parent, 0, Integer.MAX_VALUE, null)
                        : browser.getSearchResult(query, 0, Integer.MAX_VALUE, null);
        Futures.addCallback(result, new FutureCallback<LibraryResult<com.google.common.collect.ImmutableList<MediaItem>>>() {
            @Override public void onSuccess(LibraryResult<com.google.common.collect.ImmutableList<MediaItem>> response) {
                if (version != requestVersion || isDestroyed()) {
                    return;
                }
                busy(false);
                rows.clear();
                if (response.resultCode == LibraryResult.RESULT_SUCCESS && response.value != null) {
                    for (MediaItem entry : response.value) {
                        if (!parent.equals(Catalog.DOWNLOADS) || query.isEmpty()
                                || String.valueOf(entry.mediaMetadata.title).toLowerCase(java.util.Locale.ROOT)
                                .contains(query.toLowerCase(java.util.Locale.ROOT))) {
                            rows.add(entry);
                        }
                    }
                    showStatus(rows.isEmpty() ? getString(R.string.empty)
                            : downloads != null && !downloads.online() ? getString(R.string.offline_mode) : null);
                } else {
                    showStatus(response.resultCode == SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED
                            ? getString(R.string.auth_error) : getString(R.string.offline));
                }
                adapter.notifyDataSetChanged();
                if (response.resultCode == SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED) {
                    showLogin(true);
                }
            }
            @Override public void onFailure(@NonNull Throwable failure) {
                if (version == requestVersion && !isDestroyed()) {
                    busy(false);
                    rows.clear();
                    adapter.notifyDataSetChanged();
                    showStatus(getString(R.string.offline));
                }
            }
        }, ui);
    }

    private void refresh(boolean reset) {
        if (browser == null) {
            return;
        }
        Bundle args = new Bundle();
        args.putBoolean("reset", reset);
        busy(true);
        if (reset) {
            rows.clear();
            adapter.notifyDataSetChanged();
            images.evictAll();
            history.clear();
            parent = Catalog.ALBUMS;
            ((RadioGroup) findViewById(R.id.tabs)).check(R.id.tab_albums);
        }
        Futures.addCallback(browser.sendCustomCommand(PlaybackService.RELOAD, args), new FutureCallback<SessionResult>() {
            @Override public void onSuccess(SessionResult result) { load(); }
            @Override public void onFailure(@NonNull Throwable failure) {
                busy(false);
                showStatus(getString(R.string.offline));
            }
        }, ui);
    }

    private void play(String id) {
        if (browser == null) {
            return;
        }
        if (id.startsWith(Catalog.OFFLINE) && !id.equals(Catalog.OFFLINE + Catalog.ALL)) {
            for (MediaItem row : rows) {
                if (row.mediaId.equals(id) && downloads != null && downloadUrl(row) != null
                        && !downloads.completed(downloadUrl(row))) {
                    if (!downloads.online() || !Settings.get(this).current().configured) {
                        showStatus(getString(R.string.download_incomplete));
                        return;
                    }
                    id = id.substring(Catalog.OFFLINE.length());
                    break;
                }
            }
        }
        if (!Settings.get(this).current().configured && (downloads == null || !downloads.available())) {
            showLogin();
            return;
        }
        MediaItem current = browser.getCurrentMediaItem();
        if (current != null && current.mediaId.equals(id)) {
            if (browser.isPlaying()) {
                browser.pause();
            } else {
                browser.prepare();
                browser.play();
            }
        } else {
            browser.setMediaItem(new MediaItem.Builder().setMediaId(id).build());
            browser.prepare();
            browser.play();
        }
    }

    private void updatePlaying() {
        if (browser == null) {
            return;
        }
        MediaMetadata metadata = browser.getMediaMetadata();
        ((TextView) findViewById(R.id.current_title)).setText(metadata.title == null
                ? getString(R.string.nothing_playing) : metadata.title);
        ((TextView) findViewById(R.id.current_album)).setText(metadata.albumTitle);
        loadImage(findViewById(R.id.current_art), metadata.artworkUri, false);
        adapter.notifyDataSetChanged();
    }

    private void busy(boolean loading) {
        findViewById(R.id.loading).setVisibility(loading ? View.VISIBLE : View.INVISIBLE);
        findViewById(R.id.refresh).setEnabled(!loading);
    }

    private void showStatus(@Nullable String message) {
        status.setText(message);
        status.setVisibility(message == null ? View.GONE : View.VISIBLE);
    }

    private void showLogin() {
        showLogin(false);
    }

    private void showLogin(boolean passwordRequired) {
        if (isFinishing() || (loginDialog != null && loginDialog.isShowing())) {
            return;
        }
        Settings.Snapshot account = Settings.get(this).current();
        boolean canSignOut = account.requiresPassword && (account.configured || account.offlineAuthorized);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        form.setPadding(padding, padding / 2, padding, 0);
        EditText server = new EditText(this);
        server.setId(R.id.server_url);
        server.setHint(R.string.server);
        server.setSingleLine(true);
        server.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        server.setText(account.server);
        form.addView(server, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout passwordFields = new LinearLayout(this);
        passwordFields.setOrientation(LinearLayout.VERTICAL);
        passwordFields.setVisibility(passwordRequired || account.requiresPassword ? View.VISIBLE : View.GONE);
        EditText password = new EditText(this);
        password.setId(R.id.server_password);
        password.setHint(account.token.isEmpty() ? R.string.password : R.string.password_saved);
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setSingleLine(true);
        password.setSaveEnabled(false);
        password.setAutofillHints(View.AUTOFILL_HINT_PASSWORD);
        passwordFields.addView(password, new LinearLayout.LayoutParams(-1, -2));
        CheckBox remember = new CheckBox(this);
        remember.setId(R.id.remember_password);
        remember.setText(R.string.remember_password);
        remember.setChecked(account.rememberPassword);
        remember.setSaveEnabled(false);
        passwordFields.addView(remember, new LinearLayout.LayoutParams(-1, -2));
        form.addView(passwordFields, new LinearLayout.LayoutParams(-1, -2));
        TextView error = new TextView(this);
        error.setPadding(0, padding / 2, 0, padding / 2);
        error.setTextColor(Color.rgb(245, 160, 160));
        form.addView(error, new LinearLayout.LayoutParams(-1, -2));
        server.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                password.setText("");
                passwordFields.setVisibility(View.GONE);
                remember.setChecked(false);
                error.setText(null);
            }
            @Override public void afterTextChanged(Editable value) { }
        });
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(R.string.connection_title).setView(form)
                .setPositiveButton(R.string.connect, null).setNegativeButton(R.string.cancel, null);
        if (canSignOut) {
            builder.setNeutralButton(R.string.logout, (dialog, which) -> signOut());
        }
        loginDialog = builder.create();
        loginDialog.setOnShowListener(ignored -> {
            loginDialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            loginDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
                final String normalized;
                try {
                    normalized = AzifyClient.normalizeServer(server.getText().toString());
                } catch (IllegalArgumentException invalid) {
                    error.setText(invalid.getMessage());
                    return;
                }
                String entered = password.getText().toString();
                String token = entered.isEmpty() && normalized.equals(account.server) ? account.token
                        : entered.isEmpty() ? "" : AzifyClient.passwordHash(entered);
                boolean keepPassword = remember.isChecked();
                password.setText("");
                AlertDialog active = loginDialog;
                active.setCancelable(false);
                active.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                active.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
                if (canSignOut) {
                    active.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(false);
                }
                error.setText(R.string.connecting);
                ListenableFuture<AzifyClient.Connection> validation = workers.submit(() -> {
                    AzifyClient.Connection connection = new AzifyClient().connect(normalized, token);
                    Settings.get(MainActivity.this).save(normalized, connection.token,
                            connection.requiresPassword, keepPassword);
                        Downloads.get(MainActivity.this).saveCatalog(normalized, connection.catalog);
                    return connection;
                });
                Futures.addCallback(validation, new FutureCallback<AzifyClient.Connection>() {
                    @Override public void onSuccess(AzifyClient.Connection connection) {
                        if (isDestroyed()) {
                            return;
                        }
                        active.dismiss();
                        updateSessionUi();
                        refresh(!account.server.equals(normalized) || !account.token.equals(connection.token));
                    }
                    @Override public void onFailure(@NonNull Throwable failure) {
                        if (isDestroyed()) {
                            return;
                        }
                        active.setCancelable(true);
                        active.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                        active.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(true);
                        if (canSignOut) {
                            active.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(true);
                        }
                        if (failure instanceof AzifyClient.AuthenticationException) {
                            passwordFields.setVisibility(View.VISIBLE);
                            password.requestFocus();
                        }
                        error.setText(failure instanceof AzifyClient.AuthenticationException
                                ? getString(token.isEmpty() ? R.string.password_required : R.string.auth_error)
                                : failure.getMessage() == null ? getString(R.string.offline) : failure.getMessage());
                    }
                }, ui);
            });
        });
        loginDialog.show();
    }

    private void updateSessionUi() {
        Settings.Snapshot account = Settings.get(this).current();
        findViewById(R.id.sign_out).setVisibility(account.requiresPassword
                && (account.configured || account.offlineAuthorized) ? View.VISIBLE : View.GONE);
    }

    private void offerLogin() {
        if (!Settings.get(this).current().configured && !loginOffered
                && downloads != null && downloads.initialized() && !downloads.available()) {
            loginOffered = true;
            showLogin();
        }
    }

    private void signOut() {
        if (loginDialog != null) { loginDialog.dismiss(); }
        if (browser != null) {
            browser.pause();
            browser.stop();
            browser.clearMediaItems();
        }
        if (downloads != null) { downloads.pause(); }
        Settings.get(this).signOut();
        updateSessionUi();
        refresh(true);
        showLogin(true);
    }

    private String downloadUrl(MediaItem item) {
        return item.mediaMetadata.extras == null ? null : item.mediaMetadata.extras.getString("downloadUrl");
    }

    private String downloadStatus(String url) {
        if (downloads == null || url == null) { return null; }
        Download download = downloads.state(url);
        if (download == null) { return null; }
        if (downloads.completed(url)) { return getString(R.string.downloaded); }
        if (download.state == Download.STATE_FAILED) { return getString(R.string.download_failed); }
        if (download.state == Download.STATE_STOPPED) { return getString(R.string.download_paused); }
        if (download.state == Download.STATE_DOWNLOADING) {
            return getString(R.string.download_percent, Math.max(0, Math.round(download.getPercentDownloaded())));
        }
        return getString(R.string.download_queued);
    }

    private void downloadMenu(MediaItem entry, View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        String url = downloadUrl(entry);
        Download state = downloads == null || url == null ? null : downloads.state(url);
        if (state == null || state.state == Download.STATE_FAILED || state.state == Download.STATE_STOPPED
                || state.state == Download.STATE_QUEUED) {
            menu.getMenu().add(0, 1, 0, R.string.download);
        }
        if (state != null || Boolean.TRUE.equals(entry.mediaMetadata.isBrowsable)) {
            menu.getMenu().add(0, 2, 1, state != null && state.state != Download.STATE_COMPLETED
                    ? R.string.cancel_download : R.string.remove_download);
        }
        menu.setOnMenuItemClickListener(selected -> {
            if (selected.getItemId() == 2 && (state == null || state.state == Download.STATE_COMPLETED)) {
                new AlertDialog.Builder(this).setMessage(R.string.remove_downloads_question)
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.remove_download, (dialog, which) -> download(entry.mediaId, true)).show();
            } else {
                download(entry.mediaId, selected.getItemId() == 2);
            }
            return true;
        });
        menu.show();
    }

    private void download(String id, boolean remove) {
        if (downloads == null) { return; }
        if (!remove && !Settings.get(this).current().configured) { showLogin(true); return; }
        if (!remove && android.os.Build.VERSION.SDK_INT >= 33
                && androidx.core.content.ContextCompat.checkSelfPermission(this,
                android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[] {android.Manifest.permission.POST_NOTIFICATIONS}, 2);
        }
        ListenableFuture<Runnable> prepared = workers.submit(() -> {
            if (remove) {
                List<String> ids = downloads.removalIds(id);
                return () -> downloads.remove(ids);
            }
            List<androidx.media3.exoplayer.offline.DownloadRequest> requests = downloads.requests(id);
            return () -> downloads.add(requests);
        });
        Futures.addCallback(prepared, new FutureCallback<Runnable>() {
            @Override public void onSuccess(Runnable action) {
                if (!isDestroyed()) { action.run(); adapter.notifyDataSetChanged(); }
            }
            @Override public void onFailure(@NonNull Throwable error) {
                if (!isDestroyed()) { showStatus(error.getMessage()); }
            }
        }, ui);
    }

    private void loadImage(ImageView view, @Nullable Uri uri, boolean folder) {
        String key = uri == null ? "" : uri.toString();
        if (key.equals(view.getTag())) {
            return;
        }
        view.setTag(key);
        view.setImageResource(folder ? android.R.drawable.ic_menu_agenda : R.drawable.ic_azify);
        if (uri == null) {
            return;
        }
        Bitmap cached = images.get(key);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }
        ListenableFuture<Bitmap> future = pendingImages.get(key);
        if (future == null) {
            future = imageLoader.loadBitmap(uri);
            pendingImages.put(key, future);
        }
        Futures.addCallback(future, new FutureCallback<Bitmap>() {
            @Override public void onSuccess(Bitmap bitmap) {
                pendingImages.remove(key);
                if (!isDestroyed()) {
                    images.put(key, bitmap);
                    if (key.equals(view.getTag())) {
                        view.setImageBitmap(bitmap);
                    }
                }
            }
            @Override public void onFailure(@NonNull Throwable failure) {
                pendingImages.remove(key);
                if (key.equals(view.getTag())) {
                    view.setTag(null);
                }
            }
        }, ui);
    }

    private final class LibraryAdapter extends BaseAdapter {
        @Override public int getCount() { return rows.size(); }
        @Override public MediaItem getItem(int position) { return rows.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View recycled, ViewGroup container) {
            View row = recycled == null ? LayoutInflater.from(MainActivity.this)
                    .inflate(R.layout.library_row, container, false) : recycled;
            MediaItem entry = getItem(position);
            TextView title = row.findViewById(R.id.title);
            title.setText(entry.mediaMetadata.title);
                String state = downloadStatus(downloadUrl(entry));
                ((TextView) row.findViewById(R.id.subtitle)).setText(state == null
                    ? entry.mediaMetadata.subtitle : state + "  |  " + entry.mediaMetadata.subtitle);
            boolean current = browser != null && browser.getCurrentMediaItem() != null
                    && browser.getCurrentMediaItem().mediaId.equals(entry.mediaId.startsWith(Catalog.OFFLINE)
                    ? entry.mediaId.substring(Catalog.OFFLINE.length()) : entry.mediaId);
            title.setTextColor(current ? Color.rgb(29, 185, 84) : Color.rgb(245, 245, 245));
            ImageButton play = row.findViewById(R.id.row_play);
            play.setVisibility(Boolean.TRUE.equals(entry.mediaMetadata.isBrowsable)
                    && Boolean.TRUE.equals(entry.mediaMetadata.isPlayable) ? View.VISIBLE : View.GONE);
            play.setOnClickListener(view -> MainActivity.this.play(entry.mediaId));
                ImageButton download = row.findViewById(R.id.row_download);
                download.setImageResource(downloads != null && downloadUrl(entry) != null
                    && downloads.completed(downloadUrl(entry)) ? android.R.drawable.stat_sys_download_done
                    : android.R.drawable.stat_sys_download);
                download.setOnClickListener(view -> downloadMenu(entry, view));
            loadImage(row.findViewById(R.id.art), entry.mediaMetadata.artworkUri,
                    Boolean.TRUE.equals(entry.mediaMetadata.isBrowsable));
            return row;
        }
    }
}