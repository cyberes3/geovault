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

## Setting Up Release Signing

To build a signed release APK, you need to set up a keystore file and configure signing credentials:

1. **Generate a keystore file** (one-time setup):
   ```bash
   keytool -genkey -v -keystore app/keystore.jks -keyalg RSA -keysize 2048 -validity 10000 -alias upload
   ```
   
   This will prompt you for:
   - A password for the keystore (remember this - you'll need it when building)
   - Your name, organizational unit, organization, city, state, and country code
   - A password for the key alias (remember this - you'll need it when building)

2. **Build the signed release APK**:
   ```bash
   ./build-android.sh release
   # or directly:
   ./gradlew assembleRelease
   ```
   
   When building, you'll be prompted to enter:
   - Your keystore password
   - Your key password
   
   The keystore path and alias are configured in `gradle.properties` (no passwords are stored there for security).

**Important Security Notes:**
- The keystore file (`keystore.jks`) is automatically excluded from version control via `.gitignore`
- Never commit your keystore file or passwords to version control
- Keep your keystore file and passwords secure - if you lose the keystore, you won't be able to update your app on Google Play Store
- Consider backing up your keystore file securely (e.g., encrypted backup)