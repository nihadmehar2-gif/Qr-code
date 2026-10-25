package com.qrcode.scanner.data

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface ScanHistoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ScanHistoryEntity): Long

    @Update
    suspend fun update(entity: ScanHistoryEntity)

    @Delete
    suspend fun delete(entity: ScanHistoryEntity)

    @Query("DELETE FROM scan_history WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM scan_history")
    suspend fun clearAll()

    @Query("UPDATE scan_history SET is_favorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: Long, favorite: Boolean)

    @Query("SELECT * FROM scan_history ORDER BY scanned_at DESC")
    fun observeAll(): LiveData<List<ScanHistoryEntity>>

    @Query("SELECT * FROM scan_history WHERE is_favorite = 1 ORDER BY scanned_at DESC")
    fun observeFavorites(): LiveData<List<ScanHistoryEntity>>

    @Query("SELECT * FROM scan_history ORDER BY scanned_at DESC")
    suspend fun getAll(): List<ScanHistoryEntity>

    @Query("SELECT * FROM scan_history WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): ScanHistoryEntity?
}
