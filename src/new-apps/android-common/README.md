## Building an APK

```bash
./build-android.sh [debug|release|clean] [--install]
```



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

3. **Optional: use `src/.env` for non-interactive release builds**

   Copy `src/.env.example` to `src/.env` and set:
   - `RELEASE_STORE_FILE` – path to your keystore file (e.g. absolute path).
   - `ANDROID_KEY_PASSWORD_FILE` – (optional) path to a text file containing the keystore password (one line). Use `chmod 600` and keep it outside the repo.

   Then `./build-android.sh release` will use these without prompting. Without `.env`, you can still pass `RELEASE_STORE_PASSWORD` / `RELEASE_KEY_PASSWORD` via the environment or enter them when prompted.
