# BridgeShare

Share files between an Android phone and an iPhone **without installing anything on the iPhone**.

The Android app runs a small web server on the phone. The iPhone scans a QR code with its
Camera and opens a page in Safari to download files from Android or upload photos and
files to it. Transfers stay on the local Wi-Fi: no internet, no cloud.

## AirDrop

Third-party apps can't speak Apple's AirDrop protocol themselves. On phones whose built-in
Quick Share supports **Share with Apple devices** (for example Galaxy S24 on One UI 8.5,
Galaxy S26, Pixel 10), the **Send via AirDrop (Quick Share)** button hands your files to
Quick Share, and the iPhone shows up there as an AirDrop target.

- Android: Settings › Connected devices › Quick Share › turn on **Share with Apple devices**.
- iPhone: Settings › General › AirDrop › **Everyone for 10 minutes**.

On other phones, use the QR code / Safari method below.

## Install

1. Copy `release/BridgeShare-1.1.apk` to the Android phone (Android 8.0 or newer) and open it.
2. Allow "Install unknown apps" for your browser or file manager when asked.

## Use

1. Open BridgeShare and tap **Start sharing**.
   - **Same Wi-Fi:** connect both phones to the same Wi-Fi network.
   - **No Wi-Fi around:** turn on **Create my own Wi-Fi**. The iPhone scans the first QR
     code to join it (Location must be on for Android 8–12).
2. On the iPhone, open **Camera**, point it at the QR code and tap the link.
3. **Android → iPhone:** tap **Add files** (or use *Share → BridgeShare* from any app).
   On the iPhone, tap **Save** (goes to Files › Downloads) or **View** then press and hold
   a photo to choose **Save to Photos**.
4. **iPhone → Android:** tap **Choose photos or files** on the iPhone. Files are saved to
   `Pictures/BridgeShare`, `Movies/BridgeShare` or `Download/BridgeShare` on Android.

Each session uses a random access key in the QR code, so other devices on the same
Wi-Fi can't open your files without scanning it.

## Build

```
cd BridgeShare
./gradlew assembleDebug    # needs the Android SDK (platform 35)
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. The
`Build BridgeShare APK` GitHub Actions workflow also builds it on every push.
