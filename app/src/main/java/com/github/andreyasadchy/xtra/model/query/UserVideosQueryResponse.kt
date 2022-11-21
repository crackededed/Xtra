package com.github.andreyasadchy.xtra.model.query

import com.github.andreyasadchy.xtra.model.helix.video.Video

data class UserVideosQueryResponse(val data: List<Video>, val cursor: String?, val hasNextPage: Boolean?)