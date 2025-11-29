# GeoVault

*Self-hosted platform to organize your personal spatial data in a unified database.*

<br>

<p align="center">
  <img src="other/deviceframes.png" alt="deviceframes.com" height="200px">
</p>

<br>

An outdoorsman tends to collect all sorts of spatial data: tracks of hikes, points of interest, and so on. This data
tends to be scattered across numerous files stored in your documents and it isn't easy to see where you've been.
*GeoVault* is a self-hosted web platform that stores this data and presents *all* of it on one map.

The goal of this project is to automate as much of the pipeline as possible and focus on the user experience. Many GIS
platforms end up extremely complicated. GeoVault aims to automate much of that complication.

Development is done on my personal Git server, [git.evulid.cc](https://git.evulid.cc/cyberes/geovault), and is mirrored
to [GitHub](https://github.com/Cyberes/geovault).

**Features:**

- Streamlined upload and import process that makes it easy to shove your spatial data into the database
- KMZ, KML, and GPX files supported
- Tag and collection based organization
- Link-based public sharing
- Reverse geocoding to show what features are associated with
- Heavy data processing behind the scenes
- API key authentication for programmatic access
- Android app for quick file uploads via share intent

**This platform does not support editing.** Use your own preferred tool and then upload your data to the server.

## Installation

Installation instructions are in the [installation/](https://git.evulid.cc/cyberes/geovault/src/branch/master/installation) folder.

## Development

Test files are in the [geovault-tests](https://git.evulid.cc/cyberes/geovault-tests) repository. Please submit issues on [git.evulid.cc](https://git.evulid.cc/cyberes/geovault).

If you are having issues uploading or importing files, please provide the problem file. You can email it to me if you'd like.

## API Keys

GeoVault supports API key authentication for programmatic access to the API. API keys can be created and managed from the Account Settings page in the web interface.

### Creating API Keys

1. Navigate to Settings → Account Settings
2. Scroll to the "API Keys" section
3. Enter a name for your key (e.g., "My Phone", "Desktop App")
4. Click "Create API Key"
5. **Important**: Copy the full key immediately - it will only be shown once. The key starts with `gv_` and is 64 characters long.

### Using API Keys

API keys are authenticated using the `Authorization` header with the `Bearer` token format:

```
Authorization: Bearer gv_<your-api-key-here>
```

API keys have full access to all API endpoints except for API key management routes (create, delete, list API keys). This ensures that compromised keys cannot be used to create additional keys.

### Validating API Keys

You can validate an API key by making a POST request to `/api/user/api-keys/validate/` with the `Authorization: Bearer <key>` header. This endpoint can be called using either session authentication or an API key.

## Android Uploader App

A simple Android app is included that allows you to quickly upload KML/KMZ/GPX files to your GeoVault server via Android's share intent.

### Building the App

From the `src/android` directory, run:

```bash
./build-android.sh
```

This will build a debug APK located at `app/build/outputs/apk/debug/app-debug.apk`.

### Installing and Using

1. Install the APK on your Android device
2. On first launch, you'll be prompted to enter:
   - **Server URL**: Your GeoVault server URL (e.g., `https://geovault.example.com`)
   - **API Key**: An API key created from the web interface
3. To upload a file:
   - Share a KML, KMZ, or GPX file from any app (file manager, email, etc.)
   - Select "GeoVault Uploader" from the share menu
   - Optionally rename the file (the app will automatically append `_android_upload_<timestamp>` to the filename)
   - Tap "Upload" to send the file to your server

The app will show upload progress and automatically close on successful upload.
