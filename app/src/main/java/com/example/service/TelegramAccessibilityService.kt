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

        // 1. Check if we are actually on a YouTube video/channel page.
        // The Home feed often has "views" (e.g., "1M views"), so we avoid using that alone.
        // Instead, we look for player-specific or channel-specific buttons/labels.
        val playerIndicators = listOf("pause video", "play video", "enter fullscreen", "exit fullscreen", "collapse")
        val channelIndicators = listOf("subscribe", "subscribed") // Typically explicit buttons on channel/video pages

        val isVideoScreen = !rootNode.findAccessibilityNodeInfosByViewId("com.google.android.youtube:id/player_view").isNullOrEmpty() ||
                            !rootNode.findAccessibilityNodeInfosByViewId("com.google.android.youtube:id/watch_player").isNullOrEmpty() ||
                            cleanTexts.any { text -> playerIndicators.any { indicator -> text == indicator } }

        val isChannelScreen = cleanTexts.any { text -> channelIndicators.any { indicator -> text == indicator } }

        if (!isVideoScreen && !isChannelScreen) {
            // Likely on the Home feed, search results, or Library. Allow navigation.
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
        // Pressing BACK exits the video player before switching apps,
        // which prevents YouTube from entering Picture-in-Picture (PiP) mode.
        performGlobalAction(GLOBAL_ACTION_BACK)
        performGlobalAction(GLOBAL_ACTION_BACK)

        scope.launch {
            // Slight delay to allow the BACK actions to be processed by the system
            // before we transition to the BlockActivity.
            kotlinx.coroutines.delay(300)
            val intent = Intent(this@TelegramAccessibilityService, BlockActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            startActivity(intent)
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
    }
}
