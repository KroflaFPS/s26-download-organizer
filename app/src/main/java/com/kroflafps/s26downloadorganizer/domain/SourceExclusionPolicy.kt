package com.kroflafps.s26downloadorganizer.domain

import java.nio.file.Path

/** Lexical path components, not string prefixes. Does not resolve/follow symlinks. */
class SourceExclusionPolicy(destinationRoot: Path) {
    private val excluded = destinationRoot.toAbsolutePath().normalize()

    fun excludes(candidate: Path): Boolean =
        candidate.toAbsolutePath().normalize().startsWith(excluded)
}
