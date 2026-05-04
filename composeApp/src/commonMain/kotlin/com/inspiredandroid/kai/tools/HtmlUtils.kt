package com.inspiredandroid.kai.tools

private val numericEntityRegex = Regex("&#x([0-9a-fA-F]+);|&#(\\d+);")
private val namedEntityMap = mapOf(
    "&nbsp;" to " ",
    "&lt;" to "<",
    "&gt;" to ">",
    "&amp;" to "&",
    "&quot;" to "\"",
    "&apos;" to "'",
    "&mdash;" to "\u2014",
    "&ndash;" to "\u2013",
    "&lsquo;" to "\u2018",
    "&rsquo;" to "\u2019",
    "&ldquo;" to "\u201C",
    "&rdquo;" to "\u201D",
    "&hellip;" to "\u2026",
    "&copy;" to "\u00A9",
    "&reg;" to "\u00AE",
    "&trade;" to "\u2122",
    "&laquo;" to "\u00AB",
    "&raquo;" to "\u00BB",
    "&bull;" to "\u2022",
    "&middot;" to "\u00B7",
    "&deg;" to "\u00B0",
    "&plusmn;" to "\u00B1",
    "&times;" to "\u00D7",
    "&divide;" to "\u00F7",
    "&euro;" to "\u20AC",
    "&pound;" to "\u00A3",
    "&yen;" to "\u00A5",
    "&cent;" to "\u00A2",
    "&para;" to "\u00B6",
    "&sect;" to "\u00A7",
    "&dagger;" to "\u2020",
    "&Dagger;" to "\u2021",
)

internal fun String.decodeHtmlEntities(): String {
    var result = this
    for ((entity, replacement) in namedEntityMap) {
        result = result.replace(entity, replacement)
    }
    result = numericEntityRegex.replace(result) { match ->
        val hex = match.groupValues[1]
        val dec = match.groupValues[2]
        try {
            val codePoint = if (hex.isNotEmpty()) hex.toInt(16) else dec.toInt()
            CodePoint_toChars(codePoint)
        } catch (_: Exception) {
            match.value
        }
    }
    return result
}

private fun CodePoint_toChars(codePoint: Int): String {
    return if (codePoint in 0..0xFFFF) {
        Char(codePoint).toString()
    } else {
        val high = Char((codePoint - 0x10000) / 0x400 + 0xD800)
        val low = Char((codePoint - 0x10000) % 0x400 + 0xDC00)
        String(charArrayOf(high, low))
    }
}
