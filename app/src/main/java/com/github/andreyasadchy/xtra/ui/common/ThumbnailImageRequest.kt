package com.github.andreyasadchy.xtra.ui.common

import coil3.request.ImageRequest
import coil3.request.error
import coil3.request.fallback
import coil3.request.placeholder
import com.github.andreyasadchy.xtra.R

internal fun ImageRequest.Builder.thumbnailState(): ImageRequest.Builder = apply {
    placeholder(R.drawable.bg_thumbnail_placeholder)
    error(R.drawable.ic_thumbnail_error)
    fallback(R.drawable.ic_thumbnail_error)
}
