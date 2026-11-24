# KTube: ad-free YouTube + YouTube Music player (Android)

A lightweight third-party client for YouTube and YouTube Music.
Streams are fetched directly with [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor),
so no ads load, and nothing needs a Google account.

## Features
**YouTube mode (Home tab + Search)**
- Home feeds: *For you* (based on what you watched), *Subscriptions*, Live, Gaming, Podcasts, Music, Trailers
- Search with suggestions & history; filter by Videos / Channels / Playlists
- Player with quality picker (144p–4K), playback speed, captions, fullscreen (auto on rotate), picture-in-picture
- Channel and playlist pages with Play all / Shuffle / Listen
- Up next queue + autoplay

**Music mode (Music tab)**
- YT Music search: songs, playlists, artists
- Songs play as audio only (saves data), endless radio from related songs, mini player like YT Music

**Background and lock screen**
- Keeps playing when you lock the phone, switch apps or swipe the app away
- Controls on the lock screen, in the notification, and on headsets/Bluetooth/smart watches
- "Audio only" toggle for any video; pauses when headphones are unplugged

**Library (on-device only)**: history, liked videos/songs, subscriptions

Also opens YouTube links shared from other apps (Share → KTube) and can be chosen to open youtube.com links.

## Install
Download `app-release.apk` (≈10 MB, all phones, Android 7.0+), allow "Install unknown apps", install.

## Build
```bash
cd KTube && ./gradlew assembleRelease   # -> app/build/outputs/apk/release/app-release.apk
```
GitHub Actions (`.github/workflows/ktube-apk.yml`) builds it on every push to `KTube/`.

## Known limitations
- YouTube changes often; if playback or search breaks, the fix is usually bumping the NewPipeExtractor version in `app/build.gradle.kts`.
- No Google sign-in: subscriptions/likes are local, members-only/age-restricted videos can't play.
- YT Music "Songs" results come from the music-videos shelf (the extractor can't currently parse the songs/albums shelves).

Use in line with YouTube's Terms of Service and copyright law.
