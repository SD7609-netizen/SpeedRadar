package com.hudspeed.android.data

import android.content.Context
import androidx.room.*

@Database(entities = [Camera::class], version = 1, exportSchema = false)
@TypeConverters(Converters::class)
abstract class CameraDatabase : RoomDatabase() {
    abstract fun cameraDao(): CameraDao

    companion object {
        @Volatile
        private var INSTANCE: CameraDatabase? = null

        fun getInstance(context: Context): CameraDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    CameraDatabase::class.java,
                    "cameras.db"
                ).build().also { INSTANCE = it }
            }
        }

        fun reset() {
            synchronized(this) { INSTANCE = null }
        }
    }
}

class Converters {
    @TypeConverter
    fun fromCameraType(type: CameraType): String = type.name

    @TypeConverter
    fun toCameraType(value: String): CameraType =
        runCatching { CameraType.valueOf(value) }.getOrDefault(CameraType.UNKNOWN)
}
