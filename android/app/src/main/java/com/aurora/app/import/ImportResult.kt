package com.aurora.app.import

import android.net.Uri

enum class ImportStatus {
    SUCCESS,
    DUPLICATE,
    INVALID_FILE,
    PERMISSION_DENIED,
    COPY_FAILED,
    METADATA_FAILED,
    DATABASE_FAILED
}

sealed class ImportResult(
    val status: ImportStatus,
    val message: String,
    val sourceUri: Uri? = null
) {
    data class Success(
        val trackId: Long,
        val title: String,
        val source: Uri? = null
    ) : ImportResult(ImportStatus.SUCCESS, "Imported successfully", source)

    data class Duplicate(
        val existingTrackId: Long,
        val title: String,
        val source: Uri? = null
    ) : ImportResult(ImportStatus.DUPLICATE, "Duplicate track detected", source)

    data class InvalidFile(
        val reason: String,
        val source: Uri? = null
    ) : ImportResult(ImportStatus.INVALID_FILE, reason, source)

    data class PermissionDenied(
        val source: Uri? = null
    ) : ImportResult(ImportStatus.PERMISSION_DENIED, "Permission denied", source)

    data class CopyFailed(
        val reason: String,
        val source: Uri? = null
    ) : ImportResult(ImportStatus.COPY_FAILED, reason, source)

    data class MetadataFailed(
        val reason: String,
        val source: Uri? = null
    ) : ImportResult(ImportStatus.METADATA_FAILED, reason, source)

    data class DatabaseFailed(
        val reason: String,
        val source: Uri? = null
    ) : ImportResult(ImportStatus.DATABASE_FAILED, reason, source)
}
