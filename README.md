<p align="center">
  <img src="viby_logo.png" width="128" alt="Viby logo" />
</p>

# Viby

An AIMP-inspired offline music player for Android that downloads audio straight from YouTube — no server required, everything runs on the device.

## Features

- **On-device downloads** — paste a YouTube video or playlist link (or share it from the YouTube app) and Viby extracts the audio with a bundled yt-dlp + FFmpeg, converting to MP3 with embedded cover art and tags
- **Playlist support** — a playlist link downloads every track into its own folder with per-track progress
- **Self-updating yt-dlp** — the extractor updates itself daily (plus a manual update button), so YouTube changes don't require an app update
- **AIMP-style player** — dark/light theme with orange accent, waveform seek bar, shuffle/repeat, track queue with a mini player, background playback with media notification (Media3/ExoPlayer)
- **Equalizer** — system equalizer with the classic AIMP/Winamp preset collection
- **Library management** — playlists as folders, multi-select, context menu (play next, add to queue, move between playlists, delete)

Downloads are stored in `Android/data/com.example.viby/files/Music/<playlist>/` — accessible over USB, no storage permissions needed.

## Building

```bash
./gradlew :app:assembleDebug
```

Requires JDK 17+ and the Android SDK. The APK is large (~70 MB+) because it bundles a Python runtime for yt-dlp.

## Disclaimer

Viby is a personal-use project. Downloading content from YouTube may violate the YouTube Terms of Service — use it only for content you have the right to download, at your own responsibility.

## License

[MIT](LICENSE) © 2026 Vladimir Makarenko (vovaaa312)
