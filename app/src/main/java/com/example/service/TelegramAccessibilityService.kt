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
        val texts = mutableListOf<String>()
        extractTexts(rootNode, texts)
        
        val cleanTexts = texts.filter { it.isNotBlank() }.map { it.trim().lowercase() }
        if (cleanTexts.isEmpty()) return
        
        // Find the likely title based on standard Android Action Bar patterns.
        // Usually, the back button is the first item, and the title is next.
        var titleIndex = 0
        if (cleanTexts.isNotEmpty() && (cleanTexts[0] == "go back" || cleanTexts[0] == "back" || cleanTexts[0].contains("back"))) {
            titleIndex = 1
        }
        
        val likelyTitle = if (cleanTexts.size > titleIndex) cleanTexts[titleIndex] else ""
        
        // Allow main screens
        if (likelyTitle == "telegram" || likelyTitle == "chats" || likelyTitle == "settings" || likelyTitle == "contacts" || likelyTitle == "edit" || likelyTitle.contains("navigation")) {
            return
        }
        
        // Check if the likely title matches an allowed channel
        val isAllowed = currentAllowedChannels.any { allowed ->
            val cleanAllowed = allowed.replace("@", "").trim().lowercase()
            likelyTitle == cleanAllowed || likelyTitle.contains(cleanAllowed) ||
            cleanTexts.take(3).any { it.contains(cleanAllowed) } // Fallback check in top texts
        }
        
        // Identify if it's a chat screen (chat screens have "message", "mute", "unmute", "broadcast", "join" or back button)
        val hasChatIndicators = cleanTexts.any { it == "message" || it == "mute" || it == "unmute" || it == "join" || it == "broadcast" } || titleIndex == 1
        
        if (!isAllowed) {
            if (isStrictMode) {
                // In strict mode, if we suspect it's a chat screen and not allowed, block it.
                if (hasChatIndicators && likelyTitle.isNotEmpty()) {
                    blockApp()
                }
            } else {
                // In normal mode, only block if we are very confident it's a non-allowed chat.
                if (hasChatIndicators && likelyTitle.isNotEmpty()) {
                    blockApp()
                }
            }
        }
    }
    
    private fun extractTexts(node: AccessibilityNodeInfo, list: MutableList<String>) {
        if (node.text != null && node.text.isNotBlank()) {
            list.add(node.text.toString())
        } else if (node.contentDescription != null && node.contentDescription.isNotBlank()) {
            list.add(node.contentDescription.toString())
        }
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                extractTexts(child, list)
                child.recycle()
            }
        }
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
