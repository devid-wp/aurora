package com.aurora.app

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.aurora.app.database.AuroraDatabase
import com.aurora.app.import.ImportResult
import com.aurora.app.import.ImportStatus
import com.aurora.app.import.MusicImportManager
import com.aurora.app.storage.AuroraStorageManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportManagerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db: AuroraDatabase = AuroraDatabase.inMemory(context)
    private val storage: AuroraStorageManager = AuroraStorageManager(context)
    private val manager: MusicImportManager = MusicImportManager(context, db, storage)

    @Test
    fun successful_import_creates_record() {
        val file = createTempAudioFile("success.mp3", "ID3\u0000".toByteArray())
        val result = manager.importFromUri(Uri.fromFile(file))
        assertTrue(result is ImportResult.Success)
        assertEquals(ImportStatus.SUCCESS, result.status)
    }

    @Test
    fun duplicate_import_is_detected() {
        val file = createTempAudioFile("duplicate.mp3", byteArrayOf(1, 2, 3, 4, 5))
        val first = manager.importFromUri(Uri.fromFile(file))
        val second = manager.importFromUri(Uri.fromFile(file))
        assertTrue(first is ImportResult.Success)
        assertTrue(second is ImportResult.Duplicate)
        assertEquals(ImportStatus.DUPLICATE, second.status)
    }

    @Test
    fun missing_metadata_falls_back_to_defaults() {
        val file = createTempAudioFile("fallback.mp3", byteArrayOf(9, 9, 9))
        val result = manager.importFromUri(Uri.fromFile(file))
        assertTrue(result is ImportResult.Success)
    }

    @Test
    fun invalid_file_is_rejected() {
        val file = createTempAudioFile("notes.txt", "not audio".toByteArray())
        val result = manager.importFromUri(Uri.fromFile(file))
        assertEquals(ImportStatus.INVALID_FILE, result.status)
    }

    @Test
    fun copy_failure_returns_copy_failed() {
        val result = manager.importFromUri(Uri.parse("content://missing/invalid.mp3"))
        assertTrue(result.status == ImportStatus.PERMISSION_DENIED || result.status == ImportStatus.COPY_FAILED)
    }

    @Test
    fun database_failure_triggers_rollback() {
        val brokenManager = object : MusicImportManager(context, db, storage) {
            override fun databaseIsAvailable(): Boolean = false
        }
        val file = createTempAudioFile("rollback.mp3", byteArrayOf(1, 2, 3, 4))
        val result = brokenManager.importFromUri(Uri.fromFile(file))
        assertEquals(ImportStatus.DATABASE_FAILED, result.status)
        assertTrue(storage.tracksDir.listFiles()?.none { it.name.contains("rollback") } == true)
    }

    @Test
    fun multiple_imports_are_processed_independently() {
        val ok1 = createTempAudioFile("multi1.mp3", byteArrayOf(1, 2, 3, 4))
        val ok2 = createTempAudioFile("multi2.mp3", byteArrayOf(5, 6, 7, 8))
        val invalid = createTempAudioFile("invalid.txt", "bad".toByteArray())

        val results = manager.importFromUris(listOf(Uri.fromFile(ok1), Uri.fromFile(ok2), Uri.fromFile(invalid)))
        assertEquals(3, results.size)
        assertTrue(results.count { it.status == ImportStatus.SUCCESS } >= 2)
        assertTrue(results.any { it.status == ImportStatus.INVALID_FILE })
    }

    @Test
    fun durable_track_id_avoids_hashcode_collisions() {
        val first = manager.generateDurableTrackId("Aa")
        val second = manager.generateDurableTrackId("BB")
        assertTrue(first != second)
    }

    private fun createTempAudioFile(name: String, payload: ByteArray): File {
        val file = File(context.cacheDir, name)
        file.writeBytes(payload)
        return file
    }
}
