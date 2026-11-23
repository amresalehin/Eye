package com.amresalehin.emregallery.data

import android.os.SystemClock
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.lang.ref.WeakReference
import java.net.URI
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Non-owning access to the browser tab already displayed by DirectBrowserView.
 *
 * The registry never changes WebView clients, JavaScript interfaces, settings, navigation,
 * attachment, or lifetime. It only requests a DOM scan and observes media that the browser
 * screen has already discovered. A separate extractor WebView remains the fallback when the
 * requested page is not the page currently open in the browser.
 */
object LiveBrowserWebViewRegistry {
    private const val EMPTY_RESULT_IDLE_MS = 4_500L
    private const val RESULT_IDLE_MS = 2_200L
    private const val MAX_CAPTURE_WAIT_MS = 12_000L

    private data class Observer(
        val pageKey: String,
        val onItem: (ScrapedMediaItem) -> Unit
    )

    private class Session(
        val token: Any,
        webView: WebView,
        val currentUrl: () -> String,
        val pageTitle: () -> String,
        val requestScan: () -> Unit
    ) {
        val webView = WeakReference(webView)
        val observers = CopyOnWriteArrayList<Observer>()
    }

    private val lock = Any()
    private var activeSession: Session? = null

    class Registration internal constructor(
        private val token: Any
    ) : AutoCloseable {
        @Volatile
        private var closed = false

        override fun close() {
            if (closed) return
            closed = true
            LiveBrowserWebViewRegistry.unregister(token)
        }
    }

    /**
     * Register the current user-facing WebView. Registering does not transfer ownership.
     * The caller must close the returned registration before destroying or replacing the view.
     */
    fun register(
        webView: WebView,
        currentUrl: () -> String,
        pageTitle: () -> String,
        requestScan: () -> Unit
    ): Registration {
        val token = Any()
        val session = Session(
            token = token,
            webView = webView,
            currentUrl = currentUrl,
            pageTitle = pageTitle,
            requestScan = requestScan
        )
        synchronized(lock) {
            activeSession = session
        }
        return Registration(token)
    }

    fun unregister(webView: WebView) {
        synchronized(lock) {
            if (activeSession?.webView?.get() === webView) {
                activeSession = null
            }
        }
    }

    private fun unregister(token: Any) {
        synchronized(lock) {
            if (activeSession?.token === token) {
                activeSession = null
            }
        }
    }

    /**
     * Forward items produced by the visible browser's existing network/DOM/Blob capture path.
     */
    fun publish(
        webView: WebView?,
        pageUrl: String,
        items: List<ScrapedMediaItem>
    ) {
        if (webView == null || items.isEmpty()) return
        val session = synchronized(lock) { activeSession } ?: return
        if (session.webView.get() !== webView) return
        val pageKey = pageKey(pageUrl) ?: return
        if (pageKey(session.currentUrl()) != pageKey) return

        session.observers.forEach { observer ->
            if (observer.pageKey == pageKey) {
                items.forEach { item ->
                    runCatching { observer.onItem(item) }
                }
            }
        }
    }

    /**
     * Captures from the matching visible browser tab without taking control of it.
     *
     * Returns null only when no live browser tab is displaying [targetUrl], allowing the
     * caller to fall back to a dedicated extraction WebView. An empty result means the live
     * tab was reused successfully but no media was found during the bounded observation.
     */
    suspend fun captureIfAvailable(
        targetUrl: String,
        onItemDiscovered: (ScrapedMediaItem) -> Unit,
        onStatusUpdate: (String) -> Unit
    ): MediaExtractionResult? {
        val targetPageKey = pageKey(targetUrl) ?: return null
        val session = withContext(Dispatchers.Main.immediate) {
            findMatchingSession(targetPageKey)
        } ?: return null
        val webView = session.webView.get() ?: return null

        val discoveredByUrl = LinkedHashMap<String, ScrapedMediaItem>()
        val events = Channel<Unit>(Channel.CONFLATED)
        val observer = Observer(targetPageKey) { item ->
            val key = MediaClassifier.canonicalKey(item.url)
            val accepted = synchronized(discoveredByUrl) {
                val existing = discoveredByUrl[key]
                when {
                    existing == null -> {
                        discoveredByUrl[key] = item
                        item
                    }
                    else -> {
                        val merged = MediaClassifier.mergeScrapedMediaItems(existing, item)
                        if (merged == existing) null else {
                            discoveredByUrl[key] = merged
                            merged
                        }
                    }
                }
            }
            accepted?.let {
                onItemDiscovered(it)
                events.trySend(Unit)
            }
        }

        session.observers.add(observer)
        try {
            val scanRequested = withContext(Dispatchers.Main.immediate) {
                val stillCurrent = synchronized(lock) { activeSession === session }
                if (!stillCurrent ||
                    session.webView.get() !== webView ||
                    pageKey(session.currentUrl()) != targetPageKey ||
                    pageKey(webView.url.orEmpty()) != targetPageKey
                ) {
                    false
                } else {
                    onStatusUpdate("Reusing the currently open browser tab…")
                    runCatching { session.requestScan() }.isSuccess
                }
            }

            if (!scanRequested) return null

            val startedAt = SystemClock.elapsedRealtime()
            while (true) {
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                val remaining = MAX_CAPTURE_WAIT_MS - elapsed
                if (remaining <= 0L) break
                if (!synchronized(lock) { activeSession === session }) break

                val hasItems = synchronized(discoveredByUrl) { discoveredByUrl.isNotEmpty() }
                val idleWindow = if (hasItems) RESULT_IDLE_MS else EMPTY_RESULT_IDLE_MS
                val signalled = withTimeoutOrNull(minOf(idleWindow, remaining)) {
                    events.receive()
                    true
                } ?: false
                if (!signalled) break
            }

            val results = synchronized(discoveredByUrl) {
                discoveredByUrl.values.toList()
            }
            return MediaExtractionResult(
                items = results,
                pageTitle = session.pageTitle().takeIf { it.isNotBlank() }
            )
        } finally {
            session.observers.remove(observer)
            events.close()
        }
    }

    private fun findMatchingSession(targetPageKey: String): Session? {
        val session = synchronized(lock) { activeSession } ?: return null
        val webView = session.webView.get()
        if (webView == null) {
            synchronized(lock) {
                if (activeSession === session) activeSession = null
            }
            return null
        }

        val registeredUrl = session.currentUrl()
        val actualUrl = webView.url.orEmpty()
        return session.takeIf {
            pageKey(registeredUrl) == targetPageKey &&
                pageKey(actualUrl) == targetPageKey
        }
    }

    /**
     * Match the same document, ignoring only the URL fragment. Query strings remain significant.
     */
    internal fun pageKey(rawUrl: String): String? {
        val uri = runCatching { URI(rawUrl.trim()) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        return runCatching {
            URI(
                scheme,
                uri.userInfo,
                host,
                uri.port,
                uri.path.ifEmpty { "/" },
                uri.query,
                null
            ).normalize().toASCIIString()
        }.getOrNull()
    }
}
