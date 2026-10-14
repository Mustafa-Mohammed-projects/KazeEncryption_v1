# Kaze Encryption

AES-256 file and folder encryptor for Android (Kotlin). Same file format as the original
Python tool: `[salt 32][iv 16][AES-256-CBC ciphertext]`, key from PBKDF2-HMAC-SHA256 (100,000 iterations).

## Build on GitHub
1. Replace `app/src/main/res/drawable-nodpi/presplash.png` with your own image (keep the name).
2. Create a GitHub repo and push this folder to the `main` branch.
3. Open the **Actions** tab, wait for "Build APK" to finish, then download
   `KazeEncryption-debug-apk` from the run's Artifacts.

Requirements are handled by the workflow (JDK 17, Gradle 8.7, AGP 8.5.2, minSdk 26).
