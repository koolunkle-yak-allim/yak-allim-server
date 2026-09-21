package com.example.yakallim.ocr.service

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.time.Instant
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isRegularFile
import kotlin.streams.asSequence

@Component
class UploadCleanupScheduler(
    @param:Value("\${ocr.upload-dir:outputs/api-images}") private val uploadDirStr: String,
    @param:Value("\${ocr.retention-minutes:60}") private val retentionMinutes: Long
) {
    private val log = LoggerFactory.getLogger(UploadCleanupScheduler::class.java)
    private val baseDir: Path = Paths.get(uploadDirStr).toAbsolutePath().normalize()

    @Scheduled(fixedDelay = 30 * 60 * 1000)
    fun cleanupStaleUploads() {
        if (!baseDir.exists()) return

        val cutoff = Instant.now().minus(Duration.ofMinutes(retentionMinutes))

        Files.list(baseDir).use { paths ->
            paths.asSequence()
                .filter { it.isRegularFile() && it.getLastModifiedTime().toInstant().isBefore(cutoff) }
                .forEach { path ->
                    runCatching { Files.deleteIfExists(path) }
                        .onFailure { log.warn("Failed to delete stale upload: {}", path, it) }
                        .onSuccess { log.info("Deleted stale upload past retention: {}", path) }
                }
        }
    }
}
