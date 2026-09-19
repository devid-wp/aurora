package com.aurora.app

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.aurora.app.storage.AuroraStorageManager
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StorageManagerTest {
    @Test
    fun storage_manager_imports_and_tracks_local_files() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val storage = AuroraStorageManager(context)

        val tempFile = File(context.cacheDir, "storage-test.mp3")
        tempFile.writeBytes(byteArrayOf(0x49, 0x44, 0x33, 0x00, 0x00))

        val imported = storage.importAudioFile(Uri.fromFile(tempFile))
        assertNotNull(imported)
        assertNotNull(imported?.localUri)
        assertTrue(storage.localCopyExists(imported!!.localFile.name))

        val artUri = storage.storeArtwork(imported.localFile.nameWithoutExtension, Uri.fromFile(tempFile))
        assertNotNull(artUri)
    }
}
