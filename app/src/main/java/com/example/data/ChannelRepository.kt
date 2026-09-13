package com.example.data

import kotlinx.coroutines.flow.Flow

class ChannelRepository(private val channelDao: ChannelDao) {
    val allChannels: Flow<List<AllowedChannel>> = channelDao.getAllChannels()

    suspend fun insertChannel(channel: AllowedChannel) {
        channelDao.insertChannel(channel)
    }

    suspend fun deleteChannel(channel: AllowedChannel) {
        channelDao.deleteChannel(channel)
    }
}
