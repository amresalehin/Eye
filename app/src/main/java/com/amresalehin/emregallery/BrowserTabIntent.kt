package com.amresalehin.emregallery

import android.content.Context
import android.content.Intent

/**
 * Handoff contract for opening a URL as a tab in the app's primary browser.
 * Secondary activities must not own browser tabs that disappear when they finish.
 */
object BrowserTabIntent {
    const val ACTION_OPEN_TAB = "com.amresalehin.emregallery.action.OPEN_BROWSER_TAB"
    const val EXTRA_URL = "com.amresalehin.emregallery.extra.BROWSER_TAB_URL"

    fun create(context: Context, url: String): Intent =
        Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_TAB
            putExtra(EXTRA_URL, url.trim())
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
}

data class BrowserTabOpenRequest(
    val requestId: Long,
    val url: String
)
