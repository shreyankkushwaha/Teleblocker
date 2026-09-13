package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.example.ui.AppNavigation
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
  private val viewModel: MainViewModel by viewModels()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    
    handleIntent(intent)

    setContent {
      MyApplicationTheme {
        AppNavigation(viewModel = viewModel)
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    handleIntent(intent)
  }

  private fun handleIntent(intent: Intent) {
    if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
      val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
      if (sharedText != null) {
        val channelName = extractChannelName(sharedText)
        if (channelName.isNotEmpty()) {
          viewModel.addChannel(channelName)
        }
      }
    }
  }

  private fun extractChannelName(text: String): String {
    val tMeRegex = "t\\.me/([^/\\s]+)".toRegex()
    val match = tMeRegex.find(text)
    if (match != null) {
      return "@" + match.groupValues[1]
    }
    
    if (text.contains("@")) {
      val atRegex = "@([^\\s]+)".toRegex()
      val atMatch = atRegex.find(text)
      if (atMatch != null) {
        return "@" + atMatch.groupValues[1]
      }
    }
    
    return text.trim()
  }
}
