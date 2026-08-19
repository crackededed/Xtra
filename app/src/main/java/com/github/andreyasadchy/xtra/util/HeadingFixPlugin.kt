package com.github.andreyasadchy.xtra.util

import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.core.CorePlugin
import org.commonmark.internal.HeadingParser
import org.commonmark.node.Heading
import org.commonmark.parser.Parser
import org.commonmark.parser.block.BlockStart
import org.commonmark.parser.block.MatchedBlockParser
import org.commonmark.parser.block.ParserState

class HeadingFixPlugin: AbstractMarkwonPlugin() {

    override fun configureParser(builder: Parser.Builder) {
        val enabled = CorePlugin.enabledBlockTypes()
            .filter { it != Heading::class.java }
            .toSet()
        builder.enabledBlockTypes(enabled)
        builder.customBlockParserFactory(
            object : HeadingParser.Factory() {
                override fun tryStart(state: ParserState, matchedBlockParser: MatchedBlockParser): BlockStart? {
                    val line = state.line
                    var index = state.nextNonSpaceIndex
                    while (line.getOrNull(index) == '#') {
                        index += 1
                    }
                    val state = if (index > state.nextNonSpaceIndex) {
                        val char = line.getOrNull(index)
                        if (char != null && char != ' ' && char != '\t') {
                            object : ParserState {
                                override fun getLine(): CharSequence {
                                    return buildString {
                                        append(line.subSequence(0, index))
                                        append(' ')
                                        append(line.subSequence(index, line.lastIndex))
                                    }
                                }

                                override fun getIndex() = state.index
                                override fun getNextNonSpaceIndex() = state.nextNonSpaceIndex
                                override fun getColumn() = state.column
                                override fun getIndent() = state.indent
                                override fun isBlank() = state.isBlank
                                override fun getActiveBlockParser() = state.activeBlockParser
                            }
                        } else state
                    } else state
                    return super.tryStart(state, matchedBlockParser)
                }
            }
        )
    }
}