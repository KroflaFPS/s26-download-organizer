package com.kroflafps.s26downloadorganizer.operations

import java.nio.file.Paths
import org.junit.Assert.*
import org.junit.Test

class OperationPolicyTest {
    @Test fun namesPreserveExtensionsDotfilesAndUnicode() {
        val cases = mapOf("file.txt" to "file (2).txt", "noext" to "noext (2)", ".hidden" to ".hidden (2)",
            ".config.json" to ".config (2).json", "račun 你好.tar.gz" to "račun 你好.tar (2).gz", "a." to "a. (2)")
        cases.forEach { (input, expected) -> assertEquals(expected, OperationPolicy.candidate(input, 2)) }
    }
    @Test fun knownDownloadAndMalformedNamesAreRejected() {
        for (name in listOf("", ".", "..", "../file", "a\u0000", "x.part", "x.CRDownload", "x.tmp", ".pending-123")) {
            assertFalse(name, OperationPolicy.eligibleName(name))
        }
    }
    @Test fun utf8BudgetDoesNotSplitCodePointsOrLoseExtension() {
        val long = "📁".repeat(80) + ".pdf"
        val name = OperationPolicy.candidate(long, 9999)
        assertTrue(name.toByteArray().size <= 255)
        assertTrue(name.endsWith(" (9999).pdf"))
        assertFalse(name.contains('\uFFFD'))
    }
    @Test fun impossibleSuffixAndExhaustedCollisionSpaceFailClosed() {
        for (request in listOf({ OperationPolicy.candidate("a." + "x".repeat(300), 1) }, { OperationPolicy.candidate("a", 10000) })) {
            try { request(); fail("Expected rejection") } catch (_: OperationFailure) { }
        }
    }
    @Test fun normalizedBoundaryChecksRejectTraversalOutsideRootsAndRelativePaths() {
        val root = Paths.get("/fixture/Download")
        for (raw in listOf("", "relative.txt", "/fixture/Download/../Other/a", "/fixture/Download/sub/a", "/fixture/Download2/a", "/fixture/Organized/Other/a")) {
            try { OperationPolicy.sourcePath(raw, root); fail(raw) } catch (_: OperationFailure) { }
        }
        assertEquals(root.resolve("a.txt"), OperationPolicy.sourcePath("/fixture/Download/a.txt", root))
    }
}
