# Oracle Companion

Android client and Windows LAN gateway for Oracle. This first implementation supports QR or text enrollment, live status, one persisted Companion conversation shared with a Windows console, and a confirmed VS Code launch request.

## Windows

1. Start the existing Oracle desktop backend on loopback port 8000. Configure its local Ollama provider and start Ollama for chat.
2. Install the gateway dependencies with `python -m pip install -r gateway/requirements.txt`.
3. Find the Windows Wi-Fi IPv4 address with `ipconfig`. Run `python gateway/companion.py serve --address YOUR_WIFI_IPV4`. Keep the console open. If Windows prompts for firewall access, use the private network.
4. Scan or photograph the terminal QR from Android, or enter the displayed pairing text. Press Enter on Windows to issue a fresh code. Only share the QR directly with the phone being enrolled.
5. Open **Device → Open VS Code** on Android and confirm the PC name. **Activity** displays Windows’ reported outcome. Launch accepted means Oracle handed the request to Windows.

Run `python gateway/companion.py history` from another Windows console to read the Companion thread. Run `python gateway/companion.py chat "your message"` to continue it. This Windows console uses the Companion store. The existing Oracle desktop chat UI still uses its own history. For revocation use `python gateway/companion.py devices` and `python gateway/companion.py revoke DEVICE_ID`.

The optional global `--data` argument selects the same alternate state directory for all commands. By default the store and certificate are in `%LOCALAPPDATA%\OracleCompanion`. Back up the directory privately if you want to retain the conversation and pairing identity. A new certificate requires fresh phone enrollment.

## Android

Use JDK 17 or later and Android SDK 35 with the Android Gradle build tools. Set `ANDROID_HOME` or create an Android Studio `local.properties` file. Build with `./gradlew.bat :app:assembleDebug` on Windows, then install the generated APK under `app/build/outputs/apk/debug`. Android 8.0 or later is required. A signed debug APK is included for initial installation. It was assembled from the local Kotlin compiler and Android SDK tools because the build environment blocks Gradle daemon loopback connections. Runtime acceptance on Android is pending.

The app supports photo-based QR reading, text fallback, system light/dark appearance, and keeps the desktop credential encrypted with Android Keystore. Chat uses the local Ollama model configured by the desktop and loads whole replies. The Companion store and API are separate so the phone cannot invoke unrestricted commands or send chat through Oracle’s command-aware desktop route.

## Verification

Run `python -m unittest discover -s gateway -p "test_*.py"`. The tests use temporary state and mock Oracle/Ollama responses. Complete release acceptance by pairing on a physical Android phone on the same Wi-Fi, sending a message from each interface, testing revocation and reconnect, and confirming a VS Code launch on Windows.
