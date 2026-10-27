# AZify Native Client for Android

A generic native Android client for [AZify Server/Player](https://github.com/azraelpc/azify),
with Android Auto media browsing support, and persistent offline downloads.
Requires Android 8.0 or later. The interface and documentation are in English.
No server, account, password or personal library is included in the APK.

<img height="600" alt="image" src="https://github.com/user-attachments/assets/c8950aaf-1b76-45cf-b023-f8437379c6ce" />
 <img height="600" alt="image" src="https://github.com/user-attachments/assets/d5f0c81e-d64d-461e-9efc-627f5690033f" />

## Connect

Open AZify, enter your **AZify Server URL**, and select **Connect**. The URL is
empty on a fresh installation. HTTP and HTTPS URLs, custom ports and subpaths are
supported, for example:

- `https://example.com/`
- `https://example.com:8443/azify/`
- `http://192.0.2.10:5155/`
- `http://azify.michaelknight.de/`
- `http://example.com:5155/apps/music/`

A URL without scheme is also accepted. The app first tries `https://` and then
`http://` if needed.

A missing trailing slash is added automatically. Embedded credentials, query
parameters and fragments are rejected. HTTPS is recommended. With an HTTP URL,
the unchecked **Allow insecure HTTP** option and a warning appear. You must
explicitly enable it before any connection is attempted: HTTP exposes your
password credential and music to interception. The choice is remembered only
with that saved URL, resets when editing the URL, and is cleared on Sign out.

The configured URL is the application base, not the API endpoint. For
`https://example.com/azify/`, the client requests `/azify/api/music`, maps catalog
paths such as `/music/Album/track.mp3` to `/azify/music/Album/track.mp3`, and loads
fallback artwork from `/azify/nocover.jpg`. A reverse proxy must route those paths
to the same Azify instance, usually stripping the `/azify/` prefix. The stock
server does not need source changes for that arrangement. Mounting the original
website under a subpath may additionally require adapting its absolute web paths;
the Android client handles its own base-path resolution.

The app first requests `api/music` relative to the base URL without credentials. Public servers open
directly; only HTTP 401 reveals the password field. Network errors, HTTP 403
and invalid catalogs are not treated as password prompts.

**Remember Password** is unchecked by default. When selected, the compatible
SHA-256 credential is encrypted using Android Keystore and AES-GCM. Otherwise,
it remains in process memory and must be entered again for online access after
Android ends the process. Downloaded tracks remain available locally until
you explicitly sign out.

**Sign out** appears only after password-protected access. It stops playback,
pauses downloads, clears the password and offline authorization, then opens the
login dialog. The last URL is remembered and the password must be entered again.
Downloaded files remain on the device but cannot be played until signing in
again to the same server. Public servers do not show Sign out.

## Settings

Use **Settings** from the top bar to adjust local behavior:

- **Cache**: playback cache size (1-10 GB) and **Clear cache** action.
   Lowering the limit trims cached playback items immediately, removing the
   least-recently-used entries first (it does not wipe everything).
- **Downloads over mobile data**: disabled by default (Wi-Fi only). You can
  enable it in Settings. If disabled and you start a download on mobile data,
  the app asks for confirmation before enabling mobile downloads.
- **Bandwidth usage**: downloaded bytes split by **Wi-Fi** and **Mobile data**
   for **Today** and **Last 7 days**.
- **Debug**: **Show downloaded MP3 list**, including merged source labels
   (USER, CACHE, USER+CACHE).
- Settings closes with **Back** (instead of Cancel) for clearer navigation.

The Connect dialog is separate from Settings and is opened with **Connect** from
the Settings dialog.

## Browse And Play

- Albums, nested folders, tracks, search, and a shared playback queue.
- Play/pause, previous/next, seeking, ten-second skips, shuffle and repeat.
- With shuffle enabled, playback is randomized inside the current collection
   (album/folder) and loops within that collection.
- If shuffle is enabled while a song is already playing, the queue is
   re-scoped to the current song folder before applying random order.
- With shuffle disabled, playback continues in normal order and, after the last
   track of the current album/folder, moves into the first track of the next
   album/folder.
- In **Songs** tab, when you are inside nested folders, a header button
   (**Back to Folder List**) appears to move one level up in the folder history.
- Background playback, audio focus and pause when headphones are disconnected.
- Phone, notification, headset and Android Auto controls use one Media3 session.
- The last track and playback position are saved for playback resumption.
- Header includes a **Bluetooth audio** button next to Refresh.
- The Bluetooth button uses Android's native Bluetooth search flow for nearby
   pairing devices (same behavior as system Settings).
- The app view shows/manage **Saved Bluetooth devices** (paired devices).
- If audio is already connected, tapping the Bluetooth icon first offers a
   **Disconnect / Cancel** confirmation flow.
- Choosing **Disconnect** opens system Bluetooth settings to complete the
   disconnection (required by Android platform restrictions).
- Device connect/disconnect actions are finalized in system Bluetooth settings.
- If pairing asks for a PIN/passkey, common defaults are suggested
   (`0000`, `1234`, `8888`, `1111`, `9999`, `000000`, `123456`).

Android does not allow third-party apps to force-connect/disconnect A2DP audio
devices or auto-submit pairing PINs through stable public APIs, so those actions
are completed through the system Bluetooth UI.

This is a native client, not a WebView. The web portal animation, visualizer and
Web Audio compressor are not reproduced. Audio format support depends on the
Android/Media3 decoders and the media served by Azify.

## Downloads

Use the download icon beside an album, folder or track to download it. Progress,
queued, paused, failed and downloaded states appear in the track list. Tapping
the icon lets you start, cancel or remove downloads directly depending on the
current state. Allow notifications to see background download progress and
completion alerts.

The foreground download notification now includes the active collection name
(album/folder) and progress count, for example:
`Downloading: Queen - Hits (13/17)`.

By default, downloads run on **Wi-Fi only**. If mobile downloads are disabled
and the device is on mobile data, starting a download prompts for explicit
confirmation for that download only. It does not automatically enable the
global **Allow mobile data for downloads** setting.

**Downloads** lists requested tracks, including those still in progress. Completed
tracks are stored persistently in the app's private files, not temporary OS cache.
Playback always reads local cached bytes first, even when online. The player uses
a dedicated playback cache (LRU) plus persistent manual downloads. Without a
connection, the saved catalog exposes completed tracks and their folders. Playing
from Downloads uses a queue containing completed downloads only.

Partial downloads are resumed by Media3. Pending downloads can be resumed by
selecting Download again; if an unsaved password was lost when Android killed
the process, sign in before retrying. Android battery restrictions and foreground
service time limits can interrupt long-running downloads.

When you download an album/folder, the app keeps it synchronized with the server
catalog: new tracks added by the admin are queued automatically, and tracks that
disappear from the server are removed from managed offline downloads for that
collection. This sync follows your current download network policy (Wi-Fi only
or Wi-Fi + mobile data).

When playback loses connectivity while streaming, the app shows a waiting state
and automatically resumes once the connection returns.

Storage is not automatically evicted. Remove downloads to reclaim space.
Uninstalling or clearing app data deletes them. Downloads and catalogs are kept
separate by server base URL (including scheme, port and subpath) and are never
copied into a build artifact.

## Android Auto

The MediaLibraryService exposes Albums, Folders, Play all and Downloads. It
supports media selection, search, queue, play/pause, previous/next and seek through
MediaSession. The car decides which controls are visible and may restrict browsing
or seeking while driving. The website itself is not displayed in the car.

For a development APK installed outside Google Play:

1. Open AZify and connect on the phone before connecting to the car.
2. Open Android Auto settings and tap the version repeatedly, usually ten times,
   to enable its developer settings.
3. Enable **Unknown sources** in the **Android Auto developer menu**. This is not
   Android's general permission to install APKs.
4. Reconnect and look for AZify in the media selector or Customize launcher.

Sideloaded-app visibility depends on Android Auto and the device manufacturer.
An official alternative is distribution through a Google Play internal testing
track. A debug APK is not Google Play approval or a guarantee of support by every
head unit. Publishing requires your own signing key and compliance with car-app
quality requirements. Protocol tests do not replace testing in a car or Desktop
Head Unit.

## Build

On Windows without a preinstalled JDK or Android SDK:

```powershell
.\scripts\build.ps1 -AcceptSdkLicenses
```

Review <https://developer.android.com/studio/terms> before accepting. Tools are
downloaded into `.tools/` without a global installation. Subsequent builds use:

```powershell
.\scripts\build.ps1
```

This runs local tests, Android Lint and assembleDebug. With JDK 17 and SDK 35
already configured, the standard Gradle wrapper or Android Studio also works:

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

After a successful build that includes **assembleDebug**, the APK is copied to
**`APK/Azify-DDMMYY.apk`**, for example `APK/Azify-261026.apk`. Successful builds
on the same day replace that day's file; builds from previous dates remain.
Failed builds never replace the published copy. `APK/` is deliberately not
gitignored, so these files can be committed to GitHub. No automatic git commit
or push is performed. The original output remains at
`app/build/outputs/apk/debug/app-debug.apk`.

The Gradle wrapper checks the official Gradle 8.11.1 SHA-256. Open this folder in
Android Studio as a Gradle project; VS Code's generic Java analyzer might not
automatically import the Android classpath. Gradle is the build reference.
Release distribution requires a private signing key, not the debug key.

## Device Tests

Enable USB debugging, authorize the computer and keep the phone unlocked:

```powershell
.\scripts\test-device.ps1 -InstallPackages
```

Approve both APK installations. Open AZify, configure your server and connect.
Then run the non-destructive instrumentation script:

```powershell
.\scripts\test-device.ps1
```

The live tests cover library/folder browsing, search, authenticated artwork,
streaming, pause, seek, next/previous, portrait/landscape controls and the Media3
and legacy MediaBrowserCompat protocols. They require a nonempty playable library,
mute AZify during playback checks and leave it paused. Use `-Serial ID` to select
a device. Local tests also verify cache-first playback without network access,
credential privacy, catalog filtering and Sign out.

Avoid `connectedDebugAndroidTest` against an installation containing personal
data: Gradle cleanup can uninstall the app after a failed test session. The
provided script does not uninstall packages.

## Security And Sharing

The generic package is `org.azify.client`. It does not inherit data from older
personalized app identifiers. HTTPS is recommended; HTTP requires explicit
consent and offers no transport encryption. Matching the configured scheme,
host, port and media path, together with disabled redirects, prevents credentials
being forwarded to a different application base or server. Credentials are sent only
through `X-Azify-Pass`, never in URLs. Trusted media controllers receive artwork
through a read-only ContentProvider. Only AZify can change connection settings.

Credentials, downloads and library snapshots are private runtime data and are
excluded from backups and device transfers. They are not part of source or APKs.
Do not commit `.tools/`, `.gradle/`, `build/`, `local.properties`, personal signing
keys, phone screenshots or test captures. These paths are local tooling or data;
the generated distributable in `APK/` is safe to share after review.

The upstream GitHub link identifies the compatible server project. It is not a
preconfigured server or bundled account.

## Technical Notes

### Stack and Architecture

- Platform: native Android app (Java), package `org.azify.client`.
- Minimum Android: 8.0 (API 26). Target/compile SDK: 35.
- Media stack: AndroidX Media3 (`MediaLibraryService`, `MediaSession`, ExoPlayer,
   DownloadManager/DownloadService).
- Networking: OkHttp (`okhttp`) for catalog/media access and request control.
- UI: XML layouts + AppCompat widgets (no WebView).
- Offline model: persistent manual downloads + separate playback LRU cache.

### Build Toolchain

- Android Gradle Plugin: 8.9.1.
- Gradle wrapper: 8.11.1 (SHA-256 verified by wrapper config).
- Java language level: 17 (source and target compatibility).
- Primary module: `:app` (application project, no Kotlin source in this repo).

### Main Libraries

- `androidx.appcompat:appcompat:1.7.0`
- `androidx.media3:media3-exoplayer:1.6.1`
- `androidx.media3:media3-session:1.6.1`
- `androidx.media3:media3-ui:1.6.1`
- `androidx.media3:media3-datasource-okhttp:1.6.1`
- `androidx.media3:media3-database:1.6.1`
- `com.squareup.okhttp3:okhttp:4.12.0`

### Test and Validation Tooling

- Local unit tests: JUnit 4 + Robolectric + MockWebServer.
- Android instrumentation tests: AndroidX Test Runner/Core/JUnit extensions.
- Static checks in normal pipeline: Android Lint.
- Typical Gradle task set: `testDebugUnitTest lintDebug assembleDebug`.

### Build and Packaging Flow

- Regular local build entrypoint: `scripts/build.ps1`.
- Scripted environment bootstrap (Windows):
   - installs/uses JDK 17 (Adoptium) in `.tools/`
   - installs Android command-line tools + SDK platform 35 + build-tools 35.0.0
   - runs Gradle tasks without requiring global Android Studio setup
- On successful `assembleDebug`, the app archives a date-stamped APK copy to
   `APK/Azify-DDMMYY.apk` while keeping Gradle output at
   `app/build/outputs/apk/debug/app-debug.apk`.

### Repository and Runtime Notes

- Dependency repositories: `google()` and `mavenCentral()`.
- Repositories mode: fail on project-defined repositories
   (`RepositoriesMode.FAIL_ON_PROJECT_REPOS`).
- English-only app resources are enforced with `resourceConfigurations += ['en']`.
