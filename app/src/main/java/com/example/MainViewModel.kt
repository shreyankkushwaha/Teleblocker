package com.example

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AllowedChannel
import com.example.data.AppDatabase
import com.example.data.ChannelRepository
import com.example.data.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val database = AppDatabase.getDatabase(application)
    private val channelRepository = ChannelRepository(database.channelDao())
    private val settingsRepository = SettingsRepository(application)

    val allChannels: StateFlow<List<AllowedChannel>> = channelRepository.allChannels
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val isProtectionEnabled: StateFlow<Boolean> = settingsRepository.isProtectionEnabled
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    val isStrictMode: StateFlow<Boolean> = settingsRepository.isStrictMode
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = true
        )

    val customBlockMessage: StateFlow<String> = settingsRepository.customBlockMessage
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = "This Telegram channel is not on the allowed list."
        )

    fun addChannel(name: String) {
        val cleanName = name.trim()
        if (cleanName.isNotEmpty()) {
            viewModelScope.launch {
                channelRepository.insertChannel(AllowedChannel(name = cleanName))
            }
        }
    }

    fun removeChannel(channel: AllowedChannel) {
        viewModelScope.launch {
            channelRepository.deleteChannel(channel)
        }
    }

    fun setProtectionEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setProtectionEnabled(enabled)
        }
    }

    fun setStrictMode(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setStrictMode(enabled)
        }
    }

    fun setCustomBlockMessage(message: String) {
        viewModelScope.launch {
            settingsRepository.setCustomBlockMessage(message)
        }
    }
}
