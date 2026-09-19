package com.aurora.app.import

import android.content.Context
import android.content.Intent
import android.net.Uri

data object FilePickerIntentFactory {
    fun openAudioPicker(multiple: Boolean = true): Intent {
        return Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/*"))
            putExtra(Intent.EXTRA_LOCAL_ONLY, true)
        }
    }

    fun persistReadPermission(context: Context, uri: Uri, flags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION) {
        try {
            context.contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: SecurityException) {
            // Some URIs are not persistable; the import layer will still behave safely without it.
        }
    }
}
