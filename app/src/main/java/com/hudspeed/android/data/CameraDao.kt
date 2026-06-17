package com.hudspeed.android.data

import androidx.room.*

@Dao
interface CameraDao {

    @Query("SELECT * FROM cameras WHERE isActive = 1")
    suspend fun getAll(): List<Camera>

    @Query("""
        SELECT * FROM cameras
        WHERE isActive = 1
          AND lat BETWEEN :minLat AND :maxLat
          AND lon BETWEEN :minLon AND :maxLon
    """)
    suspend fun getCamerasInBounds(
        minLat: Double, maxLat: Double,
        minLon: Double, maxLon: Double
    ): List<Camera>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(cameras: List<Camera>)

    @Query("DELETE FROM cameras WHERE updatedAt < :threshold")
    suspend fun deleteOlderThan(threshold: Long)

    @Query("SELECT COUNT(*) FROM cameras")
    suspend fun count(): Int
}
