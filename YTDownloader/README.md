# YT Downloader (Android)

Android app that downloads YouTube videos or whole playlists in the quality you pick.
It uses [yt-dlp](https://github.com/yt-dlp/yt-dlp) and FFmpeg on the phone itself, through
[youtubedl-android](https://github.com/JunkFood02/youtubedl-android), so no server is involved.

## Features
- Paste a **video link** or a **playlist link** (or use **Share → YT Downloader** in the YouTube app)
- **Pick the quality**: Best, 2160p … 144p (shows only the resolutions the video actually has, with estimated size), or **audio only** as MP3/M4A
- Playlists: tick or untick individual videos, Select all / none
- Background downloads with notification progress, cancel and retry
- Files are saved to `Download/YTDownloader/` (playlists go in a sub-folder named after the playlist)
- yt-dlp updates itself once a day (or use ⋮ → *Update yt-dlp*) to keep up with YouTube changes

## Which APK to install
| APK | For |
|---|---|
| `app-arm64-v8a-release.apk` (~60 MB) | Almost all phones from 2017 onward |
| `app-armeabi-v7a-release.apk` (~53 MB) | Older 32-bit phones |
| `app-universal-release.apk` (~205 MB) | Any device (all CPU types bundled) |

Allow "Install unknown apps" for your browser/file manager when prompted. Requires Android 7.0+.

## Build
```bash
cd YTDownloader
./gradlew assembleRelease     # APKs in app/build/outputs/apk/release/
```
GitHub Actions (`.github/workflows/android-apk.yml`) also builds the APKs on every push that touches this folder;
download them from the workflow run's *Artifacts*.

## Note
Only download content you own or have permission to download, and respect YouTube's Terms of Service and copyright.
