package com.s410211.a55wuwa

/**
 * A conservative hint parser for Samsung build display strings. Many retail
 * builds do not include the One UI version in public Build fields; null means
 * "not identifiable here", not "not a Samsung device".
 */
object OneUiVersionParser {
    private val pattern = Regex(
        """(?i)One\s*UI[\s/_-]*([0-9]+(?:\.[0-9]+){0,2})"""
    )

    fun parse(vararg buildStrings: String?): String? =
        buildStrings.asSequence()
            .filterNotNull()
            .mapNotNull { pattern.find(it)?.groupValues?.getOrNull(1) }
            .firstOrNull()
}
