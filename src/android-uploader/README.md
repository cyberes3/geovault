# GeoVault Android Uploader

A minimal Android app for quickly uploading KML/KMZ/GPX files to your GeoVault server via Android's share intent.

## Setup

1. Install the APK on your Android device
2. On first launch it will ask for your GeoVault server URL

## Usage

1. Share one or more KML, KMZ, or GPX files from any app
2. Select "GeoVault Uploader" from the share menu
3. Review the upload queue, optionally rename files (the app can append `_android_upload_<timestamp>` via settings)
4. Tap **Upload All** to send files to your server
5. Tap **Cancel** during an upload to stop the batch; the current file returns to the pending queue

From the app launcher you can validate your connection and use **Choose File** to open the same upload queue.
