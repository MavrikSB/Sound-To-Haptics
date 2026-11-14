package com.sound.to.haptics.bitchord

/**
 * Provider-neutral capability contract (SECTION 3.1 of the master spec).
 *
 * The adapter the app ships today is YouTube Music via BitChord's ported
 * InnerTube implementation. A provider can support playback without supporting
 * PCM analysis; the contract models that difference explicitly instead of
 * implying one. Every `true` below is backed by code in this build that
 * exists and has been reviewed — nothing here is aspirational, and anything
 * not supported is listed as a restriction instead of guessed at.
 *
 * If a different provider is ever selected, it supplies its own instance of
 * this contract — that is the replaceability boundary. Consumers must read
 * these values, not provider names.
 */
data class ProviderCapabilities(
    val supportsAuthentication: Boolean,
    val supportsSearch: Boolean,
    val supportsDiscovery: Boolean,
    val supportsTrackMetadata: Boolean,
    val supportsArtwork: Boolean,
    val supportsQueue: Boolean,
    val supportsPlayback: Boolean,
    val supportsPause: Boolean,
    val supportsSeek: Boolean,
    val supportsPlaybackSpeed: Boolean,
    /** Decoded PCM tapped off the player we control (ExoPlayer audio processor). */
    val supportsDecodedPcmAnalysis: Boolean,
    /** On-device caching of *derived data* (haptic profiles) — permitted. */
    val supportsPermittedCaching: Boolean,
    /** Audio download/offline is not offered or permitted to this client. */
    val supportsOfflinePlayback: Boolean,
    val supportsTrackIdentifiers: Boolean,
    val supportsSessionRestoration: Boolean,
    val supportsTokenRefresh: Boolean,
    val supportsExternalPlayback: Boolean,
    val supportedAudioFormats: List<String>,
    val knownRestrictions: List<String>,
    val attributionRequirements: String,
)

/**
 * The YouTube Music adapter as actually implemented and tested.
 *
 * Audio is never downloaded. Audio analysis happens by tapping PCM out of the
 * ExoPlayer instance this process owns — the same access path the local file
 * side of the app uses — never by extracting signed stream URLs or decrypted
 * media, which no provider permits this client to do.
 */
object BitChordProviderCapabilities {
    val current = ProviderCapabilities(
        supportsAuthentication = true,
        supportsSearch = true,
        supportsDiscovery = true,
        supportsTrackMetadata = true,
        supportsArtwork = true,
        supportsQueue = true,
        supportsPlayback = true,
        supportsPause = true,
        supportsSeek = true,
        supportsPlaybackSpeed = false,
        supportsDecodedPcmAnalysis = true,
        supportsPermittedCaching = true,
        supportsOfflinePlayback = false,
        supportsTrackIdentifiers = true,
        supportsSessionRestoration = true,
        supportsTokenRefresh = true,
        supportsExternalPlayback = false,
        supportedAudioFormats = listOf("audio/mp4 (AAC)", "audio/webm (Opus/Vorbis)"),
        knownRestrictions = listOf(
            "No audio download — YouTube Music is streaming-only for this client.",
            "Stream URLs are resolved at play time; none are persisted or reused.",
            "Rate limits (HTTP 429) and bot checks may block requests from some networks; the app reports this honestly and never bypasses it.",
            "Signed in by the listener's own Google account; no client secret is embedded.",
        ),
        attributionRequirements =
            "BitChord is a GPL-3.0 derivative of BitChord (GPL-3.0). This app must carry the GPL-3.0 LICENSE and the third-party notices in THIRD_PARTY_LICENSES.txt.",
    )
}
