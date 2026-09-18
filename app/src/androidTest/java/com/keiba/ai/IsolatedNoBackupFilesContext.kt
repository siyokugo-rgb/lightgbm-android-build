package com.keiba.ai

import android.content.Context
import android.content.ContextWrapper
import java.io.File

/**
 * Isolates [Context.getNoBackupFilesDir] to a temporary directory so
 * instrumentation tests never touch or delete production snapshots.
 */
class IsolatedNoBackupFilesContext(
    base: Context,
    private val isolatedNoBackupDir: File
) : ContextWrapper(base) {

    override fun getNoBackupFilesDir(): File {
        if (
            !isolatedNoBackupDir.exists() &&
            !isolatedNoBackupDir.mkdirs()
        ) {
            error(
                "could not create isolated noBackupFilesDir: " +
                    isolatedNoBackupDir.absolutePath
            )
        }

        require(isolatedNoBackupDir.isDirectory) {
            "isolated noBackupFilesDir is not a directory"
        }

        return isolatedNoBackupDir
    }
}
