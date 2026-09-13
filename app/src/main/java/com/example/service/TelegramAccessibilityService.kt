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

    private var currentTelegramChannels: List<String> = emptyList()
    private var currentYoutubeChannels: List<String> = emptyList()
    private var isProtectionEnabled: Boolean = false
    private var isStrictMode: Boolean = true

    override fun onServiceConnected() {
        super.onServiceConnected()
        val database = AppDatabase.getDatabase(applicationContext)
        channelRepository = ChannelRepository(database.channelDao())
        settingsRepository = SettingsRepository(applicationContext)

        scope.launch {
            channelRepository.allChannels.collect { channels ->
                currentTelegramChannels = channels.filter { it.type == "telegram" }.map { it.name.trim().lowercase() }
                currentYoutubeChannels = channels.filter { it.type == "youtube" }.map { it.name.trim().lowercase() }
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
        val isTelegram = packageName == "org.telegram.messenger" || packageName == "org.thunderdog.challegram"
        val isYoutube = packageName == "com.google.android.youtube" || packageName == "com.google.android.apps.youtube.kids"

        if (!isTelegram && !isYoutube) {
            return
        }

        val rootNode = rootInActiveWindow ?: return
        
        if (isTelegram) {
            checkTelegramAndBlock(rootNode)
        } else if (isYoutube) {
            checkYoutubeAndBlock(rootNode)
        }
    }
    
    private fun checkTelegramAndBlock(rootNode: AccessibilityNodeInfo) {
        val texts = mutableListOf<String>()
        extractTexts(rootNode, texts)
        
        val cleanTexts = texts.filter { it.isNotBlank() }.map { it.trim().lowercase() }
        if (cleanTexts.isEmpty()) return
        
        // 1. Identify if we are inside a chat/channel screen
        val isChatScreenById = !rootNode.findAccessibilityNodeInfosByViewId("org.telegram.messenger:id/chat_message_edit").isNullOrEmpty() ||
                               !rootNode.findAccessibilityNodeInfosByViewId("org.telegram.messenger:id/bottom_overlay_chat_text").isNullOrEmpty()
        
        val hasChatInputText = cleanTexts.any { it == "message" || it == "broadcast" || it == "join" || it == "mute" || it == "unmute" || it == "discuss" }
        val hasBackButton = cleanTexts.any { it == "go back" || it == "back" || it.contains("back") }
        
        val isChat = isChatScreenById || (hasChatInputText && hasBackButton)

        if (!isChat) {
            // Not a chat screen (likely main menu, settings, etc.). Allow navigation.
            return
        }

        // 2. We are in a chat. Check if it's an allowed channel.
        val screenTextCombined = cleanTexts.joinToString(" ") { it.replace(Regex("[^a-z0-9]"), "") }
        
        val isAllowed = currentTelegramChannels.any { allowed ->
            val cleanAllowed = allowed.replace(Regex("[^a-z0-9]"), "").trim().lowercase()
            if (cleanAllowed.isEmpty()) return@any false
            screenTextCombined.contains(cleanAllowed)
        }
        
        if (!isAllowed) {
            blockApp()
        }
    }

    private fun checkYoutubeAndBlock(rootNode: AccessibilityNodeInfo) {
        val texts = mutableListOf<String>()
        extractTexts(rootNode, texts)
        
        val cleanTexts = texts.filter { it.isNotBlank() }.map { it.trim().lowercase() }
        if (cleanTexts.isEmpty()) return

        // 1. Check if we are on a YouTube video/channel page.
        // We look for common YouTube UI texts like "subscribe", "subscribers", "views"
        val isVideoOrChannelScreen = cleanTexts.any { it == "subscribe" || it == "subscribed" || it.contains("subscribers") || it.contains("views") }

        if (!isVideoOrChannelScreen) {
            // Probably Home feed or search, we might block everything else if strict,
            // but usually we want to allow navigation and only block videos that are not allowed.
            // Let's allow home screen but wait for a video to be clicked.
            return
        }

        // 2. We are on a video or channel screen. Check if it's an allowed channel.
        val screenTextCombined = cleanTexts.joinToString(" ") { it.replace(Regex("[^a-z0-9]"), "") }

        val isAllowed = currentYoutubeChannels.any { allowed ->
            val cleanAllowed = allowed.replace(Regex("[^a-z0-9]"), "").trim().lowercase()
            if (cleanAllowed.isEmpty()) return@any false
            screenTextCombined.contains(cleanAllowed)
        }

        if (!isAllowed) {
            blockApp()
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
        performGlobalAction(GLOBAL_ACTION_HOME)
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
