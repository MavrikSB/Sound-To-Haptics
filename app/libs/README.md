# Spotify App Remote .aar goes here — this step is REQUIRED

Unlike `com.spotify.android:auth`, the App Remote SDK is **not** published to
Maven Central, JCenter, or JitPack. Spotify only ships it as a raw `.aar` you
download yourself. Without it, the Spotify tab will not compile — this is not
optional and there is no Gradle-only workaround.

Steps:
1. Go to https://developer.spotify.com/documentation/android/quick-start
2. Sign in, click through to download the "Spotify App Remote SDK" zip
   (this is separate from the browsable GitHub source — it's a direct
   download link on that quick-start page)
3. Unzip it — inside you'll find something like
   `spotify-app-remote-release-0.8.0.aar`
4. Copy that single .aar file into this folder (`app/libs/`)
5. Sync Gradle — `app/build.gradle.kts` already has:
     implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))
   which picks up any .aar dropped here automatically.

You'll also need, in the Spotify Developer Dashboard, for your app:
- Package name: com.example.haptictest
- Redirect URI: hapticstudio://spotify-callback
- Your debug keystore's SHA-1 fingerprint (get it with:
  `keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android`
  on Mac/Linux, or the equivalent path on Windows — usually
  `C:\Users\<you>\.android\debug.keystore`)
