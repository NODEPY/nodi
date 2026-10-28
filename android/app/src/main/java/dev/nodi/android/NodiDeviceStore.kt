package dev.nodi.android

import android.content.Context
import java.io.File

object NodiDeviceStore {
    @Volatile private var instance: DeviceBook? = null
    fun get(context: Context): DeviceBook = instance ?: synchronized(this) {
        instance ?: DeviceBook(File(context.applicationContext.noBackupFilesDir, "nodi-devices.json")).also { instance = it }
    }
}
