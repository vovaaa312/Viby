# Viby

Viby is an experimental, AIMP-inspired music player for Android that turns YouTube videos and playlists into an on-device music library. Downloading, metadata extraction, queue management, playback, and app updates run on the phone; Viby does not require its own backend.

> **Project status:** personal-use beta. The main workflows are usable, but the project still needs broader automated testing, lint/API cleanup, production signing, and architectural refactoring before it should be treated as a production-ready player.

## What Viby can do

### YouTube downloads

- Accept a YouTube video or playlist URL entered in Viby or shared from the YouTube app.
- Run bundled `yt-dlp` and FFmpeg entirely on the device.
- Produce MP3 files with titles, artists, thumbnails, and other available metadata.
- Sign in to YouTube through a WebView and export cookies for content that requires an account.
- Update the bundled extractor daily or on demand without waiting for a Viby release.
- Refresh a previously downloaded YouTube playlist and restore the source order with the **Original YouTube order** sorting option.
- Detect duplicate tracks and ask whether to reuse the existing local file instead of silently downloading it again.

### Download queue

- Download multiple playlists as independent persistent jobs.
- Pause or cancel one job without affecting the other playlists.
- Drag playlist jobs to change their execution order.
- Open an individual job and reorder or remove its pending tracks.
- Show per-track download progress as a circular overlay on darkened cover art.
- Add placeholder tracks to the destination playlist as soon as playlist metadata is available.
- When an unfinished track is selected, resolve an online stream for immediate playback and move its full download to the front of the queue.

The current **smart download** implementation uses metadata placeholders, on-demand online playback, and full-download prioritization. It does **not** yet prefetch the first few seconds of every track in parallel.

### Playback and queues

- Background playback powered by AndroidX Media3/ExoPlayer and a media notification.
- Persistent last track, playback position, repeat state, playback queue, and exact shuffled order.
- Editable playback queue with drag-and-drop reordering and track removal.
- Queue reset that also disables shuffle.
- The currently playing track is brought into view at the top when the queue panel opens.
- Shuffle, repeat, previous/next, seeking, full player, and persistent mini player.

### Library and interface

- Playlist-based local library backed by Room.
- Create and delete playlists, search the active playlist, sort tracks, and move tracks between playlists.
- Multi-select and contextual track actions.
- Monochrome light and dark themes.
- Dedicated portrait, landscape, phone, and tablet layouts.
- App-scoped storage, so normal storage permissions are not required.

### Equalizer

- 20 adjustable bands from 31 Hz to 22 kHz.
- Preamp control and built-in presets.
- A graph editor that locks the selected band for the duration of a drag gesture.
- Modern `DynamicsProcessing` implementation where supported, with interpolation to the device equalizer bands on older devices.

### App updates

- Automatic daily and manual checks of the latest GitHub Release.
- Semantic-version comparison.
- APK download through Android's system `DownloadManager` with visible progress and retry handling.
- SHA-256 verification when the GitHub release asset provides a digest.
- Installation through the system Android package installer after explicit user approval.

## Storage

Downloaded music is stored in:

```text
Android/data/com.example.viby/files/Music/<playlist>/
```

This avoids broad storage permissions and is accessible over USB on supported devices. It also means that Android normally removes the downloaded library when Viby is uninstalled. A user-selected shared library location and backup/restore workflow are not implemented yet.

## Current limitations

- Only `arm64-v8a` phones/tablets and `x86_64` emulators are included in the APK.
- The APK is large (currently roughly 119 MB) because it bundles Python, `yt-dlp`, and FFmpeg.
- Viby does not yet provide a global artist/album/genre library, tag editor, lyrics, ReplayGain, gapless playback, crossfade, sleep timer, Android Auto browser, widgets, Chromecast, or selectable download quality/format.
- Download throughput for large playlists still needs profiling and optimization. Jobs currently prioritize correctness and deterministic state over maximum parallelism.
- The release process still needs a permanent production signing key and CI-generated release builds.
- `lintDebug` currently reports known API, Media3 opt-in, localization, and accessibility issues. `assembleDebug` and the existing unit tests pass.
- YouTube login cookies are sensitive data. Backup exclusions and further WebView/cookie hardening are planned.

## Roadmap

### Reliability and test coverage

- Add unit, integration, Room migration, service lifecycle, process-death, and UI tests.
- Cover playback persistence, shuffle order, download pause/cancel/reorder, placeholders, duplicate handling, retries, and app updating.
- Resolve all current Android Lint errors and review the remaining warnings.
- Fix minimum-API compatibility and update AndroidX/Media3 dependencies deliberately, with regression testing.
- Harden cookies, backup rules, WebView navigation, APK digest verification, and release signing.

### Architecture and performance

- Split the monolithic `DownloadService` into a persistent state repository, queue scheduler, job runner, `yt-dlp` layer, file importer, and notification/UI bridge.
- Reduce the responsibilities of `MainActivity` and move business logic into lifecycle-aware components.
- Persist per-track state and progress robustly across process death.
- Profile large playlists and optimize metadata extraction, audio conversion, database writes, artwork processing, and controlled concurrency.
- Investigate resumable downloads and real short-prefix prefetching without overwhelming the network, CPU, battery, or YouTube endpoints.

### Player and library completeness

- Add a global track/artist/album library and user-selected storage through MediaStore or the Storage Access Framework.
- Add explicit retry/cleanup tools for failed placeholder tracks.
- Consider ReplayGain, gapless playback, crossfade, sleep timer, tag editing, lyrics, playlist import/export, Android Auto, widgets, and quality/format selection.

## Building

Requirements:

- JDK 17 or newer
- Android SDK matching the project's compile SDK
- Android Studio's bundled JDK is recommended on Windows

Windows:

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```

macOS/Linux:

```bash
./gradlew testDebugUnitTest assembleDebug
```

The debug APK is generated under `app/build/outputs/apk/debug/`.

## Disclaimer

Viby is a personal-use project. Downloading or copying content from YouTube may violate YouTube's Terms of Service, copyright law, or the rights of a content owner. Use Viby only with content you are authorized to download. The project is not affiliated with YouTube, Google, AIMP, or Poweramp.
