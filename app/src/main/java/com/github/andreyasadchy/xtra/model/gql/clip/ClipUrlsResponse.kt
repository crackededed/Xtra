package com.github.andreyasadchy.xtra.model.gql.clip

import com.github.andreyasadchy.xtra.model.gql.Error
import kotlinx.serialization.Serializable

@Serializable
class ClipUrlsResponse(
    val errors: List<Error>? = null,
    val data: Data? = null,
) {
    @Serializable
    class Data(
        val clip: Clip,
    )

    @Serializable
    class Clip(
        val playbackAccessToken: PlaybackAccessToken? = null,
        val assets: List<ClipAsset>
    )

    @Serializable
    class PlaybackAccessToken(
        val value: String? = null,
        val signature: String? = null,
    )

    @Serializable
    class ClipAsset(
        val videoQualities: List<Quality>,
        val portraitMetadata: PortraitClipCropping? = null,
    )

    @Serializable
    class Quality(
        val bitrate: Int? = null,
        val codecs: String? = null,
        val sourceURL: String,
        val frameRate: Float? = null,
        val quality: String? = null,
    )

    @Serializable
    class PortraitClipCropping(
        val portraitClipLayout: String? = null,
    )
}