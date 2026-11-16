package com.sound.to.haptics.bitchord

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

    val supportsDecodedPcmAnalysis: Boolean,

    val supportsPermittedCaching: Boolean,

    val supportsOfflinePlayback: Boolean,
    val supportsTrackIdentifiers: Boolean,
    val supportsSessionRestoration: Boolean,
    val supportsTokenRefresh: Boolean,
    val supportsExternalPlayback: Boolean,
    val supportedAudioFormats: List<String>,
    val knownRestrictions: List<String>,
    val attributionRequirements: String,
)

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
