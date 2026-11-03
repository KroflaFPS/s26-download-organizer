package com.kroflafps.s26downloadorganizer.telemetry

data class NameWindow(val names: List<String>, val nextAfter: String?)
object ScanWindow {
    const val ENUMERATION_LIMIT = 4096
    const val PAGE_LIMIT = 128
    fun select(names: List<String>, after: String?): NameWindow {
        require(names.size <= ENUMERATION_LIMIT)
        require(names.all { it.isNotEmpty() && it !in listOf(".", "..") && '/' !in it && '\u0000' !in it })
        val remaining = names.sorted().filter { after == null || it > after }
        val page = remaining.take(PAGE_LIMIT)
        return NameWindow(page, if (remaining.size > page.size) page.last() else null)
    }
}
