package com.hudspeed.android.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "cameras")
data class Camera(
    @PrimaryKey val id: Long,
    val lat: Double,
    val lon: Double,
    val direction: Float,       // направление в градусах (0-360), -1 = обе стороны
    val maxSpeed: Int,          // ограничение скорости (0 = неизвестно)
    val type: CameraType,
    val isActive: Boolean = true,
    val updatedAt: Long = System.currentTimeMillis()
)

enum class CameraType {
    SPEED,          // камера скорости
    RED_LIGHT,      // камера светофора
    AVERAGE_SPEED,  // средняя скорость (начало)
    AVERAGE_SPEED_END, // средняя скорость (конец)
    UNKNOWN
}
