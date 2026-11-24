package com.amresalehin.emregallery.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.URL
import java.util.concurrent.atomic.AtomicLong

/**
 * Single source of truth for downloader sessions across:
 * - DownloaderResultsPage
 * - FullScreenBrowserView
 * - DownloaderSearchMainPage
 * - ShareReceiverActivity
 *
 * Media candidates are merged by a background actor so browser callbacks never
 * hold a shared monitor while canonicalizing URLs or merging media metadata.
 */
object DownloaderSession {

    private sealed class SessionCommand {
        data class Reset(val epoch: Long) : SessionCommand()
        data class Merge(
            val epoch: Long,
            val items: List<ScrapedMediaItem>,
            val removedCanonicalKeys: Set<String> = emptySet(),
            val title: String? = null
        ) : SessionCommand()
    }

    private val _currentUrl = MutableStateFlow("")
    val currentUrl: StateFlow<String> = _currentUrl.asStateFlow()

    private val _sourceDomain = MutableStateFlow("")
    val sourceDomain: StateFlow<String> = _sourceDomain.asStateFlow()

    private val _pageTitle = MutableStateFlow("")
    val pageTitle: StateFlow<String> = _pageTitle.asStateFlow()

    private val _discoveredItems = MutableStateFlow<List<ScrapedMediaItem>>(emptyList())
    val discoveredItems: StateFlow<List<ScrapedMediaItem>> = _discoveredItems.asStateFlow()

    private val _selectedItemIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedItemIds: StateFlow<Set<String>> = _selectedItemIds.asStateFlow()

    private val _isExtracting = MutableStateFlow(false)
    val isExtracting: StateFlow<Boolean> = _isExtracting.asStateFlow()

    private val _statusMessage = MutableStateFlow("")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // The actor is the sole owner of canonicalMap. epochLock protects only short
    // enqueue/publication operations; media merging never runs while it is held.
    private val epochLock = Any()
    private val sessionEpoch = AtomicLong(0L)
    private val canonicalMap = LinkedHashMap<String, ScrapedMediaItem>()
    private val commands = Channel<SessionCommand>(Channel.UNLIMITED)
    private val commandScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        commandScope.launch {
            var pending: SessionCommand? = null
            while (true) {
                val command = pending ?: commands.receive()
                pending = null

                when (command) {
                    is SessionCommand.Reset -> {
                        if (sessionEpoch.get() == command.epoch) canonicalMap.clear()
                    }

                    is SessionCommand.Merge -> {
                        val batch = ArrayList<SessionCommand.Merge>(16)
                        batch.add(command)

                        // Coalesce adjacent updates from the same page into one merge
                        // and one StateFlow snapshot. Reset commands remain barriers.
                        while (true) {
                            val queued = commands.tryReceive().getOrNull() ?: break
                            if (queued is SessionCommand.Merge && queued.epoch == command.epoch) {
                                batch.add(queued)
                            } else {
                                pending = queued
                                break
                            }
                        }
                        applyMergeBatch(batch)
                    }
                }
            }
        }
    }

    private fun applyMergeBatch(batch: List<SessionCommand.Merge>) {
        if (batch.isEmpty()) return
        val epoch = batch.first().epoch
        if (sessionEpoch.get() != epoch) return

        var changed = false
        var firstUsableTitle: String? = null
        val removedItemIds = LinkedHashSet<String>()

        for (command in batch) {
            if (sessionEpoch.get() != epoch) return

            for (key in command.removedCanonicalKeys) {
                val removed = canonicalMap.remove(key)
                if (removed != null) {
                    removedItemIds.add(removed.id)
                    changed = true
                }
            }

            for (item in command.items) {
                val key = MediaClassifier.canonicalKey(item.url)
                val existing = canonicalMap[key]
                if (existing == null) {
                    canonicalMap[key] = item
                    changed = true
                } else {
                    val merged = MediaClassifier.mergeScrapedMediaItems(existing, item)
                    if (merged != existing) {
                        canonicalMap[key] = merged
                        changed = true
                    }
                }
            }

            if (firstUsableTitle.isNullOrBlank() && !command.title.isNullOrBlank()) {
                firstUsableTitle = command.title
            }
        }

        val snapshot = if (changed) canonicalMap.values.toList() else null
        synchronized(epochLock) {
            // Navigation can occur while this background batch is being merged.
            if (sessionEpoch.get() != epoch) return@synchronized

            if (snapshot != null) {
                _discoveredItems.value = snapshot
                _selectedItemIds.update { selected ->
                    val retained = selected - removedItemIds
                    if (retained.isEmpty()) snapshot.map { it.id }.toSet() else retained
                }
            }

            if (!firstUsableTitle.isNullOrBlank() && _pageTitle.value == _sourceDomain.value) {
                _pageTitle.value = firstUsableTitle
            }
        }
    }

    fun startSession(url: String) {
        val trimmed = url.trim()
        val normalized = if (!trimmed.startsWith("http://", true) && !trimmed.startsWith("https://", true)) {
            "https://$trimmed"
        } else trimmed
        val domain = runCatching { URL(normalized).host }.getOrDefault("")

        synchronized(epochLock) {
            val epoch = sessionEpoch.incrementAndGet()
            _currentUrl.value = normalized
            _sourceDomain.value = domain
            _pageTitle.value = domain
            _discoveredItems.value = emptyList()
            _selectedItemIds.value = emptySet()
            _isExtracting.value = true
            _statusMessage.value = "Starting extraction..."
            _errorMessage.value = null
            commands.trySend(SessionCommand.Reset(epoch))
        }
    }

    /**
     * Queue a discovery batch without performing URL normalization or metadata merging
     * on the caller thread. Existing call sites remain source-compatible.
     */
    fun addDiscoveredItems(newItems: List<ScrapedMediaItem>, title: String? = null) {
        enqueueMerge(newItems, emptySet(), title)
    }

    /**
     * Apply upserts and removals atomically within one ordered session command.
     * Removal keys must be generated with MediaClassifier.canonicalKey.
     */
    fun applyDiscoveredDelta(
        upserts: List<ScrapedMediaItem>,
        removedCanonicalKeys: Set<String>
    ) {
        enqueueMerge(upserts, removedCanonicalKeys, null)
    }

    private fun enqueueMerge(
        items: List<ScrapedMediaItem>,
        removedCanonicalKeys: Set<String>,
        title: String?
    ) {
        if (items.isEmpty() && removedCanonicalKeys.isEmpty() && title.isNullOrBlank()) return

        val stableItems = items.toList()
        val stableRemovals = removedCanonicalKeys.toSet()
        synchronized(epochLock) {
            commands.trySend(
                SessionCommand.Merge(
                    epoch = sessionEpoch.get(),
                    items = stableItems,
                    removedCanonicalKeys = stableRemovals,
                    title = title
                )
            )
        }
    }

    fun addDiscoveredItem(item: ScrapedMediaItem) {
        addDiscoveredItems(listOf(item))
    }

    fun updateStatus(message: String, isRunning: Boolean? = null) {
        _statusMessage.value = message
        if (isRunning != null) _isExtracting.value = isRunning
    }

    fun setStatusMessage(msg: String) {
        updateStatus(msg)
    }

    fun finishSession(message: String? = null, error: String? = null) {
        _isExtracting.value = false
        if (message != null) _statusMessage.value = message
        _errorMessage.value = error
    }

    fun setPageTitle(title: String) {
        if (title.isNotBlank()) _pageTitle.value = title
    }

    fun toggleSelection(itemId: String) {
        _selectedItemIds.update { current ->
            if (itemId in current) current - itemId else current + itemId
        }
    }

    fun selectAll(ids: Set<String>) {
        _selectedItemIds.value = ids
    }

    fun deselectAll() {
        _selectedItemIds.value = emptySet()
    }
}
