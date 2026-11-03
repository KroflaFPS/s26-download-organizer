package com.kroflafps.s26downloadorganizer.diagnostic

import com.kroflafps.s26downloadorganizer.operations.FileEvidence
import java.io.Closeable
import java.nio.file.Path
import java.security.MessageDigest

/** Writable handles may ONLY originate from atomic exclusive creation, and are never reopened writable. */
internal interface TestFileHandle : Closeable {
    fun metadata(): TestMetadata
    fun rewind()
    fun read(buffer: ByteArray, count: Int): Int
    fun write(buffer: ByteArray, count: Int)
    fun sync()
}
internal data class TestMetadata(val identity: String, val size: Long, val stamp: String)
internal interface TestDirectoryHandles : Closeable {
    val input: Path
    val output: Path
    fun validate()
    fun source(name: String): TestFileHandle
    fun outputRead(name: String): TestFileHandle
    fun exclusiveOutput(name: String): TestFileHandle
    fun requireEntry(name: String, identity: String, source: Boolean)
    fun syncOutput()
}

/** Shared bounded streaming/verification implementation. Android supplies only guarded descriptor I/O. */
internal class DiagnosticIo(private val directories: TestDirectoryHandles) : DiagnosticSession {
    override val input get() = directories.input
    override val output get() = directories.output
    private val outputs = mutableMapOf<String, TestFileHandle>()
    private var sourceHandle: TestFileHandle? = null
    private var sourceName: String? = null

    override fun source(name: String, check: () -> Unit): FileEvidence {
        DiagnosticPolicy.requireName(name); check(); directories.validate()
        if (sourceName != name) { sourceHandle?.close(); sourceHandle = null; sourceName = null }
        val handle = sourceHandle ?: directories.source(name).also { sourceHandle = it; sourceName = name }
        val result = fingerprint(handle, input.resolve(name), check)
        directories.requireEntry(name, result.identity, source = true)
        DiagnosticPolicy.requireFixture(result, name)
        return result
    }
    override fun createStage(name: String): String {
        require(name.matches(Regex("\\.s26-diagnostic-[0-9a-f-]{36}\\.part")))
        java.util.UUID.fromString(name.removePrefix(".s26-diagnostic-").removeSuffix(".part"))
        return create(name)
    }
    override fun createFinal(name: String): String {
        require(DiagnosticPolicy.fixtures.keys.any { original -> (0..999).any { com.kroflafps.s26downloadorganizer.operations.OperationPolicy.candidate(original, it) == name } })
        return create(name)
    }
    private fun create(name: String): String {
        directories.validate(); require(name !in outputs)
        val handle = directories.exclusiveOutput(name)
        outputs[name] = handle // Retain the handle even if subsequent evidence/DB writes fail.
        val metadata = handle.metadata()
        require(metadata.identity.isNotBlank() && metadata.size == 0L)
        directories.requireEntry(name, metadata.identity, source = false)
        return metadata.identity
    }
    override fun copySourceToStage(source: FileEvidence, stageName: String, identity: String, check: () -> Unit) {
        require(this.source(requireNotNull(sourceName), check) == source)
        val stage = requireNotNull(outputs[stageName]); require(stage.metadata().identity == identity)
        copy(requireNotNull(sourceHandle), stage, source, stageName, check)
        require(fingerprint(stage, output.resolve(stageName), check).let { it.hash == source.hash && it.size == source.size })
        require(this.source(requireNotNull(sourceName), check) == source)
    }
    override fun copyStageToFinal(stageName: String, stageIdentity: String, finalName: String, finalIdentity: String, source: FileEvidence, check: () -> Unit) {
        require(this.source(requireNotNull(sourceName), check) == source)
        val stage = requireNotNull(outputs[stageName]); val final = requireNotNull(outputs[finalName])
        require(stage.metadata().identity == stageIdentity && final.metadata().identity == finalIdentity)
        directories.requireEntry(stageName, stageIdentity, source = false)
        val before = fingerprint(stage, output.resolve(stageName), check)
        require(before.hash == source.hash && before.size == source.size)
        copy(stage, final, source, finalName, check)
        val verified = fingerprint(final, output.resolve(finalName), check)
        require(verified.hash == source.hash && verified.size == source.size && verified.identity == finalIdentity)
        require(fingerprint(stage, output.resolve(stageName), check) == before)
        directories.requireEntry(stageName, stageIdentity, source = false)
        directories.requireEntry(finalName, finalIdentity, source = false)
        require(this.source(requireNotNull(sourceName), check) == source)
    }
    private fun copy(from: TestFileHandle, to: TestFileHandle, expected: FileEvidence, destinationName: String, check: () -> Unit) {
        val original = from.metadata(); val target = to.metadata()
        require(original.size == expected.size && target.size == 0L)
        from.rewind(); to.rewind()
        val buffer = ByteArray(64 * 1024); var remaining = expected.size
        val digest = MessageDigest.getInstance("SHA-256")
        while (remaining > 0) {
            check(); directories.validate(); directories.requireEntry(destinationName, target.identity, source = false)
            val count = from.read(buffer, minOf(buffer.size.toLong(), remaining).toInt())
            require(count in 1..minOf(buffer.size.toLong(), remaining).toInt())
            digest.update(buffer, 0, count); to.write(buffer, count); remaining -= count
        }
        check(); require(from.read(buffer, 1) == -1 && from.metadata() == original)
        require(digest.digest().hex() == expected.hash)
        to.sync(); check(); directories.validate(); directories.requireEntry(destinationName, target.identity, source = false)
    }
    private fun fingerprint(handle: TestFileHandle, path: Path, check: () -> Unit): FileEvidence {
        check(); directories.validate()
        val before = handle.metadata(); require(before.size in 0..DiagnosticPolicy.MAX_BYTES)
        handle.rewind(); val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(64 * 1024); var size = 0L
        while (true) {
            check(); directories.validate()
            val count = handle.read(buffer, minOf(buffer.size.toLong(), before.size - size + 1).toInt())
            check()
            if (count == -1) break
            require(count > 0 && count <= before.size - size)
            digest.update(buffer, 0, count); size += count
        }
        require(size == before.size && handle.metadata() == before)
        return FileEvidence(path.toString(), before.identity, size, before.stamp, digest.digest().hex())
    }
    override fun syncOutputDirectory() { directories.validate(); directories.syncOutput(); directories.validate() }
    override fun describe(record: CopyRecord, check: () -> Unit): String {
        record.validate()
        require(record.sourcePath == input.resolve(record.name).toString() && record.outputDirectory == output.toString())
        val source = try { source(record.name, check).let { it.identity == record.sourceIdentity && it.stamp == record.sourceStamp && it.hash == record.hash } } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { false }
        fun output(name: String?, key: String?): String {
            if (name == null) return "not allocated"
            return try { directories.outputRead(name).use {
                val evidence = fingerprint(it, output.resolve(name), check)
                directories.requireEntry(name, evidence.identity, source = false)
                if (key != null && evidence.identity == key && evidence.hash == record.hash && evidence.size == record.size) "matching" else "partial/conflict"
            } } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { "missing/unverifiable" }
        }
        return "Recovery required. Original ${if (source) "matches" else "missing/changed/unverifiable"}; stage ${output(java.nio.file.Paths.get(record.stagePath).fileName.toString(), record.stageIdentity)}; final ${output(record.finalPath?.let { java.nio.file.Paths.get(it).fileName.toString() }, record.finalIdentity)}. Preserve all outputs. No retry or cleanup was performed."
    }
    override fun close() {
        var error: Exception? = null
        for (handle in outputs.values.toList().asReversed() + listOfNotNull(sourceHandle)) try { handle.close() } catch (e: Exception) { error = e }
        try { directories.close() } catch (e: Exception) { error = e }
        error?.let { throw it }
    }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
}
