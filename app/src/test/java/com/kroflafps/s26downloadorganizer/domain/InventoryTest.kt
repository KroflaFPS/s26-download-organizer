package com.kroflafps.s26downloadorganizer.domain

import java.nio.file.Paths
import org.junit.Assert.*
import org.junit.Test

class InventoryTest {
    @Test fun extensionsRespectHiddenNamesMissingSuffixAndUnusualCharacters() {
        val cases = mapOf(
            "README" to null, ".hidden" to null, "file." to null, "file.   " to null,
            ".config.json" to "json", "archive.tar.gz" to "gz", "PHOTO.JPG" to "JPG",
            "račun 你好 (1).pdf" to "pdf", "odd #[$] name.unknown-type" to "unknown-type",
            "zero.txt" to "txt", "file..suffix" to "suffix",
        )
        cases.forEach { (name, expected) -> assertEquals(name, expected, fileExtension(name)) }
    }

    @Test fun foldersAndLinksDoNotPretendToHaveFileExtensions() {
        for (type in EntryType.entries.filter { it != EntryType.FILE }) {
            assertNull(InventoryEntry("id", "folder.zip", type, null, null).extension)
        }
    }

    @Test fun sortIsNewestFirstThenExactFilenameThenIdentityWithMissingTimesLast() {
        fun entry(id: String, name: String, time: Long?) = InventoryEntry(id, name, EntryType.FILE, 0, time)
        val entries = listOf(
            entry("z", "unknown", null), entry("b", "same", 10), entry("old", "A", 0),
            entry("new", "new", 20), entry("a", "same", 10), entry("first", "A", 10),
        )
        val expected = listOf("new", "first", "a", "b", "old", "z")
        assertEquals(expected, entries.sortedWith(inventoryOrder).map { it.id })
        assertEquals(expected, entries.reversed().sortedWith(inventoryOrder).map { it.id })
    }

    @Test fun exclusionMatchesRootAndDescendantsButNotSiblingPrefixes() {
        val policy = SourceExclusionPolicy(Paths.get("/primary/Organized"))
        for (path in listOf("/primary/Organized", "/primary/Organized/Images/a.jpg", "/primary/Organized/")) {
            assertTrue(path, policy.excludes(Paths.get(path)))
        }
        for (path in listOf("/primary/Organized-old/a", "/primary/Organized2", "/primary/Download/a", "/primary/organized/a")) {
            assertFalse(path, policy.excludes(Paths.get(path)))
        }
    }

    @Test fun exclusionNormalizesDotSegmentsAtComponentBoundaries() {
        val policy = SourceExclusionPolicy(Paths.get("/primary/Download/../Organized"))
        assertTrue(policy.excludes(Paths.get("/primary/Download/../Organized/Images/./a")))
        assertFalse(policy.excludes(Paths.get("/primary/Organized/../Download/a")))
        val relative = Paths.get("fixture/Organized")
        assertTrue(SourceExclusionPolicy(relative).excludes(relative.toAbsolutePath().resolve("a")))
    }
}
