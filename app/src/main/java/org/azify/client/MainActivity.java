package org.azify.client;

import android.Manifest;
import android.app.SearchManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.format.Formatter;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.LinearInterpolator;
import android.view.animation.RotateAnimation;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RadioGroup;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;
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
import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@UnstableApi
public final class MainActivity extends AppCompatActivity {
    private static final String MASKED_PASSWORD = "********";
    private static final int REQ_BLUETOOTH = 3;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Executor ui = command -> main.post(command);
    private final ListeningExecutorService workers =
            MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(3));
    private final List<MediaItem> rows = new ArrayList<>();
    private final ArrayList<String> history = new ArrayList<>();
    private final Map<String, ListenableFuture<Bitmap>> pendingImages = new HashMap<>();
    private final Map<String, Integer> collectionStates = new HashMap<>();
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
    private EditText search;
    private PlayerControlView controls;
    private String parent = Catalog.ALBUMS;
    private boolean queueView;
    private int requestVersion;
    private AlertDialog loginDialog;
    private AlertDialog settingsDialog;
    private boolean loginOffered;
    private Boolean onlineState;
    private boolean waitForConnectivity;
    private Downloads downloads;
    private final Runnable searchLater = this::load;
    private final Runnable downloadChanged = () -> {
        if (browser != null) {
            if (parent.equals(Catalog.DOWNLOADS) || parent.startsWith(Catalog.OFFLINE)) { load(); }
            refreshCollectionStates();
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
    private final Runnable connectivityWatch = new Runnable() {
        @Override public void run() {
            if (downloads == null) {
                main.postDelayed(this, 3_000);
                return;
            }
            boolean online = downloads.online();
            if (onlineState == null || onlineState != online) {
                onlineState = online;
                if (browser != null) {
                    refresh(false);
                }
            }
            if (online && waitForConnectivity && browser != null) {
                waitForConnectivity = false;
                showStatus(null);
                browser.prepare();
                browser.play();
            }
            main.postDelayed(this, 3_000);
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
                if (Catalog.ALBUMS.equals(parent) && Boolean.TRUE.equals(selected.mediaMetadata.isPlayable)) {
                    play(selected.mediaId);
                } else {
                    history.add(parent);
                    parent = selected.mediaId;
                    search.setText("");
                    updateFolderBackVisibility();
                    load();
                }
            } else {
                if (isCollectionTrackView() && browser != null && browser.getShuffleModeEnabled()) {
                    playInVisibleOrder(selected.mediaId);
                } else {
                    play(selected.mediaId);
                }
            }
        });
        findViewById(R.id.settings).setOnClickListener(view -> showSettings());
        findViewById(R.id.sign_out).setOnClickListener(view -> signOut());
        updateSessionUi();
        findViewById(R.id.folder_back).setOnClickListener(view -> goBack());
        findViewById(R.id.refresh).setOnClickListener(view -> refresh(false));
        findViewById(R.id.bluetooth_audio).setOnClickListener(view -> showBluetoothAudioDialog());
        RadioGroup tabs = findViewById(R.id.tabs);
        tabs.setOnCheckedChangeListener((group, checked) -> {
            queueView = checked == R.id.tab_queue;
                parent = checked == R.id.tab_folders ? Catalog.FOLDERS
                    : checked == R.id.tab_downloads ? Catalog.DOWNLOADS : Catalog.ALBUMS;
            history.clear();
            search.setText("");
            search.setVisibility(queueView ? View.GONE : View.VISIBLE);
            updateFolderBackVisibility();
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
        updateFolderBackVisibility();
        applyQueueTabVisibility();
    }

    private void configureCompactLandscape() {
        android.content.res.Configuration configuration = getResources().getConfiguration();
        if (configuration.orientation != android.content.res.Configuration.ORIENTATION_LANDSCAPE
                || configuration.screenHeightDp >= 480) {
            return;
        }
        LinearLayout root = findViewById(R.id.root);
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

    private static final class BtChoice {
        final String name;
        final String address;
        final boolean connected;

        BtChoice(String name, String address, boolean connected) {
            this.name = name;
            this.address = address;
            this.connected = connected;
        }
    }

    private static final class BtRow {
        final String label;
        final @Nullable BtChoice device;

        BtRow(String label, @Nullable BtChoice device) {
            this.label = label;
            this.device = device;
        }
    }

    private boolean hasBluetoothConnectPermission() {
        return Build.VERSION.SDK_INT < 31 || ContextCompat.checkSelfPermission(this,
                Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasBluetoothScanPermission() {
        if (Build.VERSION.SDK_INT >= 31) {
            return ContextCompat.checkSelfPermission(this,
                    Manifest.permission.BLUETOOTH_SCAN) == android.content.pm.PackageManager.PERMISSION_GRANTED;
        }
        return ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private BluetoothAdapter bluetoothAdapter() {
        BluetoothManager manager = getSystemService(BluetoothManager.class);
        return manager == null ? null : manager.getAdapter();
    }

    private boolean isBluetoothOutputConnected() {
        AudioManager audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (audio == null) {
            return false;
        }
        for (AudioDeviceInfo device : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            int type = device.getType();
            if (type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                    || type == AudioDeviceInfo.TYPE_BLE_HEADSET
                    || type == AudioDeviceInfo.TYPE_BLE_SPEAKER
                    || type == AudioDeviceInfo.TYPE_BLE_BROADCAST) {
                return true;
            }
        }
        return false;
    }

    private boolean isAudioBluetoothClass(@Nullable BluetoothClass klass) {
        if (klass == null) {
            return false;
        }
        int major = klass.getMajorDeviceClass();
        if (major != BluetoothClass.Device.Major.AUDIO_VIDEO) {
            return false;
        }
        int deviceClass = klass.getDeviceClass();
        return deviceClass == BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES
                || deviceClass == BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET
                || deviceClass == BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER
                || deviceClass == BluetoothClass.Device.AUDIO_VIDEO_PORTABLE_AUDIO
                || deviceClass == BluetoothClass.Device.AUDIO_VIDEO_HIFI_AUDIO
                || deviceClass == BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO
                || deviceClass == BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE
                || deviceClass == BluetoothClass.Device.AUDIO_VIDEO_UNCATEGORIZED;
    }

    private List<String> connectedBluetoothOutputNames() {
        List<String> connectedNames = new ArrayList<>();
        AudioManager audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (audio == null) {
            return connectedNames;
        }
        for (AudioDeviceInfo output : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            int type = output.getType();
            if (type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                    || type == AudioDeviceInfo.TYPE_BLE_HEADSET
                    || type == AudioDeviceInfo.TYPE_BLE_SPEAKER
                    || type == AudioDeviceInfo.TYPE_BLE_BROADCAST) {
                CharSequence product = output.getProductName();
                if (product != null) {
                    connectedNames.add(product.toString());
                }
            }
        }
        return connectedNames;
    }

    private List<BtChoice> bluetoothSavedChoices() {
        List<BtChoice> choices = new ArrayList<>();
        BluetoothAdapter adapter = bluetoothAdapter();
        if (adapter == null || !hasBluetoothConnectPermission()) {
            return choices;
        }
        List<String> connectedNames = connectedBluetoothOutputNames();
        for (BluetoothDevice device : adapter.getBondedDevices()) {
            if (!isAudioBluetoothClass(device.getBluetoothClass())) {
                continue;
            }
            String name = device.getName() == null || device.getName().trim().isEmpty()
                    ? device.getAddress() : device.getName();
            boolean connected = connectedNames.contains(name);
            choices.add(new BtChoice(name, device.getAddress(), connected));
        }
        return choices;
    }

    private void openBluetoothSettings() {
        Intent panel = new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS);
        startActivity(panel);
    }

    private void requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            ActivityCompat.requestPermissions(this,
                    new String[] {Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN},
                    REQ_BLUETOOTH);
            return;
        }
        ActivityCompat.requestPermissions(this,
                new String[] {Manifest.permission.ACCESS_FINE_LOCATION}, REQ_BLUETOOTH);
    }

    private void handleBluetoothChoice(BtChoice selected) {
        if (selected.connected || isBluetoothOutputConnected()) {
            new AlertDialog.Builder(this)
                    .setMessage(R.string.bluetooth_disconnect_confirm)
                    .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.bluetooth_disconnect,
                    (confirmDialog, confirmWhich) -> requestBluetoothDisconnect())
                    .show();
            return;
        }
        new AlertDialog.Builder(this)
                .setMessage(getString(R.string.bluetooth_pairing_hint))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.bluetooth_open_settings,
                        (confirmDialog, confirmWhich) -> openBluetoothSettings())
                .show();
    }

    private void requestBluetoothDisconnect() {
        // Android does not expose a stable public API to force A2DP disconnects from third-party apps.
        showStatus(getString(R.string.bluetooth_disconnect_system_required));
        openBluetoothSettings();
    }

    private void showBluetoothDeviceChooser(BluetoothAdapter adapter) {
        List<BtChoice> saved = bluetoothSavedChoices();
        if (saved.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.bluetooth_audio_title)
                    .setMessage(getString(R.string.bluetooth_no_devices) + "\n\n"
                            + getString(R.string.bluetooth_use_android_search))
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.bluetooth_open_settings,
                            (dialog, which) -> openBluetoothSettings())
                    .show();
            return;
        }
        List<BtRow> rows = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        rows.add(new BtRow(getString(R.string.bluetooth_saved_section), null));
        labels.add(getString(R.string.bluetooth_saved_section));
        for (BtChoice choice : saved) {
            String label = choice.connected
                    ? choice.name + " " + getString(R.string.bluetooth_connected_suffix)
                    : choice.name;
            rows.add(new BtRow(label, choice));
            labels.add(label);
        }

        android.widget.ArrayAdapter<String> adapterRows = new android.widget.ArrayAdapter<>(
                this, android.R.layout.simple_list_item_1, labels);
        ListView list = new ListView(this);
        list.setAdapter(adapterRows);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        list.setPadding(pad, pad / 2, pad, 0);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.bluetooth_audio_title)
                .setView(list)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.bluetooth_open_settings,
                        (d, which) -> openBluetoothSettings())
                .create();

        list.setOnItemClickListener((parentView, clicked, position, id) -> {
            if (position < 0 || position >= rows.size()) {
                return;
            }
            BtRow row = rows.get(position);
            if (row.device == null) {
                return;
            }
            dialog.dismiss();
            handleBluetoothChoice(row.device);
        });
        dialog.show();
    }

    private void showBluetoothAudioDialog() {
        BluetoothAdapter adapter = bluetoothAdapter();
        if (adapter == null) {
            showStatus(getString(R.string.bluetooth_not_supported));
            return;
        }
        if (!hasBluetoothConnectPermission() || !hasBluetoothScanPermission()) {
            requestBluetoothPermissions();
            return;
        }
        if (!adapter.isEnabled()) {
            showStatus(getString(R.string.bluetooth_enable_first));
            return;
        }
        if (isBluetoothOutputConnected()) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.bluetooth_audio_title)
                    .setMessage(R.string.bluetooth_disconnect_confirm)
                    .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.bluetooth_disconnect,
                    (dialog, which) -> requestBluetoothDisconnect())
                    .show();
            return;
        }
        // Nearby discovery is delegated to the Android system Bluetooth UI for reliability.
        openBluetoothSettings();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
            @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_BLUETOOTH) {
            boolean granted = grantResults.length > 0;
            for (int value : grantResults) {
                granted &= value == android.content.pm.PackageManager.PERMISSION_GRANTED;
            }
            if (granted) {
                showBluetoothAudioDialog();
            } else {
                showStatus(getString(R.string.bluetooth_permission_needed));
            }
        }
    }

    private void applyQueueTabVisibility() {
        View queueTab = findViewById(R.id.tab_queue);
        if (queueTab == null) {
            return;
        }
        boolean enabled = Settings.get(this).current().queueEnabled;
        queueTab.setVisibility(enabled ? View.VISIBLE : View.GONE);
        RadioGroup tabs = findViewById(R.id.tabs);
        if (!enabled && tabs.getCheckedRadioButtonId() == R.id.tab_queue) {
            tabs.check(R.id.tab_albums);
            queueView = false;
            parent = Catalog.ALBUMS;
        }
        updateFolderBackVisibility();
    }

    private void updateFolderBackVisibility() {
        View back = findViewById(R.id.folder_back);
        if (back == null) {
            return;
        }
        RadioGroup tabs = findViewById(R.id.tabs);
        boolean inSongs = tabs != null && tabs.getCheckedRadioButtonId() == R.id.tab_folders;
        back.setVisibility(inSongs && !history.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private boolean isCollectionTrackView() {
        return parent.startsWith(Catalog.FOLDERS)
                || parent.startsWith(Catalog.ALBUMS)
                || parent.startsWith("album:")
                || parent.startsWith(Catalog.OFFLINE + Catalog.FOLDERS)
                || parent.startsWith(Catalog.OFFLINE + "album:");
    }

    private void playInVisibleOrder(String selectedId) {
        if (browser == null) {
            return;
        }
        ArrayList<MediaItem> queue = new ArrayList<>();
        int start = 0;
        for (MediaItem row : rows) {
            if (Boolean.TRUE.equals(row.mediaMetadata.isBrowsable)) {
                continue;
            }
            if (selectedId.equals(row.mediaId)) {
                start = queue.size();
            }
            queue.add(new MediaItem.Builder().setMediaId(row.mediaId).build());
        }
        if (queue.isEmpty()) {
            return;
        }
        browser.setMediaItems(queue, start, 0);
        browser.prepare();
        browser.play();
    }

    @Override protected void onStart() {
        super.onStart();
        downloads = Downloads.get(this);
        downloads.addListener(downloadChanged);
        main.post(downloadProgress);
        main.post(connectivityWatch);
        onlineState = downloads.online();
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
                        MediaItem current = browser.getCurrentMediaItem();
                        String url = current == null ? null : downloadUrl(current);
                        if (downloads != null && !downloads.online()
                                && (url == null || !downloads.completed(url))) {
                            waitForConnectivity = true;
                            showStatus(getString(R.string.playback_waiting_connection));
                        } else {
                            showStatus(getString(R.string.playback_error));
                        }
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
        main.removeCallbacks(connectivityWatch);
        waitForConnectivity = false;
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
        if (settingsDialog != null) {
            settingsDialog.dismiss();
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
            updateFolderBackVisibility();
            load();
        }
    }

    private void load() {
        if (browser == null) {
            return;
        }
        int version = ++requestVersion;
        String query = search.getText().toString().trim();
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
        boolean localOffline = parent.equals(Catalog.DOWNLOADS) || parent.startsWith(Catalog.OFFLINE);
        ListenableFuture<LibraryResult<com.google.common.collect.ImmutableList<MediaItem>>> result =
            query.isEmpty() || localOffline ? browser.getChildren(parent, 0, Integer.MAX_VALUE, null)
                        : browser.getSearchResult(query, 0, Integer.MAX_VALUE, null);
        Futures.addCallback(result, new FutureCallback<LibraryResult<com.google.common.collect.ImmutableList<MediaItem>>>() {
            @Override public void onSuccess(LibraryResult<com.google.common.collect.ImmutableList<MediaItem>> response) {
                if (version != requestVersion || isDestroyed()) {
                    return;
                }
                busy(false);
                rows.clear();
                collectionStates.clear();
                if (response.resultCode == LibraryResult.RESULT_SUCCESS && response.value != null) {
                    for (MediaItem entry : response.value) {
                        if (!localOffline || query.isEmpty()
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
                refreshCollectionStates();
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

    private void showSettings() {
        if (isFinishing() || (settingsDialog != null && settingsDialog.isShowing())) {
            return;
        }
        Settings.Snapshot account = Settings.get(this).current();
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        form.setPadding(padding, padding / 2, padding, 0);

        TextView cacheSection = new TextView(this);
        cacheSection.setText(R.string.cache_section);
        cacheSection.setTextColor(Color.rgb(230, 230, 230));
        cacheSection.setTextSize(16);
        form.addView(cacheSection, new LinearLayout.LayoutParams(-1, -2));

        TextView cacheValue = new TextView(this);
        int cacheGb = (int) (Settings.normalizeCacheBytes(account.cacheBytes) / (1024L * 1024L * 1024L));
        cacheValue.setText(getString(R.string.cache_size_value, cacheGb));
        cacheValue.setPadding(0, padding / 4, 0, 0);
        form.addView(cacheValue, new LinearLayout.LayoutParams(-1, -2));

        SeekBar cacheSlider = new SeekBar(this);
        cacheSlider.setId(R.id.cache_size_slider);
        cacheSlider.setMax(9);
        cacheSlider.setProgress(Math.max(0, Math.min(9, cacheGb - 1)));
        form.addView(cacheSlider, new LinearLayout.LayoutParams(-1, -2));

        TextView clearCache = new TextView(this);
        clearCache.setId(R.id.clear_cache_action);
        clearCache.setText(R.string.clear_cache);
        clearCache.setPadding(0, padding / 3, 0, 0);
        clearCache.setTextColor(Color.rgb(245, 190, 110));
        clearCache.setClickable(true);
        clearCache.setFocusable(true);
        form.addView(clearCache, new LinearLayout.LayoutParams(-1, -2));

        CheckBox mobileDownloads = new CheckBox(this);
        mobileDownloads.setText(R.string.allow_mobile_downloads);
        mobileDownloads.setChecked(account.allowMobileDownloads);
        mobileDownloads.setPadding(0, padding / 3, 0, 0);
        form.addView(mobileDownloads, new LinearLayout.LayoutParams(-1, -2));

        BandwidthUsage.Snapshot usage = BandwidthUsage.snapshot(this);
        TextView bandwidthSection = new TextView(this);
        bandwidthSection.setText(R.string.bandwidth_section);
        bandwidthSection.setPadding(0, padding, 0, 0);
        bandwidthSection.setTextColor(Color.rgb(230, 230, 230));
        bandwidthSection.setTextSize(16);
        form.addView(bandwidthSection, new LinearLayout.LayoutParams(-1, -2));

        TextView wifiUsage = new TextView(this);
        wifiUsage.setText(getString(R.string.bandwidth_wifi) + "\n"
            + getString(R.string.bandwidth_today, Formatter.formatFileSize(this, usage.wifiToday)) + "\n"
            + getString(R.string.bandwidth_week, Formatter.formatFileSize(this, usage.wifiWeek)));
        wifiUsage.setPadding(0, padding / 4, 0, 0);
        form.addView(wifiUsage, new LinearLayout.LayoutParams(-1, -2));

        TextView mobileUsage = new TextView(this);
        mobileUsage.setText(getString(R.string.bandwidth_mobile) + "\n"
            + getString(R.string.bandwidth_today, Formatter.formatFileSize(this, usage.mobileToday)) + "\n"
            + getString(R.string.bandwidth_week, Formatter.formatFileSize(this, usage.mobileWeek)));
        mobileUsage.setPadding(0, padding / 4, 0, 0);
        form.addView(mobileUsage, new LinearLayout.LayoutParams(-1, -2));

        TextView debugSection = new TextView(this);
        debugSection.setText(R.string.debug_section);
        debugSection.setPadding(0, padding, 0, 0);
        debugSection.setTextColor(Color.rgb(230, 230, 230));
        debugSection.setTextSize(16);
        form.addView(debugSection, new LinearLayout.LayoutParams(-1, -2));

        TextView debugDownloads = new TextView(this);
        debugDownloads.setId(R.id.debug_downloads_action);
        debugDownloads.setText(R.string.debug_show_downloaded_mp3);
        debugDownloads.setPadding(0, padding / 3, 0, 0);
        debugDownloads.setTextColor(Color.rgb(110, 185, 245));
        debugDownloads.setClickable(true);
        debugDownloads.setFocusable(true);
        form.addView(debugDownloads, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(form);

        settingsDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.settings)
                .setView(scroll)
            .setNegativeButton(R.string.back, null)
                .setPositiveButton(R.string.connect, (dialog, which) -> showLogin())
                .create();
        settingsDialog.setOnShowListener(ignored -> {
            settingsDialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            cacheSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar slider, int progress, boolean fromUser) {
                    int selectedGb = progress + 1;
                    cacheValue.setText(getString(R.string.cache_size_value, selectedGb));
                    if (fromUser) {
                        Settings.get(MainActivity.this).setCacheBytes(selectedGb * 1024L * 1024L * 1024L);
                    }
                }

                @Override public void onStartTrackingTouch(SeekBar slider) { }

                @Override public void onStopTrackingTouch(SeekBar slider) { }
            });
            clearCache.setOnClickListener(view -> workers.submit(() -> {
                main.post(() -> new AlertDialog.Builder(MainActivity.this)
                        .setMessage(R.string.clear_cache_confirm)
                        .setNegativeButton(android.R.string.no, null)
                        .setPositiveButton(android.R.string.yes, (dialog, which) -> workers.submit(() -> {
                            try {
                                Downloads.get(MainActivity.this).clearPlaybackCache();
                                main.post(() -> Toast.makeText(MainActivity.this,
                                        getString(R.string.cache_cleared), Toast.LENGTH_SHORT).show());
                            } catch (Exception failure) {
                                main.post(() -> showStatus(getString(R.string.cache_clear_failed)));
                            }
                        }))
                        .show());
            }));
            mobileDownloads.setOnCheckedChangeListener((button, checked) -> {
                Settings.get(MainActivity.this).setAllowMobileDownloads(checked);
                if (downloads != null) {
                    downloads.applyNetworkPolicy();
                }
            });
            debugDownloads.setOnClickListener(view -> showDownloadedMp3DebugList());
        });
        settingsDialog.show();
    }

    private void showDownloadedMp3DebugList() {
        workers.submit(() -> {
            try {
                List<String> names = Downloads.get(MainActivity.this).debugMergedMp3List();
                main.post(() -> {
                    if (isDestroyed()) {
                        return;
                    }
                    if (names.isEmpty()) {
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle(R.string.debug_downloaded_mp3_title)
                                .setMessage(R.string.debug_no_downloaded_mp3)
                                .setPositiveButton(R.string.cancel, null)
                                .show();
                        return;
                    }
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle(R.string.debug_downloaded_mp3_title)
                            .setItems(names.toArray(new String[0]), null)
                            .setPositiveButton(R.string.cancel, null)
                            .show();
                });
            } catch (Exception failure) {
                main.post(() -> showStatus(failure.getMessage() == null
                        ? getString(R.string.offline) : failure.getMessage()));
            }
        });
    }

    private void showLogin(boolean passwordRequired) {
        showConnectionDialog(passwordRequired, false);
    }

    private void showConnectionDialog(boolean passwordRequired, boolean includeCache) {
        AlertDialog existing = includeCache ? settingsDialog : loginDialog;
        if (isFinishing() || (existing != null && existing.isShowing())) {
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
        LinearLayout insecureFields = new LinearLayout(this);
        insecureFields.setOrientation(LinearLayout.VERTICAL);
        insecureFields.setVisibility(account.server.startsWith("http://") ? View.VISIBLE : View.GONE);
        CheckBox allowHttp = new CheckBox(this);
        allowHttp.setId(R.id.allow_insecure_http);
        allowHttp.setText(R.string.allow_insecure_http);
        allowHttp.setChecked(account.allowInsecureHttp);
        allowHttp.setSaveEnabled(false);
        insecureFields.addView(allowHttp, new LinearLayout.LayoutParams(-1, -2));
        TextView insecureWarning = new TextView(this);
        insecureWarning.setText(R.string.insecure_http_warning);
        insecureWarning.setTextColor(Color.rgb(245, 190, 110));
        insecureFields.addView(insecureWarning, new LinearLayout.LayoutParams(-1, -2));
        form.addView(insecureFields, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout passwordFields = new LinearLayout(this);
        passwordFields.setOrientation(LinearLayout.VERTICAL);
        passwordFields.setVisibility(passwordRequired || account.requiresPassword ? View.VISIBLE : View.GONE);
        EditText password = new EditText(this);
        password.setId(R.id.server_password);
        password.setHint(R.string.password);
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setSingleLine(true);
        if (!account.token.isEmpty()) {
            password.setText(MASKED_PASSWORD);
            password.setSelection(MASKED_PASSWORD.length());
        }
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
        TextView cacheValue = null;
        SeekBar cacheSlider = null;
        TextView clearCache = null;
        if (includeCache) {
            TextView cacheSection = new TextView(this);
            cacheSection.setText(R.string.cache_section);
            cacheSection.setPadding(0, padding / 2, 0, 0);
            cacheSection.setTextColor(Color.rgb(230, 230, 230));
            cacheSection.setTextSize(16);
            form.addView(cacheSection, new LinearLayout.LayoutParams(-1, -2));
            cacheValue = new TextView(this);
            int cacheGb = (int) (Settings.normalizeCacheBytes(account.cacheBytes) / (1024L * 1024L * 1024L));
            cacheValue.setText(getString(R.string.cache_size_value, cacheGb));
            cacheValue.setPadding(0, padding / 4, 0, 0);
            form.addView(cacheValue, new LinearLayout.LayoutParams(-1, -2));
            cacheSlider = new SeekBar(this);
            cacheSlider.setId(R.id.cache_size_slider);
            cacheSlider.setMax(9);
            cacheSlider.setProgress(Math.max(0, Math.min(9, cacheGb - 1)));
            form.addView(cacheSlider, new LinearLayout.LayoutParams(-1, -2));
            clearCache = new TextView(this);
            clearCache.setId(R.id.clear_cache_action);
            clearCache.setText(R.string.clear_cache);
            clearCache.setPadding(0, padding / 3, 0, 0);
            clearCache.setTextColor(Color.rgb(245, 190, 110));
            clearCache.setClickable(true);
            clearCache.setFocusable(true);
            form.addView(clearCache, new LinearLayout.LayoutParams(-1, -2));
        }
        TextView error = new TextView(this);
        error.setId(R.id.connection_error);
        error.setPadding(0, padding / 2, 0, padding / 2);
        error.setTextColor(Color.rgb(245, 160, 160));
        form.addView(error, new LinearLayout.LayoutParams(-1, -2));
        server.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                password.setText("");
                passwordFields.setVisibility(View.GONE);
                remember.setChecked(false);
                allowHttp.setChecked(false);
                insecureFields.setVisibility(value.toString().trim().toLowerCase(java.util.Locale.ROOT)
                    .startsWith("http://") ? View.VISIBLE : View.GONE);
                error.setText(null);
            }
            @Override public void afterTextChanged(Editable value) { }
        });
        ScrollView scroll = new ScrollView(this);
        scroll.addView(form);
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
            .setTitle(includeCache ? R.string.settings : R.string.connection_title).setView(scroll)
                .setPositiveButton(R.string.connect, null).setNegativeButton(R.string.cancel, null);
        if (canSignOut) {
            builder.setNeutralButton(R.string.logout, (dialog, which) -> signOut());
        }
        AlertDialog dialog = builder.create();
        if (includeCache) {
            settingsDialog = dialog;
        } else {
            loginDialog = dialog;
        }
        final TextView finalCacheValue = cacheValue;
        final SeekBar finalCacheSlider = cacheSlider;
        final TextView finalClearCache = clearCache;
        dialog.setOnShowListener(ignored -> {
            dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            if (includeCache && finalCacheSlider != null && finalCacheValue != null && finalClearCache != null) {
                finalCacheSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override public void onProgressChanged(SeekBar slider, int progress, boolean fromUser) {
                        int selectedGb = progress + 1;
                        finalCacheValue.setText(getString(R.string.cache_size_value, selectedGb));
                        if (fromUser) {
                            Settings.get(MainActivity.this).setCacheBytes(selectedGb * 1024L * 1024L * 1024L);
                        }
                    }

                    @Override public void onStartTrackingTouch(SeekBar slider) { }

                    @Override public void onStopTrackingTouch(SeekBar slider) { }
                });
                finalClearCache.setOnClickListener(view -> workers.submit(() -> {
                    try {
                        Downloads.get(MainActivity.this).clearPlaybackCache();
                        main.post(() -> Toast.makeText(MainActivity.this,
                                getString(R.string.cache_cleared), Toast.LENGTH_SHORT).show());
                    } catch (Exception failure) {
                        main.post(() -> showStatus(getString(R.string.cache_clear_failed)));
                    }
                }));
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
                final List<String> candidates;
                final boolean implicitScheme;
                try {
                    implicitScheme = !AzifyClient.hasExplicitScheme(server.getText().toString());
                    candidates = AzifyClient.connectionCandidates(server.getText().toString());
                } catch (IllegalArgumentException invalid) {
                    error.setText(invalid.getMessage());
                    return;
                }
                final String primary = candidates.get(0);
                if (primary.startsWith("http://") && !allowHttp.isChecked()) {
                    error.setText(R.string.insecure_http_not_allowed);
                    return;
                }
                String entered = password.getText().toString();
                String token = ((entered.isEmpty() || MASKED_PASSWORD.equals(entered))
                    && primary.equals(account.server)) ? account.token
                    : entered.isEmpty() ? "" : AzifyClient.passwordHash(entered);
                boolean keepPassword = remember.isChecked();
                boolean allowInsecure = allowHttp.isChecked();
                password.setText("");
                AlertDialog active = dialog;
                active.setCancelable(false);
                server.setEnabled(false);
                password.setEnabled(false);
                remember.setEnabled(false);
                allowHttp.setEnabled(false);
                active.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                active.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
                if (canSignOut) {
                    active.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(false);
                }
                error.setText(R.string.connecting);
                ListenableFuture<AzifyClient.Connection> validation = workers.submit(() -> {
                    AzifyClient client = new AzifyClient();
                    IOException lastError = null;
                    String selected = primary;
                    AzifyClient.Connection connection = null;
                    for (int i = 0; i < candidates.size(); i++) {
                        String candidate = candidates.get(i);
                        try {
                            connection = client.connect(candidate, token);
                            selected = candidate;
                            break;
                        } catch (AzifyClient.AuthenticationException auth) {
                            throw auth;
                        } catch (IOException failure) {
                            lastError = failure;
                            if (!implicitScheme || i == candidates.size() - 1) {
                                break;
                            }
                        }
                    }
                    if (connection == null) {
                        if (implicitScheme) {
                            throw new IOException(getString(R.string.server_not_found), lastError);
                        }
                        throw lastError == null ? new IOException(getString(R.string.offline)) : lastError;
                    }
                    boolean resolvedAllowInsecure = selected.startsWith("http://")
                            ? (implicitScheme || allowInsecure) : false;
                    Settings.get(MainActivity.this).save(selected, connection.token,
                            connection.requiresPassword, keepPassword, resolvedAllowInsecure);
                    Downloads.get(MainActivity.this).saveCatalog(selected, connection.catalog);
                    return connection;
                });
                Futures.addCallback(validation, new FutureCallback<AzifyClient.Connection>() {
                    @Override public void onSuccess(AzifyClient.Connection connection) {
                        if (isDestroyed()) {
                            return;
                        }
                        active.dismiss();
                        updateSessionUi();
                        Settings.Snapshot current = Settings.get(MainActivity.this).current();
                        refresh(!account.server.equals(current.server) || !account.token.equals(connection.token));
                    }
                    @Override public void onFailure(@NonNull Throwable failure) {
                        if (isDestroyed()) {
                            return;
                        }
                        active.setCancelable(true);
                        server.setEnabled(true);
                        password.setEnabled(true);
                        remember.setEnabled(true);
                        allowHttp.setEnabled(true);
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
        dialog.show();
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
        if (settingsDialog != null) { settingsDialog.dismiss(); }
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

    private void onCollectionDownloadPressed(MediaItem entry, int collectionState) {
        if (collectionState == Downloads.COLLECTION_ACTIVE) {
            new AlertDialog.Builder(this)
                    .setMessage(R.string.cancel_download_collection_question)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.cancel_download,
                            (dialog, which) -> download(entry.mediaId, true))
                    .show();
            return;
        }
        if (collectionState == Downloads.COLLECTION_DONE) {
            new AlertDialog.Builder(this)
                    .setMessage(R.string.remove_download_collection_question)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.remove_download,
                            (dialog, which) -> download(entry.mediaId, true))
                    .show();
            return;
        }
        download(entry.mediaId, false);
    }

    private void download(String id, boolean remove) {
        if (downloads == null) { return; }
        boolean removing = remove;
        if (!remove && !Settings.get(this).current().configured) { showLogin(true); return; }
        if (!remove && !Settings.get(this).current().allowMobileDownloads && downloads.onMobileData()) {
            new AlertDialog.Builder(this)
                    .setMessage(R.string.mobile_download_confirm)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.download, (dialog, which) -> {
                        Settings.get(MainActivity.this).setAllowMobileDownloads(true);
                        downloads.applyNetworkPolicy();
                        download(id, false);
                    })
                    .show();
            return;
        }
        if (!remove && android.os.Build.VERSION.SDK_INT >= 33
                && androidx.core.content.ContextCompat.checkSelfPermission(this,
                android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[] {android.Manifest.permission.POST_NOTIFICATIONS}, 2);
        }
        ListenableFuture<Runnable> prepared = workers.submit(() -> {
            if (remove) {
                List<String> ids = downloads.removalIds(id);
                return () -> {
                    if (Downloads.isSyncableCollectionId(id)) {
                        downloads.setCollectionAutoSync(id, false);
                    }
                    downloads.remove(ids);
                };
            }
            List<androidx.media3.exoplayer.offline.DownloadRequest> requests = downloads.requests(id);
            return () -> {
                downloads.watchCollection(id);
                if (Downloads.isSyncableCollectionId(id)) {
                    downloads.setCollectionAutoSync(id, true);
                }
                downloads.add(requests);
            };
        });
        Futures.addCallback(prepared, new FutureCallback<Runnable>() {
            @Override public void onSuccess(Runnable action) {
                if (!isDestroyed()) {
                    action.run();
                    adapter.notifyDataSetChanged();
                    refreshCollectionStates();
                    if (removing && (parent.equals(Catalog.DOWNLOADS) || parent.startsWith(Catalog.OFFLINE))) {
                        main.postDelayed(MainActivity.this::load, 300);
                        main.postDelayed(MainActivity.this::load, 1200);
                    }
                }
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

    private void refreshCollectionStates() {
        if (downloads == null || rows.isEmpty()) {
            collectionStates.clear();
            return;
        }
        List<MediaItem> snapshot = new ArrayList<>(rows);
        workers.submit(() -> {
            Map<String, Integer> updated = new HashMap<>();
            for (MediaItem item : snapshot) {
                if (!Boolean.TRUE.equals(item.mediaMetadata.isBrowsable)) {
                    continue;
                }
                updated.put(item.mediaId, downloads.collectionState(item.mediaId));
            }
            main.post(() -> {
                if (isDestroyed()) {
                    return;
                }
                collectionStates.clear();
                collectionStates.putAll(updated);
                adapter.notifyDataSetChanged();
            });
        });
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
                ImageButton download = row.findViewById(R.id.row_download);
                boolean downloadVisible = !queueView && !parent.equals(Catalog.DOWNLOADS)
                    && !parent.startsWith(Catalog.OFFLINE)
                    && Boolean.TRUE.equals(entry.mediaMetadata.isBrowsable);
                download.setVisibility(downloadVisible ? View.VISIBLE : View.GONE);
                if (downloadVisible) {
                    int collectionState = collectionStates.getOrDefault(entry.mediaId, Downloads.COLLECTION_NONE);
                    updateDownloadIcon(download, collectionState);
                    if (collectionState == Downloads.COLLECTION_ACTIVE) {
                        download.setOnClickListener(null);
                        download.setOnTouchListener((view, event) -> {
                            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                                onCollectionDownloadPressed(entry, Downloads.COLLECTION_ACTIVE);
                                return true;
                            }
                            return false;
                        });
                    } else {
                        download.setOnTouchListener(null);
                        download.setOnClickListener(view -> onCollectionDownloadPressed(entry, collectionState));
                    }
                } else {
                    download.clearAnimation();
                    download.setImageTintList(ColorStateList.valueOf(Color.rgb(189, 189, 189)));
                    download.setImageResource(android.R.drawable.stat_sys_download);
                    download.setOnTouchListener(null);
                    download.setOnClickListener(null);
                }
            loadImage(row.findViewById(R.id.art), entry.mediaMetadata.artworkUri,
                    Boolean.TRUE.equals(entry.mediaMetadata.isBrowsable));
            return row;
        }

        private void updateDownloadIcon(ImageButton button, int collectionState) {
            if (collectionState == Downloads.COLLECTION_DONE) {
                if (button.getAnimation() != null) {
                    button.clearAnimation();
                }
                button.setImageResource(R.drawable.ic_download_done_circle);
                button.setImageTintList(null);
                return;
            }
            button.setImageResource(android.R.drawable.stat_sys_download);
            if (collectionState == Downloads.COLLECTION_ACTIVE) {
                button.setImageResource(R.drawable.ic_download_loading_small);
                button.setImageTintList(null);
                if (button.getAnimation() == null) {
                    RotateAnimation rotate = new RotateAnimation(0, 360,
                            RotateAnimation.RELATIVE_TO_SELF, 0.5f,
                            RotateAnimation.RELATIVE_TO_SELF, 0.5f);
                    rotate.setInterpolator(new LinearInterpolator());
                    rotate.setDuration(2700);
                    rotate.setRepeatCount(RotateAnimation.INFINITE);
                    button.startAnimation(rotate);
                }
                return;
            }
            if (button.getAnimation() != null) {
                button.clearAnimation();
            }
            button.setImageTintList(ColorStateList.valueOf(Color.rgb(189, 189, 189)));
        }
    }
}