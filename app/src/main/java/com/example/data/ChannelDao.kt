package com.example.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ChannelDao {
    @Query("SELECT * FROM allowed_channels ORDER BY name ASC")
    fun getAllChannels(): Flow<List<AllowedChannel>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChannel(channel: AllowedChannel)

    @Delete
    suspend fun deleteChannel(channel: AllowedChannel)
}
