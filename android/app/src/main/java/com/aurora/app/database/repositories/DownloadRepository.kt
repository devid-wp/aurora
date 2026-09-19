package com.aurora.app.database.repositories

import com.aurora.app.database.AuroraDatabase
import com.aurora.app.database.entities.DownloadEntity

/** Persistence for download jobs. Status strings match [com.aurora.app.transfer.DownloadState]. */
class DownloadRepository(private val database: AuroraDatabase) {
    private val dao = database.downloadDao()

    fun createJob(
        downloadId: Long,
        source: String,
        sourceTrackId: String,
        title: String,
        artist: String,
        uri: String,
        localPath: String? = null,
        trackId: Long? = null
    ): Long {
        return dao.insert(
            DownloadEntity(
                downloadId = downloadId,
                trackId = trackId,
                source = source,
                sourceTrackId = sourceTrackId,
                title = title,
                artist = artist,
                uri = uri,
                status = "queued",
                localPath = localPath
            )
        )
    }

    fun updateStatus(downloadId: Long, status: String, localPath: String? = null, errorMessage: String? = null): Int {
        val current = dao.getById(downloadId) ?: return 0
        val now = System.currentTimeMillis()
        return dao.update(
            current.copy(
                status = status,
                localPath = localPath ?: current.localPath,
                errorMessage = errorMessage,
                updatedAt = now,
                completedAt = if (status == "completed") now else current.completedAt
            )
        )
    }

    fun updateProgress(downloadId: Long, downloadedBytes: Long, totalBytes: Long): Int {
        val current = dao.getById(downloadId) ?: return 0
        return dao.update(
            current.copy(
                downloadedBytes = downloadedBytes,
                totalBytes = totalBytes,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    fun complete(
        downloadId: Long,
        trackId: Long,
        localPath: String?,
        downloadedBytes: Long? = null,
        totalBytes: Long? = null
    ): Int {
        val current = dao.getById(downloadId) ?: return 0
        val now = System.currentTimeMillis()
        return dao.update(
            current.copy(
                trackId = trackId,
                status = "completed",
                localPath = localPath ?: current.localPath,
                downloadedBytes = downloadedBytes ?: current.downloadedBytes,
                totalBytes = totalBytes ?: current.totalBytes,
                errorMessage = null,
                updatedAt = now,
                completedAt = now
            )
        )
    }

    fun getAll(): List<DownloadEntity> = dao.getAll()

    fun getByTrackId(trackId: Long): List<DownloadEntity> = dao.getByTrackId(trackId)

    fun getByStatus(status: String): List<DownloadEntity> = dao.getByStatus(status)

    fun findActive(source: String, sourceTrackId: String): DownloadEntity? = dao.findActive(source, sourceTrackId)

    fun getById(downloadId: Long): DownloadEntity? = dao.getById(downloadId)

    fun delete(downloadId: Long): Int = dao.deleteById(downloadId)
}
