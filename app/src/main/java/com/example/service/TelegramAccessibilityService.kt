package com.example.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.data.AppDatabase
import com.example.data.ChannelRepository
import com.example.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.example.ui.BlockActivity

class TelegramAccessibilityService : AccessibilityService() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    private lateinit var channelRepository: ChannelRepository
    private lateinit var settingsRepository: SettingsRepository

    private var currentAllowedChannels: List<String> = emptyList()
    private var isProtectionEnabled: Boolean = false
    private var isStrictMode: Boolean = true

    override fun onServiceConnected() {
        super.onServiceConnected()
        val database = AppDatabase.getDatabase(applicationContext)
        channelRepository = ChannelRepository(database.channelDao())
        settingsRepository = SettingsRepository(applicationContext)

        scope.launch {
            channelRepository.allChannels.collect { channels ->
                currentAllowedChannels = channels.map { it.name.trim().lowercase() }
            }
        }
        scope.launch {
            settingsRepository.isProtectionEnabled.collect { enabled ->
                isProtectionEnabled = enabled
            }
        }
        scope.launch {
            settingsRepository.isStrictMode.collect { enabled ->
                isStrictMode = enabled
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isProtectionEnabled || event == null) return

        val packageName = event.packageName?.toString() ?: return
        if (packageName != "org.telegram.messenger" && packageName != "org.thunderdog.challegram") {
            return
        }

        val rootNode = rootInActiveWindow ?: return
        checkNodeAndBlock(rootNode)
    }
    
    private fun checkNodeAndBlock(rootNode: AccessibilityNodeInfo) {
        val titleText = findChatTitle(rootNode)
        val isChatScreen = isChatScreen(rootNode)
        
        if (titleText != null) {
            val titleLower = titleText.trim().lowercase()
            
            // Ignore the main app title which might just be "Telegram" or "Chats"
            if (titleLower == "telegram" || titleLower == "chats") {
                return 
            }
            
            val isAllowed = currentAllowedChannels.any { allowed ->
                val cleanAllowed = allowed.replace("@", "").trim().lowercase()
                titleLower == cleanAllowed || titleLower.contains(cleanAllowed)
            }
            
            if (!isAllowed) {
                // Positively identified a non-allowed channel
                blockApp()
            }
        } else {
            // Couldn't positively identify channel.
            if (isStrictMode) {
                // In strict mode, if we are in a chat view but can't identify the title, block it.
                // We shouldn't block everything, or the user can't navigate to the allowed channels.
                if (isChatScreen) {
                    blockApp()
                }
            }
        }
    }
    
    private fun findChatTitle(node: AccessibilityNodeInfo): String? {
        val titleNodes = node.findAccessibilityNodeInfosByViewId("org.telegram.messenger:id/action_bar_title")
        if (!titleNodes.isNullOrEmpty()) {
            return titleNodes[0].text?.toString()
        }
        return null
    }

    private fun isChatScreen(node: AccessibilityNodeInfo): Boolean {
        // Look for the message input box, which indicates we are in a chat screen.
        val inputNodes = node.findAccessibilityNodeInfosByViewId("org.telegram.messenger:id/chat_message_edit")
        return !inputNodes.isNullOrEmpty()
    }
    
    private fun blockApp() {
        val intent = Intent(this, BlockActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(intent)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
    }
}
