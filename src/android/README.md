# GeoVault Android Uploader

A minimal Android app for quickly uploading KML/KMZ/GPX files to your GeoVault server via Android's share intent.

<br>

<p align="center">
  <img src="screenshot.png" alt="screenshot">
</p>

<br>

## Setup

1. Install the APK on your Android device
2. On first launch, configure:
   - **Server URL**: Your GeoVault server URL (e.g., `https://geovault.example.com`)
   - **API Key**: Create an API key from the web interface (Settings → Account Settings → API Keys)

## Usage

1. Share a KML, KMZ, or GPX file from any app
2. Select "GeoVault Uploader" from the share menu
3. Optionally rename the file (the app automatically appends `_android_upload_<timestamp>` to the filename)
4. Tap "Upload" to send the file to your server

The app shows upload progress and automatically closes on successful upload.

## Building the APK

From the `src/android` directory:

**Debug build (default, faster, no signing required):**
```bash
./build-android.sh
# or explicitly:
./build-android.sh debug
```

**Release build (optimized, requires signing configuration):**
```bash
./build-android.sh release
```

The APK will be generated at:
- Debug: `app/build/outputs/apk/debug/app-debug.apk`
- Release: `app/build/outputs/apk/release/app-release.apk`

**Note:** Debug builds are faster and don't require signing, making them ideal for development and testing. Release builds are optimized and signed for distribution.