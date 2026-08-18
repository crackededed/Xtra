package com.github.andreyasadchy.xtra.ui.common

import io.noties.markwon.AbstractMarkwonPlugin
object MarkdownHeadingSpacePlugin : AbstractMarkwonPlugin() {
    private val headingRegex = Regex("(?m)^#{1,6}(?=[^#\\s])")

    override fun processMarkdown(markdown: String): String =
        headingRegex.replace(markdown) { it.value + " " }
}
