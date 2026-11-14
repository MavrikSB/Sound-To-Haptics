package com.sound.to.haptics.spotify

/**
 * Plain constants only — deliberately has zero dependency on the Spotify SDKs
 * (auth / app-remote) so it can live in `main` and compile under BOTH the
 * `offlineOnly` and `withSpotify` product flavors.
 *
 * [MainActivity] references [AUTH_REQUEST_CODE] to forward login callbacks;
 * that wiring needs to exist regardless of flavor, even though in
 * `offlineOnly` nothing ever triggers it.
 *
 * SETUP REQUIRED before this compiles/runs against a real Spotify account
 * (withSpotify flavor only):
 *  1. Register an app at https://developer.spotify.com/dashboard
 *  2. Put your Client ID in [CLIENT_ID] below
 *  3. Add a Redirect URI in the dashboard matching [REDIRECT_URI]
 *  4. Add your device's SHA-1 signing fingerprint + this app's package name
 *     under the dashboard's Android package settings
 *  5. The user must have the real Spotify app installed (App Remote controls it).
 */
object SpotifyConfig {
    const val CLIENT_ID = "35104bf478864f5486d218055337dd46"
    const val REDIRECT_URI = "hapticstudio://spotify-callback"
    const val AUTH_REQUEST_CODE = 1337
}
