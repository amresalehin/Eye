package com.amresalehin.emregallery.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.amresalehin.emregallery.data.*
import com.amresalehin.emregallery.data.preferences.DownloaderPreferencesManager
import com.amresalehin.emregallery.data.preferences.DownloaderSubfolderMode
import com.amresalehin.emregallery.data.preferences.DownloaderVideoQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.URL
import java.util.Locale

/**
 * Recognized media platforms with custom brand color and iconography.
 */
enum class DetectedPlatform(
    val title: String,
    val icon: ImageVector,
    val badgeColor: Color,
    val onBadgeColor: Color,
    val description: String,
    val defaultUrl: String
) {
    YOUTUBE("YouTube", Icons.Default.PlayCircle, Color(0xFFE53935), Color.White, "Videos, Shorts & Audio", "https://youtube.com"),
    INSTAGRAM("Instagram", Icons.Default.CameraAlt, Color(0xFFE1306C), Color.White, "Reels, Posts & Stories", "https://instagram.com"),
    TIKTOK("TikTok", Icons.Default.Audiotrack, Color(0xFF010101), Color.White, "No-watermark Clips", "https://tiktok.com"),
    TWITTER("X / Twitter", Icons.Default.AlternateEmail, Color(0xFF1DA1F2), Color.White, "Videos, GIFs & Media", "https://x.com"),
    REDDIT("Reddit", Icons.Default.Forum, Color(0xFFFF4500), Color.White, "Posts & Video Clips", "https://reddit.com"),
    PINTEREST("Pinterest", Icons.Default.PushPin, Color(0xFFBD081C), Color.White, "Full-res Pins & Boards", "https://pinterest.com"),
    FACEBOOK("Facebook", Icons.Default.VideoLibrary, Color(0xFF1877F2), Color.White, "Public Reels & Videos", "https://facebook.com"),
    DIRECT_MEDIA("Direct Media", Icons.Default.Download, Color(0xFF2E7D32), Color.White, "Raw MP4, WEBM or JPG", ""),
    GENERIC_WEB("Any Website", Icons.Default.Language, Color(0xFF455A64), Color.White, "Deep Scrape All Media", "")
}

fun detectPlatform(url: String): DetectedPlatform {
    val lower = url.trim().lowercase(Locale.ROOT)
    return when {
        lower.contains("youtube.com") || lower.contains("youtu.be") -> DetectedPlatform.YOUTUBE
        lower.contains("instagram.com") -> DetectedPlatform.INSTAGRAM
        lower.contains("tiktok.com") -> DetectedPlatform.TIKTOK
        lower.contains("twitter.com") || lower.contains("x.com") -> DetectedPlatform.TWITTER
        lower.contains("reddit.com") || lower.contains("redd.it") -> DetectedPlatform.REDDIT
        lower.contains("pinterest.com") || lower.contains("pin.it") -> DetectedPlatform.PINTEREST
        lower.contains("facebook.com") || lower.contains("fb.watch") -> DetectedPlatform.FACEBOOK
        lower.endsWith(".mp4") || lower.endsWith(".webm") || lower.endsWith(".m3u8") ||
                lower.endsWith(".mpd") || lower.endsWith(".jpg") || lower.endsWith(".png") ||
                lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".mp3") -> DetectedPlatform.DIRECT_MEDIA
        else -> DetectedPlatform.GENERIC_WEB
    }
}

/**
 * Minimal Emre-style Search & URL Fetch Page for the Media Downloader.
 * Clean monochromatic layout with dynamic capsule search input and compact
 * site favicon pills based on user history.
 */
data class DownloaderPill(
    val domain: String,
    val name: String,
    val url: String,
    val explicitFaviconUrl: String? = null
) {
    val faviconUrl: String
        get() = explicitFaviconUrl ?: "https://www.google.com/s2/favicons?domain=${domain.removePrefix("www.")}&sz=64"

    val initial: String
        get() = name.firstOrNull()?.uppercaseChar()?.toString() ?: "W"
}

@Composable
fun DownloaderSearchMainPage(
    onFetchUrl: (String) -> Unit,
    onOpenBrowser: (String) -> Unit,
    onViewSavedLibrary: () -> Unit,
    onOpenActivity: () -> Unit = {},
    onBack: () -> Unit = {},
    savedStacks: List<MediaInboxStack> = emptyList(),
    onSelectStack: (MediaInboxStack) -> Unit = {},
    activeDownloadsCount: Int = 0,
    savedStacksCount: Int = 0,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val historyManager = remember { BrowserHistoryManager(context) }
    val allHistorySites by historyManager.allSites.collectAsState(initial = emptyList())
    val downloaderPrefs = remember { DownloaderPreferencesManager(context) }
    val searchEnginePref by downloaderPrefs.searchEngine.collectAsState(initial = DownloaderPreferencesManager.getSearchEngineSync(context))
    var showDownloaderSettings by remember { mutableStateOf(false) }
    val inboxRepo = remember { MediaInboxRepository.getInstance(context) }
    val repoStacks by inboxRepo.stacks.collectAsState()
    val effectiveStacks = remember(savedStacks, repoStacks) {
        if (savedStacks.isNotEmpty()) savedStacks else repoStacks
    }

    val activities by DownloadActivityManager.activities.collectAsState()
    LaunchedEffect(Unit) {
        DownloadActivityManager.initialize(context)
        DownloadActivityManager.syncWithInboxRepository()
    }

    var urlInput by remember { mutableStateOf("") }
    var isSearchFocused by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    fun submitSearch(target: String) {
        val trimmed = target.trim()
        if (trimmed.isBlank()) return
        keyboardController?.hide()
        focusManager.clearFocus()
        val normalized = if (!trimmed.startsWith("http://", true) && !trimmed.startsWith("https://", true)) {
            "https://$trimmed"
        } else {
            trimmed
        }
        scope.launch {
            historyManager.recordVisit(normalized)
        }
        onFetchUrl(normalized)
    }

    fun openInBrowser(queryOrUrl: String) {
        val trimmed = queryOrUrl.trim()
        if (trimmed.isBlank()) return
        keyboardController?.hide()
        focusManager.clearFocus()
        val destinationUrl = MediaClassifier.formatQueryOrUrl(trimmed, searchEnginePref)
        scope.launch {
            historyManager.recordVisit(destinationUrl)
        }
        onOpenBrowser(destinationUrl)
    }

    // Dynamic pills derived solely from user visit/saved history - no hardcoded sites
    val pills = remember(allHistorySites) {
        val seenDomains = mutableSetOf<String>()
        val result = mutableListOf<DownloaderPill>()
        for (site in allHistorySites) {
            val domain = site.domain.removePrefix("www.").lowercase().trim()
            if (domain.isNotBlank() && seenDomains.add(domain)) {
                val displayName = site.title.takeIf { it.isNotBlank() && it != "about:blank" && !it.startsWith("http") }
                    ?: domain.substringBefore('.').replaceFirstChar { it.uppercase() }
                result.add(
                    DownloaderPill(
                        domain = domain,
                        name = displayName,
                        url = site.url
                    )
                )
            }
        }
        result.take(12)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("downloader_search_main_page"),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. One Unified Search Bar on Main Page (Back button & Switcher pills inside search bar, link icon removed)
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .testTag("downloader_main_search_card")
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Back Button inside search bar (exits search edit if focused/query present, or navigates back)
                IconButton(
                    onClick = {
                        if (isSearchFocused || urlInput.isNotEmpty()) {
                            urlInput = ""
                            isSearchFocused = false
                            focusManager.clearFocus()
                            keyboardController?.hide()
                        } else {
                            onBack()
                        }
                    },
                    modifier = Modifier
                        .size(40.dp)
                        .testTag("downloader_main_back_btn")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }

                // Switcher Pill & Divider: hidden when search field is focused
                if (!isSearchFocused) {
                    Spacer(Modifier.width(2.dp))

                    // Switcher Pill: download icon only and folder icon only
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Fetch Pill (Active on main search page)
                            Surface(
                                onClick = { /* already on search page */ },
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .size(width = 34.dp, height = 30.dp)
                                    .testTag("tab_search_fetch")
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.ArrowDownward,
                                        contentDescription = "Fetch",
                                        tint = MaterialTheme.colorScheme.surface,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }

                            // Saved Pill (Inactive on main search page, switches to Saved Library)
                            Surface(
                                onClick = onViewSavedLibrary,
                                shape = RoundedCornerShape(14.dp),
                                color = Color.Transparent,
                                modifier = Modifier
                                    .size(width = 34.dp, height = 30.dp)
                                    .testTag("tab_saved_library")
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Folder,
                                        contentDescription = "Saved",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.width(8.dp))

                    // Divider between switcher and search field
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(18.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    )
                }

                Spacer(Modifier.width(8.dp))

                // Search / URL input field (Link icon removed!)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (urlInput.isEmpty()) {
                        Text(
                            text = "Paste link or enter URL...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    BasicTextField(
                        value = urlInput,
                        onValueChange = { urlInput = it },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Go
                        ),
                        keyboardActions = KeyboardActions(
                            onGo = {
                                if (urlInput.isNotBlank()) {
                                    submitSearch(urlInput)
                                }
                            }
                        ),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 15.sp
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { focusState ->
                                isSearchFocused = focusState.isFocused
                            }
                            .testTag("downloader_main_url_input")
                    )
                }

                Spacer(Modifier.width(6.dp))

                // Dynamic Action Controls: Paste icon when empty; Download + Browse + Clear when query present
                AnimatedContent(
                    targetState = urlInput.isNotBlank(),
                    transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(150)) },
                    label = "search_bar_actions"
                ) { hasQuery ->
                    if (hasQuery) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            // Clear input button
                            IconButton(
                                onClick = { urlInput = "" },
                                modifier = Modifier
                                    .size(32.dp)
                                    .testTag("downloader_clear_btn")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Clear",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                            }

                            // Browse button (opens in-app browser with query / url)
                            IconButton(
                                onClick = { openInBrowser(urlInput) },
                                modifier = Modifier
                                    .size(34.dp)
                                    .testTag("downloader_action_browse_btn")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.TravelExplore,
                                    contentDescription = "Browse web with search query",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            // Download button (opens downloader page) - clean and transparent
                            IconButton(
                                onClick = { submitSearch(urlInput) },
                                modifier = Modifier
                                    .size(34.dp)
                                    .testTag("downloader_action_download_btn")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ArrowDownward,
                                    contentDescription = "Download media from URL",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    } else {
                        // Paste & Downloader Settings buttons when empty
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            IconButton(
                                onClick = {
                                    val clipText = clipboardManager.getText()?.text?.trim().orEmpty()
                                    if (clipText.isNotBlank()) {
                                        urlInput = clipText
                                        submitSearch(clipText)
                                    }
                                },
                                modifier = Modifier
                                    .size(34.dp)
                                    .testTag("downloader_paste_btn")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentPaste,
                                    contentDescription = "Paste from clipboard and fetch",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(19.dp)
                                )
                            }
                            IconButton(
                                onClick = { showDownloaderSettings = true },
                                modifier = Modifier
                                    .size(34.dp)
                                    .testTag("downloader_main_settings_btn")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Tune,
                                    contentDescription = "Downloader settings",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(19.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // 2. Search History / Recent links or 5-row gap
        // There should be a 5 row gap always present between search bar and activity card in Main Downloader Inbox Screen, even when no search history is there.
        val fiveRowGap = 200.dp
        if (pills.isNotEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("downloader_recent_pills_row"),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    items(pills, key = { it.domain + it.url }) { pill ->
                        Surface(
                            onClick = {
                                urlInput = pill.url
                                submitSearch(pill.url)
                            },
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                            modifier = Modifier
                                .height(38.dp)
                                .testTag("recent_pill_${pill.name}")
                        ) {
                            Row(
                                modifier = Modifier
                                    .padding(start = 6.dp, end = 14.dp, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // Circular site favicon avatar
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                    modifier = Modifier.size(26.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        var iconFailed by remember(pill.faviconUrl) { mutableStateOf(false) }
                                        if (!iconFailed) {
                                            AsyncImage(
                                                model = ImageRequest.Builder(LocalContext.current)
                                                    .data(pill.faviconUrl)
                                                    .crossfade(true)
                                                    .listener(
                                                        onError = { _, _ -> iconFailed = true }
                                                    )
                                                    .build(),
                                                contentDescription = pill.name,
                                                modifier = Modifier
                                                    .size(18.dp)
                                                    .clip(CircleShape),
                                                contentScale = ContentScale.Fit
                                            )
                                        } else {
                                            Icon(
                                                imageVector = Icons.Default.Language,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(15.dp)
                                            )
                                        }
                                    }
                                }

                                // Website name
                                Text(
                                    text = pill.name,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height((fiveRowGap - 38.dp - 26.dp).coerceAtLeast(16.dp)))
            }
        } else {
            // Generous 5-row gap always present when no search history is there
            Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(fiveRowGap - 16.dp)
                    .testTag("downloader_search_history_five_row_gap")
            )
        }
        // 3. Activity Card matching image_4fd87912.png
        ActivityPreviewCard(
            activities = activities,
            onOpenActivity = onOpenActivity,
            modifier = Modifier.fillMaxWidth()
        )

        // 4. Saved Preview Section matching image_1d9653bc.png layout with real data
        SavedPreviewSection(
            stacks = effectiveStacks,
            onViewSavedLibrary = onViewSavedLibrary,
            onSelectStack = onSelectStack,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * Saved Preview Section matching the layout of image_1d9653bc.png:
 * - Rounded card container with cloud download icon, "Saved" bold title, and "See All >" button
 * - Horizontal scroll of real stacked media cards with colored layer effect
 * - Item count badge on top-right of image, bold title, formatted timestamp
 * - Centered indicator dots below cards
 */
@Composable
fun SavedPreviewSection(
    stacks: List<MediaInboxStack>,
    onViewSavedLibrary: () -> Unit,
    onSelectStack: (MediaInboxStack) -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()
    val listState = rememberLazyListState()
    val containerShape = RoundedCornerShape(24.dp)

    Surface(
        shape = containerShape,
        color = if (isDark) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        shadowElevation = 6.dp,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isDark) 0.65f else 0.75f)
        ),
        modifier = modifier
            .shadow(
                elevation = 6.dp,
                shape = containerShape,
                ambientColor = if (isDark) Color.Black.copy(alpha = 0.5f) else Color.Black.copy(alpha = 0.12f),
                spotColor = if (isDark) Color.Black.copy(alpha = 0.65f) else Color.Black.copy(alpha = 0.18f)
            )
            .testTag("downloader_saved_preview_section")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 22.dp, bottom = 20.dp, start = 18.dp, end = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header: [Cloud Icon] Saved       [See All >]
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CloudDownload,
                        contentDescription = "Saved",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(28.dp)
                    )
                    Text(
                        text = "Saved",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        fontSize = 24.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Surface(
                    onClick = onViewSavedLibrary,
                    shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.55f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                    modifier = Modifier.testTag("downloader_saved_see_all_btn")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "See All",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            val displayStacks = remember(stacks) { stacks.take(5) }
            if (displayStacks.isNotEmpty()) {
                // Horizontal list of stacked preview cards (bounded to max 5 items to avoid unlimited horizontal scrolling)
                LazyRow(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("saved_preview_cards_row"),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 6.dp)
                ) {
                    itemsIndexed(displayStacks, key = { _, item -> item.id }) { index, stack ->
                        SavedStackPreviewCard(
                            stack = stack,
                            accentIndex = index,
                            onClick = { onSelectStack(stack) }
                        )
                    }
                }
            } else {
                // Clean empty state with no text or explanation matching image_6118052c.png
                val placeholderColor = if (isDark) {
                    MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f)
                } else {
                    Color(0xFFE8E8EE)
                }
                val placeholderSubColor = if (isDark) {
                    MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.35f)
                } else {
                    Color(0xFFEEEEF4)
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp, bottom = 8.dp)
                        .clickable(onClick = onViewSavedLibrary)
                        .testTag("saved_empty_state_container"),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    repeat(2) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            // Rounded thumbnail placeholder
                            Box(
                                modifier = Modifier
                                    .size(width = 86.dp, height = 52.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(placeholderColor)
                            )

                            // Title & subtitle line placeholders
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(0.72f)
                                        .height(11.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(placeholderColor)
                                )
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(0.44f)
                                        .height(9.dp)
                                        .clip(RoundedCornerShape(5.dp))
                                        .background(placeholderSubColor)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDownloaderSettings) {
        DownloaderSettingsBottomSheet(
            downloaderPrefs = downloaderPrefs,
            onDismiss = { showDownloaderSettings = false }
        )
    }
}

/**
 * Individual Saved Stack Preview Card:
 * - Stacked layered cards effect behind the main card with pastel accents
 * - Rounded cover image with item count badge
 * - Stack title and formatted timestamp
 */
@Composable
fun SavedStackPreviewCard(
    stack: MediaInboxStack,
    accentIndex: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()
    val layerColors = remember(accentIndex, isDark) {
        val palettes = listOf(
            // Lavender / Indigo (as in Taj Mahal card)
            if (isDark) listOf(Color(0xFF312E81).copy(alpha = 0.6f), Color(0xFF3730A3).copy(alpha = 0.45f))
            else listOf(Color(0xFFEDE9FE), Color(0xFFDDD6FE)),
            // Warm Amber / Peach (as in Ancient India card)
            if (isDark) listOf(Color(0xFF78350F).copy(alpha = 0.6f), Color(0xFF92400E).copy(alpha = 0.45f))
            else listOf(Color(0xFFFEF3C7), Color(0xFFFDE68A)),
            // Rose / Pink (as in Indian Architecture card)
            if (isDark) listOf(Color(0xFF831843).copy(alpha = 0.6f), Color(0xFF9D174D).copy(alpha = 0.45f))
            else listOf(Color(0xFFFCE7F3), Color(0xFFFBCFE8)),
            // Soft Teal / Cyan
            if (isDark) listOf(Color(0xFF164E63).copy(alpha = 0.6f), Color(0xFF155E75).copy(alpha = 0.45f))
            else listOf(Color(0xFFCFFAFE), Color(0xFFA5F3FC)),
            // Soft Emerald
            if (isDark) listOf(Color(0xFF064E3B).copy(alpha = 0.6f), Color(0xFF065F46).copy(alpha = 0.45f))
            else listOf(Color(0xFFD1FAE5), Color(0xFFA7F3D0))
        )
        palettes[accentIndex % palettes.size]
    }

    val cardWidth = 176.dp
    val cardHeight = 210.dp
    val cardShape = RoundedCornerShape(20.dp)

    Box(
        modifier = modifier
            .width(cardWidth + 12.dp)
            .height(cardHeight + 12.dp)
            .clickable(onClick = onClick)
            .testTag("saved_preview_stack_${stack.id}")
    ) {
        // Layer 2 (Backmost stacked card)
        Box(
            modifier = Modifier
                .offset(x = 10.dp, y = 10.dp)
                .width(cardWidth)
                .height(cardHeight)
                .background(layerColors[1], shape = cardShape)
                .border(1.dp, layerColors[1].copy(alpha = 0.85f), shape = cardShape)
        )

        // Layer 1 (Middle stacked card)
        Box(
            modifier = Modifier
                .offset(x = 5.dp, y = 5.dp)
                .width(cardWidth)
                .height(cardHeight)
                .background(layerColors[0], shape = cardShape)
                .border(1.dp, layerColors[0].copy(alpha = 0.9f), shape = cardShape)
        )

        // Front Main Card
        Surface(
            modifier = Modifier
                .width(cardWidth)
                .height(cardHeight),
            shape = cardShape,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
            shadowElevation = if (isDark) 1.dp else 4.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp)
            ) {
                // Thumbnail Box with item count badge
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(116.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                ) {
                    val coverItem = stack.items.firstOrNull { !it.isVideo && it.localUri.isNotBlank() }
                        ?: stack.items.firstOrNull { it.localUri.isNotBlank() }
                        ?: stack.items.firstOrNull()

                    val imageModel = coverItem?.localUri?.takeIf { it.isNotBlank() }
                        ?: coverItem?.sourceUrl?.takeIf { it.isNotBlank() }

                    if (imageModel != null) {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(imageModel)
                                .crossfade(true)
                                .build(),
                            contentDescription = stack.title,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Folder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    }

                    // Count Badge in top-right (e.g. 16, 24, 8)
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xCC1A1A1A),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .testTag("saved_preview_count_badge")
                    ) {
                        Text(
                            text = "${stack.items.size}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                // Title
                Text(
                    text = stack.title.ifBlank { "Saved Collection" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )

                Spacer(Modifier.height(2.dp))

                // Formatted Date & Time (e.g. Nov 04, 2026 • 9:02 AM)
                val dateFormatted = remember(stack.timestampMillis) {
                    try {
                        val sdf = java.text.SimpleDateFormat("MMM dd, yyyy • h:mm a", java.util.Locale.getDefault())
                        sdf.format(java.util.Date(stack.timestampMillis))
                    } catch (e: Exception) {
                        ""
                    }
                }
                Text(
                    text = dateFormatted,
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }
    }
}

/**
 * Dedicated Downloader Page that opens when the user enters or pastes a URL and chooses "Fetch".
 * Executes deep multi-provider extraction, displays real-time progress, filter chips,
 * staggered result cards, and sticky bottom download control.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloaderResultsPage(
    targetUrl: String,
    onBack: () -> Unit,
    onOpenBrowser: ((String) -> Unit)? = null,
    onDownloadComplete: (Int) -> Unit,
    onViewSavedLibrary: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dualExtractor = remember { DualMediaExtractor(context) }

    DisposableEffect(Unit) {
        onDispose {
            dualExtractor.cleanup()
        }
    }

    val extractorState by dualExtractor.state.collectAsState()

    var selectedFilter by remember { mutableStateOf<String?>(null) }
    var selectedItemIds by remember { mutableStateOf(setOf<String>()) }
    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    var downloadStatusText by remember { mutableStateOf("") }
    var sortDropdownExpanded by remember { mutableStateOf(false) }
    var sortOption by remember { mutableStateOf("Default") }

    // Active URL & Editing State
    var activeUrl by remember(targetUrl) { mutableStateOf(targetUrl) }
    var isEditingUrl by remember { mutableStateOf(false) }
    val cleanDisplayUrl = remember(activeUrl) {
        activeUrl.trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("www.")
    }
    var urlTextFieldValue by remember(cleanDisplayUrl) {
        mutableStateOf(
            TextFieldValue(
                text = cleanDisplayUrl,
                selection = TextRange(cleanDisplayUrl.length)
            )
        )
    }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    // Download & Checkbox dynamic states
    var isDownloaded by remember { mutableStateOf(false) }
    var downloadedItemIds by remember { mutableStateOf(setOf<String>()) }

    val activities by DownloadActivityManager.activities.collectAsState()
    val finishedMediaUrls = remember(activities) {
        activities.filter { it.status == DownloadActivityStatus.FINISHED }.map { it.mediaUrl }.toSet()
    }
    val finishedIds = remember(activities) {
        activities.filter { it.status == DownloadActivityStatus.FINISHED }.map { it.id }.toSet()
    }

    LaunchedEffect(isEditingUrl) {
        if (isEditingUrl) {
            urlTextFieldValue = TextFieldValue(
                text = cleanDisplayUrl,
                selection = TextRange(cleanDisplayUrl.length)
            )
            focusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    val downloaderPrefs = remember { DownloaderPreferencesManager(context) }
    val persistedMinKb by downloaderPrefs.minKbFilter.collectAsState(initial = 1)
    val persistedMinWidth by downloaderPrefs.minWidthFilter.collectAsState(initial = 0)
    val persistedMinHeight by downloaderPrefs.minHeightFilter.collectAsState(initial = 0)
    val persistedShowSvg by downloaderPrefs.showSvg.collectAsState(initial = false)
    val videoQualityCap by downloaderPrefs.videoQualityCap.collectAsState(initial = DownloaderPreferencesManager.getVideoQualityCapSync(context))
    val audioOnlyMode by downloaderPrefs.audioOnlyMode.collectAsState(initial = DownloaderPreferencesManager.isAudioOnlySync(context))
    val searchEnginePref by downloaderPrefs.searchEngine.collectAsState(initial = DownloaderPreferencesManager.getSearchEngineSync(context))

    // Dimension & SVG Filters (synchronized with persistent DownloaderPreferencesManager)
    var showDimensionFilter by remember { mutableStateOf(false) }
    var minWidthFilter by remember(persistedMinWidth) { mutableFloatStateOf(persistedMinWidth.toFloat()) }
    var minHeightFilter by remember(persistedMinHeight) { mutableFloatStateOf(persistedMinHeight.toFloat()) }
    var showSvg by remember(persistedShowSvg) { mutableStateOf(persistedShowSvg) }
    var minKbInput by remember(persistedMinKb) { mutableStateOf(persistedMinKb.toString()) }
    var qualityMenuExpanded by remember { mutableStateOf(false) }
    var showDownloaderSettings by remember { mutableStateOf(false) }
    var previewItem by remember { mutableStateOf<ScrapedMediaItem?>(null) }

    val dynamicDimensions = remember { mutableStateMapOf<String, Pair<Int, Int>>() }

    // Trigger extraction on mount or URL change
    LaunchedEffect(activeUrl) {
        if (activeUrl.isNotBlank()) {
            selectedItemIds = emptySet()
            downloadedItemIds = emptySet()
            isDownloaded = false
            dynamicDimensions.clear()
            DownloaderSession.startSession(activeUrl)
            dualExtractor.extract(activeUrl, scope)
        }
    }

    fun performFetch(target: String) {
        val trimmed = target.trim()
        if (trimmed.isNotBlank()) {
            val sanitized = MediaClassifier.sanitizeUrl(trimmed)
            if (sanitized != activeUrl) {
                activeUrl = sanitized
            } else {
                selectedItemIds = emptySet()
                downloadedItemIds = emptySet()
                isDownloaded = false
                dynamicDimensions.clear()
                DownloaderSession.startSession(sanitized)
                dualExtractor.extract(sanitized, scope)
            }
        }
        isEditingUrl = false
        focusManager.clearFocus()
        keyboardController?.hide()
    }

    fun performOpenInBrowser(target: String) {
        val trimmed = target.trim()
        if (trimmed.isNotBlank()) {
            val destinationUrl = MediaClassifier.formatQueryOrUrl(trimmed, searchEnginePref)
            isEditingUrl = false
            focusManager.clearFocus()
            keyboardController?.hide()
            if (onOpenBrowser != null) {
                onOpenBrowser(destinationUrl)
            } else {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(destinationUrl)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                runCatching { context.startActivity(intent) }
            }
        }
    }

    val sessionItems by DownloaderSession.discoveredItems.collectAsState()
    val allScrapedItems = remember(extractorState.items, sessionItems) {
        MediaClassifier.deduplicateScrapedItems(extractorState.items + sessionItems)
    }

    // Auto-select newly discovered items
    LaunchedEffect(allScrapedItems.size) {
        if (allScrapedItems.isNotEmpty() && selectedItemIds.isEmpty() && !extractorState.isRunning) {
            selectedItemIds = allScrapedItems.map { it.id }.toSet()
        }
    }

    // Throttled & batched dimension prober in background (Semaphore(4) + 250ms/5-item batch flush)
    LaunchedEffect(allScrapedItems) {
        val itemsToProbe = allScrapedItems.filter { item ->
            item.category == MediaCategory.IMAGES &&
                    dynamicDimensions[item.id] == null &&
                    parseScrapedMediaDimensions(item).let { (w, h) -> w == null && h == null }
        }.take(50)
        if (itemsToProbe.isEmpty()) return@LaunchedEffect

        // Immediately populate any dimensions already cached in ImageDimensionProber
        val cachedBatch = mutableMapOf<String, Pair<Int, Int>>()
        val remainingToProbe = mutableListOf<ScrapedMediaItem>()
        for (item in itemsToProbe) {
            val cached = ImageDimensionProber.getCached(item.url)
            if (cached != null) {
                cachedBatch[item.id] = cached
            } else {
                remainingToProbe.add(item)
            }
        }
        if (cachedBatch.isNotEmpty()) {
            dynamicDimensions.putAll(cachedBatch)
        }
        if (remainingToProbe.isEmpty()) return@LaunchedEffect

        val probeSemaphore = kotlinx.coroutines.sync.Semaphore(4)
        val stagedUpdates = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, Int>>()
        val flushMutex = kotlinx.coroutines.sync.Mutex()
        var lastFlushTime = System.currentTimeMillis()

        suspend fun flushStaged(force: Boolean = false) {
            flushMutex.lock()
            try {
                val now = System.currentTimeMillis()
                if (stagedUpdates.isNotEmpty() && (force || stagedUpdates.size >= 5 || now - lastFlushTime >= 250L)) {
                    val snapshot = HashMap(stagedUpdates)
                    stagedUpdates.clear()
                    lastFlushTime = now
                    kotlinx.coroutines.withContext(Dispatchers.Main.immediate) {
                        dynamicDimensions.putAll(snapshot)
                    }
                }
            } finally {
                flushMutex.unlock()
            }
        }

        kotlinx.coroutines.coroutineScope {
            for (item in remainingToProbe) {
                launch(Dispatchers.IO) {
                    probeSemaphore.acquire()
                    try {
                        val probed = ImageDimensionProber.probe(item.url, referer = activeUrl)
                        if (probed != null) {
                            stagedUpdates[item.id] = probed
                            flushStaged(force = false)
                        }
                    } finally {
                        probeSemaphore.release()
                    }
                }
            }
        }
        flushStaged(force = true)
    }

    val minKbVal = minKbInput.toFloatOrNull() ?: 0f
    val minBytesThreshold = (minKbVal * 1024L).toLong()

    val dimensionFiltered = remember(
        allScrapedItems,
        minWidthFilter,
        minHeightFilter,
        showSvg,
        minBytesThreshold,
        dynamicDimensions.size
    ) {
        val targetMinWidth = minWidthFilter.toInt()
        val targetMinHeight = minHeightFilter.toInt()

        allScrapedItems.filter { item ->
            if (!showSvg && MediaClassifier.isSvg(item.url, item.mimeType, item.format)) {
                return@filter false
            }
            if (minBytesThreshold > 0L) {
                val knownSize = item.sizeBytes ?: ImageDimensionProber.getCachedSize(item.url)
                if (knownSize != null && knownSize < minBytesThreshold) {
                    return@filter false
                }
            }
            if (targetMinWidth > 0 || targetMinHeight > 0) {
                if (!passesDimensionFilter(item, targetMinWidth, targetMinHeight, dynamicDimensions)) {
                    return@filter false
                }
            }
            true
        }
    }

    val imageCount = remember(dimensionFiltered) { dimensionFiltered.count { it.category == MediaCategory.IMAGES } }
    val videoCount = remember(dimensionFiltered) { dimensionFiltered.count { it.category == MediaCategory.VIDEOS } }
    val audioCount = remember(dimensionFiltered) { dimensionFiltered.count { it.category == MediaCategory.AUDIO } }
    val streamCount = remember(dimensionFiltered) { dimensionFiltered.count { it.category == MediaCategory.STREAMS } }

    val filteredItems = remember(selectedFilter, dimensionFiltered, sortOption) {
        val categoryFiltered = when (selectedFilter) {
            "Images" -> dimensionFiltered.filter { it.category == MediaCategory.IMAGES }
            "Videos" -> dimensionFiltered.filter { it.category == MediaCategory.VIDEOS }
            "Audio" -> dimensionFiltered.filter { it.category == MediaCategory.AUDIO }
            "Streams" -> dimensionFiltered.filter { it.category == MediaCategory.STREAMS }
            else -> dimensionFiltered
        }
        when (sortOption) {
            "Title (A-Z)" -> categoryFiltered.sortedBy { it.title.lowercase() }
            "Videos First" -> categoryFiltered.sortedByDescending { it.isVideo }
            "Images First" -> categoryFiltered.sortedBy { it.isVideo || it.isAudio || it.isStream }
            "Streams First" -> categoryFiltered.sortedByDescending { it.isStream }
            else -> categoryFiltered
        }
    }

    val isAllSelected = filteredItems.isNotEmpty() && selectedItemIds.containsAll(filteredItems.map { it.id })
    val filterOptions = listOf("Images", "Videos", "Audio", "Streams")

    BackHandler {
        if (isEditingUrl) {
            isEditingUrl = false
            urlTextFieldValue = TextFieldValue(cleanDisplayUrl, selection = TextRange(cleanDisplayUrl.length))
            focusManager.clearFocus()
            keyboardController?.hide()
        } else if (showDimensionFilter) {
            showDimensionFilter = false
        } else {
            onBack()
        }
    }

    val categories = listOf(
        Triple("Images", Icons.Default.Image, imageCount),
        Triple("Videos", Icons.Default.Videocam, videoCount),
        Triple("Audio", Icons.Default.MusicNote, audioCount),
        Triple("Streams", Icons.Default.SmartDisplay, streamCount)
    )

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = modifier.fillMaxSize().testTag("downloader_results_page")
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 1. Top Browser Bar: Single rounded horizontal bar with navigation and address
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(26.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.85f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("downloader_top_browser_bar")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // Back Button
                        IconButton(
                            onClick = {
                                if (isEditingUrl) {
                                    isEditingUrl = false
                                    urlTextFieldValue = TextFieldValue(cleanDisplayUrl, selection = TextRange(cleanDisplayUrl.length))
                                    focusManager.clearFocus()
                                    keyboardController?.hide()
                                } else {
                                    onBack()
                                }
                            },
                            modifier = Modifier.size(40.dp).testTag("downloader_results_back_btn")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        // Web Address Area: Tappable to edit, sets cursor to the end of typed text
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) {
                                    if (!isEditingUrl) {
                                        urlTextFieldValue = TextFieldValue(
                                            text = cleanDisplayUrl,
                                            selection = TextRange(cleanDisplayUrl.length)
                                        )
                                        isEditingUrl = true
                                    }
                                }
                                .padding(horizontal = 4.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            if (!isEditingUrl) {
                                Text(
                                    text = cleanDisplayUrl.ifBlank { "Paste link or enter URL..." },
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Normal,
                                    fontSize = 15.sp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.fillMaxWidth().testTag("downloader_results_address_display"),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            } else {
                                BasicTextField(
                                    value = urlTextFieldValue,
                                    onValueChange = { urlTextFieldValue = it },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Uri,
                                        imeAction = ImeAction.Go
                                    ),
                                    keyboardActions = KeyboardActions(
                                        onGo = {
                                            performFetch(urlTextFieldValue.text)
                                        }
                                    ),
                                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontSize = 15.sp
                                    ),
                                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .focusRequester(focusRequester)
                                        .testTag("downloader_results_url_input")
                                )
                            }
                        }

                        // Trailing Controls:
                        // When editing/focused: show Clear, Open in Live Browser, AND Fetch button so user can fetch new sites or launch browser
                        if (isEditingUrl) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                // 1. Clear / Cancel Button
                                IconButton(
                                    onClick = {
                                        if (urlTextFieldValue.text.isNotEmpty()) {
                                            urlTextFieldValue = TextFieldValue("", selection = TextRange(0))
                                        } else {
                                            isEditingUrl = false
                                            urlTextFieldValue = TextFieldValue(cleanDisplayUrl, selection = TextRange(cleanDisplayUrl.length))
                                            focusManager.clearFocus()
                                            keyboardController?.hide()
                                        }
                                    },
                                    modifier = Modifier.size(34.dp).testTag("downloader_clear_btn")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = if (urlTextFieldValue.text.isNotEmpty()) "Clear text" else "Cancel editing",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }

                                // 2. Launch in Live Browser Button
                                IconButton(
                                    onClick = {
                                        val target = urlTextFieldValue.text.ifBlank { activeUrl }
                                        performOpenInBrowser(target)
                                    },
                                    modifier = Modifier.size(34.dp).testTag("downloader_action_browse_btn")
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                        contentDescription = "Open in browser",
                                        tint = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(19.dp)
                                    )
                                }

                                // 3. Fetch / Scrape new site directly on this page
                                Surface(
                                    onClick = {
                                        val target = urlTextFieldValue.text.ifBlank { activeUrl }
                                        performFetch(target)
                                    },
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(32.dp).testTag("downloader_action_fetch_btn")
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                            contentDescription = "Fetch media",
                                            tint = MaterialTheme.colorScheme.onPrimary,
                                            modifier = Modifier.size(17.dp)
                                        )
                                    }
                                }
                            }
                        } else {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                // When not editing: Progress indicator morphs into Reload button once scraping finishes
                                if (extractorState.isRunning) {
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .testTag("downloader_progress_indicator"),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                } else {
                                    IconButton(
                                        onClick = {
                                            selectedItemIds = emptySet()
                                            dynamicDimensions.clear()
                                            dualExtractor.extract(activeUrl, scope)
                                        },
                                        modifier = Modifier.size(36.dp).testTag("downloader_refresh_btn")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Refresh,
                                            contentDescription = "Reload",
                                            tint = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }

                                // External Browser Redirect Button
                                IconButton(
                                    onClick = {
                                        performOpenInBrowser(activeUrl)
                                    },
                                    modifier = Modifier.size(36.dp).testTag("downloader_browser_btn")
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                        contentDescription = "Open in browser",
                                        tint = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 2. Media Category Toolbar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Category Pills (Images, Videos, Audio, Streams)
                LazyRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    items(categories) { (categoryName, icon, count) ->
                        val isSelected = selectedFilter == categoryName
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier
                                .clickable {
                                    selectedFilter = if (isSelected) null else categoryName
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = categoryName,
                                    tint = if (isSelected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "$count",
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp,
                                    color = if (isSelected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.width(8.dp))

                // Vertical Divider between categories and utilities
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(20.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )

                Spacer(Modifier.width(8.dp))

                // Grouped Controls: Quality/Audio Picker, Sort, Filter, Select All, Settings
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Quality / Audio-Only Picker Pill
                    Box {
                        val activeQualityLabel = if (audioOnlyMode) "Audio" else when (videoQualityCap) {
                            DownloaderVideoQuality.BEST -> "Best"
                            DownloaderVideoQuality.Q8K -> "8K"
                            DownloaderVideoQuality.Q4K -> "4K"
                            DownloaderVideoQuality.Q2K -> "2K"
                            DownloaderVideoQuality.Q1080P -> "1080p"
                            DownloaderVideoQuality.Q720P -> "720p"
                            DownloaderVideoQuality.Q480P -> "480p"
                            DownloaderVideoQuality.Q360P -> "360p"
                        }
                        val isCustomQuality = audioOnlyMode || videoQualityCap != DownloaderVideoQuality.BEST
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = if (isCustomQuality) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier
                                .height(34.dp)
                                .clickable { qualityMenuExpanded = true }
                                .testTag("downloader_quality_picker_btn")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = if (audioOnlyMode) Icons.Default.Audiotrack else Icons.Default.HighQuality,
                                    contentDescription = "Quality & Audio mode",
                                    tint = if (isCustomQuality) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(15.dp)
                                )
                                Text(
                                    text = activeQualityLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isCustomQuality) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                        DropdownMenu(
                            expanded = qualityMenuExpanded,
                            onDismissRequest = { qualityMenuExpanded = false }
                        ) {
                            DownloaderVideoQuality.entries.forEach { q ->
                                val selected = !audioOnlyMode && videoQualityCap == q
                                DropdownMenuItem(
                                    text = { Text(q.label, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = if (selected) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    },
                                    onClick = {
                                        qualityMenuExpanded = false
                                        scope.launch {
                                            downloaderPrefs.setAudioOnlyMode(false)
                                            downloaderPrefs.setVideoQualityCap(q)
                                            if (activeUrl.isNotBlank() && !extractorState.isRunning) {
                                                dynamicDimensions.clear()
                                                dualExtractor.extract(activeUrl, scope)
                                            }
                                        }
                                    }
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "Audio Only (M4A / Opus)",
                                        fontWeight = if (audioOnlyMode) FontWeight.Bold else FontWeight.Normal
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = if (audioOnlyMode) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                onClick = {
                                    qualityMenuExpanded = false
                                    scope.launch {
                                        downloaderPrefs.setAudioOnlyMode(!audioOnlyMode)
                                        if (activeUrl.isNotBlank() && !extractorState.isRunning) {
                                            dynamicDimensions.clear()
                                            dualExtractor.extract(activeUrl, scope)
                                        }
                                    }
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Downloader Settings…") },
                                leadingIcon = {
                                    Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                                },
                                onClick = {
                                    qualityMenuExpanded = false
                                    showDownloaderSettings = true
                                }
                            )
                        }
                    }

                    // Sort Control
                    Box {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier
                                .size(34.dp)
                                .clickable { sortDropdownExpanded = true }
                                .testTag("downloader_sort_btn")
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.SwapVert,
                                    contentDescription = "Sort",
                                    tint = if (sortOption != "Default") MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        DropdownMenu(
                            expanded = sortDropdownExpanded,
                            onDismissRequest = { sortDropdownExpanded = false }
                        ) {
                            listOf("Default", "Title (A-Z)", "Videos First", "Images First", "Streams First").forEach { opt ->
                                DropdownMenuItem(
                                    text = { Text(opt, fontWeight = if (sortOption == opt) FontWeight.Bold else FontWeight.Normal) },
                                    onClick = {
                                        sortOption = opt
                                        sortDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    // Dimension / Filter Control
                    val hasActiveDimensionFilter = minWidthFilter > 0 || minHeightFilter > 0 || showSvg || (minKbInput.toIntOrNull() ?: 1) > 1
                    Surface(
                        shape = CircleShape,
                        color = if (hasActiveDimensionFilter) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier
                            .size(34.dp)
                            .clickable { showDimensionFilter = !showDimensionFilter }
                            .testTag("downloader_filter_tune_btn")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Tune,
                                contentDescription = "Filter dimensions & size",
                                tint = if (hasActiveDimensionFilter) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    // Selection Checkbox Control
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier
                            .size(34.dp)
                            .clickable {
                                isDownloaded = false
                                val ids = filteredItems.map { it.id }.toSet()
                                selectedItemIds = if (isAllSelected) selectedItemIds - ids else selectedItemIds + ids
                            }
                            .testTag("downloader_select_all_btn")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (isAllSelected) Icons.Default.Check else Icons.Default.CheckBoxOutlineBlank,
                                contentDescription = "Select All",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            // Dimension & Size Filter Drawer (Animated, Dark-Mode Native, Persisted)
            AnimatedVisibility(visible = showDimensionFilter) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Dimension & Size Filter",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = { showDownloaderSettings = true }) {
                                    Text("Settings", color = MaterialTheme.colorScheme.primary)
                                }
                                TextButton(onClick = {
                                    minWidthFilter = 0f
                                    minHeightFilter = 0f
                                    showSvg = false
                                    minKbInput = "1"
                                    scope.launch {
                                        downloaderPrefs.setMinWidthFilter(0)
                                        downloaderPrefs.setMinHeightFilter(0)
                                        downloaderPrefs.setShowSvg(false)
                                        downloaderPrefs.setMinKbFilter(1)
                                    }
                                }) {
                                    Text("Reset", color = MaterialTheme.colorScheme.onSurface)
                                }
                            }
                        }

                        // Min File Size (KB) Filter Control
                        val currentMinKb = minKbInput.toFloatOrNull()?.coerceIn(0f, 1024f) ?: 1f
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Hide media smaller than: ${if (currentMinKb <= 0f) "0 KB (All)" else "${currentMinKb.toInt()} KB"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Slider(
                            value = currentMinKb,
                            onValueChange = {
                                val rounded = it.toInt()
                                minKbInput = rounded.toString()
                            },
                            onValueChangeFinished = {
                                scope.launch {
                                    downloaderPrefs.setMinKbFilter(minKbInput.toIntOrNull() ?: 1)
                                }
                            },
                            valueRange = 0f..512f,
                            steps = 7,
                            modifier = Modifier.fillMaxWidth().testTag("downloader_min_kb_slider")
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Min Width: ${if (minWidthFilter <= 0f) "Any" else "${minWidthFilter.toInt()}px"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Slider(
                            value = minWidthFilter,
                            onValueChange = { minWidthFilter = it },
                            onValueChangeFinished = {
                                scope.launch { downloaderPrefs.setMinWidthFilter(minWidthFilter.toInt()) }
                            },
                            valueRange = 0f..3840f,
                            steps = 3,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Min Height: ${if (minHeightFilter <= 0f) "Any" else "${minHeightFilter.toInt()}px"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Slider(
                            value = minHeightFilter,
                            onValueChange = { minHeightFilter = it },
                            onValueChangeFinished = {
                                scope.launch { downloaderPrefs.setMinHeightFilter(minHeightFilter.toInt()) }
                            },
                            valueRange = 0f..2160f,
                            steps = 3,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Show SVG images", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Switch(
                                checked = showSvg,
                                onCheckedChange = {
                                    showSvg = it
                                    scope.launch { downloaderPrefs.setShowSvg(it) }
                                }
                            )
                        }
                    }
                }
            }

            if (showDownloaderSettings) {
                DownloaderSettingsBottomSheet(
                    downloaderPrefs = downloaderPrefs,
                    onDismiss = { showDownloaderSettings = false }
                )
            }

            previewItem?.let { itemToPreview ->
                DownloaderMediaPreviewDialog(
                    item = itemToPreview,
                    dynamicDimensions = dynamicDimensions,
                    onDismiss = { previewItem = null },
                    onDownloadNow = {
                        previewItem = null
                        val stackId = "stack_${System.currentTimeMillis()}"
                        val stackTitle = extractorState.pageTitle.ifBlank { extractorState.domain.ifBlank { "Media Download" } }
                        DownloadQueueManager.enqueueDownload(
                            context = context,
                            item = itemToPreview,
                            referer = activeUrl,
                            stackId = stackId,
                            stackTitle = stackTitle,
                            sourceDomain = extractorState.domain,
                            sourceUrl = activeUrl
                        )
                        onDownloadComplete(1)
                    }
                )
            }

            // 3. Media Grid Area with Floating Centered Download Pill
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (filteredItems.isNotEmpty()) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp)
                            .testTag("downloader_results_grid"),
                        contentPadding = PaddingValues(top = 8.dp, bottom = 80.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(filteredItems, key = { it.id }) { item ->
                            DownloaderMediaCard(
                                item = item,
                                isSelected = item.id in selectedItemIds,
                                isDownloaded = item.id in finishedIds || item.url in finishedMediaUrls,
                                dynamicDimensions = dynamicDimensions,
                                onPreview = { previewItem = item },
                                onToggle = {
                                    isDownloaded = false
                                    selectedItemIds = if (item.id in selectedItemIds) {
                                        selectedItemIds - item.id
                                    } else {
                                        selectedItemIds + item.id
                                    }
                                }
                            )
                        }
                    }
                } else if (extractorState.isRunning) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                        contentPadding = PaddingValues(top = 10.dp, bottom = 80.dp)
                    ) {
                        item {
                            ThreeColumnSkeletonGrid()
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.widthIn(max = 420.dp)
                        ) {
                            val failureReason = extractorState.errorMessage?.ifBlank { null }
                                ?: extractorState.statusMessage.takeIf {
                                    it.contains("protect", true) || it.contains("challenge", true) ||
                                    it.contains("waf", true) || it.contains("restricted", true) ||
                                    it.contains("timeout", true) || it.contains("failed", true)
                                }
                            val isChallenge = failureReason?.let {
                                it.contains("protect", true) || it.contains("challenge", true) ||
                                it.contains("waf", true) || it.contains("bot", true) ||
                                it.contains("turnstile", true) || it.contains("captcha", true)
                            } == true

                            Icon(
                                imageVector = if (isChallenge) Icons.Default.Shield else Icons.Default.SearchOff,
                                contentDescription = null,
                                tint = if (isChallenge) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(48.dp)
                            )
                            Text(
                                text = if (isChallenge) "Verification Required" else "No media discovered",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (failureReason != null) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (isChallenge) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surfaceContainerLow,
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(12.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text(
                                            text = failureReason,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.Medium,
                                            color = if (isChallenge) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                            textAlign = TextAlign.Center
                                        )
                                        if (isChallenge) {
                                            Spacer(Modifier.height(4.dp))
                                            Text(
                                                text = "Open Live Browser once to resolve the challenge. Media will automatically appear here.",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                                                textAlign = TextAlign.Center
                                            )
                                        }
                                    }
                                }
                            } else {
                                Text(
                                    text = "The webpage might be dynamically protected, require login, or filter settings are excluding items.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Button(
                                onClick = {
                                    if (onOpenBrowser != null) {
                                        onOpenBrowser(activeUrl)
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.onSurface,
                                    contentColor = MaterialTheme.colorScheme.surface
                                )
                            ) {
                                Icon(Icons.Default.TravelExplore, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text(if (isChallenge) "Verify in Live Browser" else "Open in Live Browser")
                            }
                        }
                    }
                }

                // 6. Centered Floating Download Action Pill (Theme-aware black/white contrast)
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.onSurface,
                    shadowElevation = 8.dp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 16.dp)
                        .clickable(enabled = !isDownloading) {
                            if (selectedItemIds.isEmpty() || isDownloading) return@clickable
                            isDownloading = true
                            downloadProgress = 0.2f
                            downloadStatusText = "Preparing download batch..."
                            scope.launch {
                                val selectedItems = filteredItems.filter { it.id in selectedItemIds }
                                val deduplicated = MediaClassifier.deduplicateScrapedItems(selectedItems)
                                val stackId = "stack_${System.currentTimeMillis()}"
                                val stackTitle = extractorState.pageTitle.ifBlank { extractorState.domain.ifBlank { "Media Download" } }

                                DownloadQueueManager.enqueueBatch(
                                    context = context,
                                    items = deduplicated,
                                    referer = activeUrl,
                                    stackId = stackId,
                                    stackTitle = stackTitle,
                                    sourceDomain = extractorState.domain,
                                    sourceUrl = activeUrl
                                )

                                downloadProgress = 1.0f
                                downloadStatusText = "Queued ${deduplicated.size} item(s)"
                                delay(300)
                                isDownloading = false
                                onDownloadComplete(deduplicated.size)
                            }
                        }
                        .testTag("downloader_download_selected_btn")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (isDownloading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.surface
                            )
                        } else if (isDownloaded) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Downloaded",
                                tint = MaterialTheme.colorScheme.surface,
                                modifier = Modifier.size(20.dp)
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.ArrowDownward,
                                contentDescription = "Download selected",
                                tint = MaterialTheme.colorScheme.surface,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Text(
                            text = "(${selectedItemIds.size})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.surface
                        )
                    }
                }
            }
        }
    }
}

/**
 * Modal Bottom Sheet for configuring persistent Media Downloader preferences:
 * - Default Video Quality Cap & Audio-Only mode
 * - Subfolder organization (Flat, By Domain, By Stack Title)
 * - Wi-Fi only downloads (NetworkType.UNMETERED)
 * - Default Browser Search Engine
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloaderSettingsBottomSheet(
    downloaderPrefs: DownloaderPreferencesManager,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val videoQuality by downloaderPrefs.videoQualityCap.collectAsState(initial = DownloaderVideoQuality.BEST)
    val audioOnly by downloaderPrefs.audioOnlyMode.collectAsState(initial = false)
    val preferredAudioFormat by downloaderPrefs.preferredAudioFormat.collectAsState(initial = "M4A")
    val subfolderMode by downloaderPrefs.subfolderMode.collectAsState(initial = DownloaderSubfolderMode.FLAT)
    val filenameTemplate by downloaderPrefs.filenameTemplate.collectAsState(initial = DownloaderPreferencesManager.DEFAULT_FILENAME_TEMPLATE)
    val wifiOnly by downloaderPrefs.wifiOnlyDownloads.collectAsState(initial = false)
    val maxConcurrent by downloaderPrefs.maxConcurrentDownloads.collectAsState(initial = 3)
    val enabledProviders by downloaderPrefs.enabledProviders.collectAsState(initial = DownloaderPreferencesManager.DEFAULT_PROVIDERS)
    val searchEngine by downloaderPrefs.searchEngine.collectAsState(initial = BrowserSearchEngine.DUCKDUCKGO)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Downloader Settings",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            // 1. Video Quality Cap
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Preferred Video Quality",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(DownloaderVideoQuality.entries) { q ->
                        FilterChip(
                            selected = !audioOnly && videoQuality == q,
                            onClick = {
                                scope.launch {
                                    downloaderPrefs.setAudioOnlyMode(false)
                                    downloaderPrefs.setVideoQualityCap(q)
                                }
                            },
                            label = { Text(q.label) }
                        )
                    }
                }
            }

            // 2. Audio-Only Extraction Mode
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Audio-Only Mode",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Extract native audio stream (M4A / Opus) from social videos",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = audioOnly,
                    onCheckedChange = { enabled ->
                        scope.launch { downloaderPrefs.setAudioOnlyMode(enabled) }
                    }
                )
            }

            // 3. Subfolder Organization Mode
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Download Folder Organization",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                DownloaderSubfolderMode.entries.forEach { mode ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (subfolderMode == mode) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                scope.launch { downloaderPrefs.setSubfolderMode(mode) }
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = subfolderMode == mode,
                                onClick = { scope.launch { downloaderPrefs.setSubfolderMode(mode) } }
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = mode.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = mode.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // 4. Wi-Fi Only Downloads
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Wi-Fi Only Downloads",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Require unmetered Wi-Fi network for background downloads",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = wifiOnly,
                    onCheckedChange = { enabled ->
                        scope.launch { downloaderPrefs.setWifiOnlyDownloads(enabled) }
                    }
                )
            }

            // 5. Default Browser Search Engine
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Default Search Engine",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(BrowserSearchEngine.entries) { engine ->
                        FilterChip(
                            selected = searchEngine == engine,
                            onClick = {
                                scope.launch { downloaderPrefs.setSearchEngine(engine) }
                            },
                            label = { Text(engine.label) }
                        )
                    }
                }
            }

            // 6. Preferred Native Audio Format (M4A / Opus)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Preferred Audio Format",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("M4A" to "M4A (AAC)", "OPUS" to "Opus (WebM)").forEach { (fmt, label) ->
                        FilterChip(
                            selected = preferredAudioFormat.equals(fmt, ignoreCase = true),
                            onClick = { scope.launch { downloaderPrefs.setPreferredAudioFormat(fmt) } },
                            label = { Text(label) }
                        )
                    }
                }
            }

            // 7. Max Concurrent Downloads
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Max Concurrent Downloads",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1, 2, 3, 4, 6).forEach { count ->
                        FilterChip(
                            selected = maxConcurrent == count,
                            onClick = { scope.launch { downloaderPrefs.setMaxConcurrentDownloads(count) } },
                            label = { Text("$count") }
                        )
                    }
                }
            }

            // 8. Enabled Extraction Providers
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Active Extraction Engines",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf("yt-dlp" to "yt-dlp", "gallery-dl" to "gallery-dl", "static" to "HTML", "browser" to "Browser")) { (key, label) ->
                        val active = key in enabledProviders
                        FilterChip(
                            selected = active,
                            onClick = {
                                val next = if (active && enabledProviders.size > 1) enabledProviders - key else enabledProviders + key
                                scope.launch { downloaderPrefs.setEnabledProviders(next) }
                            },
                            label = { Text(label) }
                        )
                    }
                }
            }

            // 9. Filename Template
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Filename Format",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf("{title}_{hash}" to "Title + ID", "{domain}_{title}" to "Domain + Title", "{title}" to "Title Only")) { (tpl, label) ->
                        FilterChip(
                            selected = filenameTemplate == tpl,
                            onClick = { scope.launch { downloaderPrefs.setFilenameTemplate(tpl) } },
                            label = { Text(label) }
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
        }
    }
}

/**
 * Full-screen in-place preview modal for inspecting a scraped image, video thumbnail, or audio item before downloading.
 */
@Composable
fun DownloaderMediaPreviewDialog(
    item: ScrapedMediaItem,
    dynamicDimensions: Map<String, Pair<Int, Int>>,
    onDismiss: () -> Unit,
    onDownloadNow: () -> Unit
) {
    val dimText = formatScrapedMediaDimensionOrSize(item, dynamicDimensions)
    val durationLabel = remember(item.durationSeconds) { formatMediaDuration(item.durationSeconds) }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 8.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 24.dp)
                .testTag("downloader_media_preview_dialog")
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = item.title.ifBlank { "Media Preview" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close preview", tint = MaterialTheme.colorScheme.onSurface)
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 220.dp, max = 420.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center
                ) {
                    val isAudioWithoutArt = (item.isAudio || item.category == MediaCategory.AUDIO) && item.thumbnailUrl.isNullOrBlank()
                    if (isAudioWithoutArt) {
                        Icon(
                            imageVector = Icons.Default.Audiotrack,
                            contentDescription = item.title,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(64.dp)
                        )
                    } else {
                        AsyncImage(
                            model = item.thumbnailUrl ?: item.url,
                            contentDescription = item.title,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    if (durationLabel != null) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color.Black.copy(alpha = 0.74f),
                            modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp)
                        ) {
                            Text(
                                text = durationLabel,
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = listOfNotNull(
                            item.format.uppercase(Locale.ROOT).takeIf { it.isNotBlank() },
                            dimText.takeIf { it.isNotBlank() },
                            item.source.takeIf { it.isNotBlank() }
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = onDownloadNow,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.onSurface,
                            contentColor = MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Icon(Icons.Default.ArrowDownward, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Download")
                    }
                }
            }
        }
    }
}

/**
 * Clean, prominent media card conforming to the minimal Emre design language (Dark-Mode Native).
 * Format label top-left, selection checkbox top-right, filename below thumbnail,
 * dimensions/size bottom-left, media category bottom-right.
 */
@Composable
fun DownloaderMediaCard(
    item: ScrapedMediaItem,
    isSelected: Boolean,
    dynamicDimensions: Map<String, Pair<Int, Int>>,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    isDownloaded: Boolean = false,
    onPreview: (() -> Unit)? = null
) {
    val dimText = formatScrapedMediaDimensionOrSize(item, dynamicDimensions)
    val categoryText = when {
        item.isVideo -> "Videos"
        item.isAudio -> "Audio"
        item.isStream -> "Streams"
        else -> "Images"
    }
    val formatText = if (!item.isVideo && item.format.contains("4K", ignoreCase = true)) {
        "JPG"
    } else {
        val raw = item.format.uppercase().trim()
        when {
            raw == "JPEG" -> "JPG"
            raw.isNotBlank() -> raw
            item.isVideo -> "MP4"
            item.isAudio -> "MP3"
            item.isStream -> "M3U8"
            else -> "JPG"
        }
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .testTag("scraped_media_card_${item.id}")
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Thumbnail container
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(118.dp)
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                val isAudioWithoutArt = (item.isAudio || item.category == MediaCategory.AUDIO) && item.thumbnailUrl.isNullOrBlank()
                if (isAudioWithoutArt) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                            modifier = Modifier.size(48.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Audiotrack,
                                    contentDescription = item.title,
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                        }
                    }
                } else {
                    val previewUrl = item.thumbnailUrl ?: item.url
                    AsyncImage(
                        model = previewUrl,
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Top-Left Format Pill
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    shadowElevation = 1.dp,
                    modifier = Modifier
                        .padding(start = 6.dp, top = 6.dp)
                        .align(Alignment.TopStart)
                ) {
                    Text(
                        text = formatText,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                    )
                }

                // Top-Right Checkbox: Turns ticked on download with theme-aware contrast
                val isTicked = isSelected || isDownloaded
                Box(
                    modifier = Modifier
                        .padding(end = 6.dp, top = 6.dp)
                        .align(Alignment.TopEnd)
                        .size(20.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (isTicked) MaterialTheme.colorScheme.onSurface else Color.Black.copy(alpha = 0.25f))
                        .border(
                            width = 1.5.dp,
                            color = if (isTicked) MaterialTheme.colorScheme.surface else Color.White,
                            shape = RoundedCornerShape(4.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (isTicked) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = if (isDownloaded) "Downloaded" else "Selected",
                            tint = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }

                // Bottom-Left Preview Lightbox Trigger
                if (onPreview != null) {
                    Surface(
                        onClick = onPreview,
                        shape = CircleShape,
                        color = Color.Black.copy(alpha = 0.58f),
                        modifier = Modifier
                            .padding(start = 6.dp, bottom = 6.dp)
                            .align(Alignment.BottomStart)
                            .size(22.dp)
                            .testTag("scraped_media_preview_btn_${item.id}")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.OpenInFull,
                                contentDescription = "Preview media",
                                tint = Color.White,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                    }
                }

                // Bottom-Right Duration Pill for Video, Audio, and Stream previews
                val durationLabel = remember(item.durationSeconds) { formatMediaDuration(item.durationSeconds) }
                if (durationLabel != null && (item.isVideo || item.isAudio || item.isStream)) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color.Black.copy(alpha = 0.74f),
                        modifier = Modifier
                            .padding(end = 6.dp, bottom = 6.dp)
                            .align(Alignment.BottomEnd)
                            .testTag("scraped_media_duration_${item.id}")
                    ) {
                        Text(
                            text = durationLabel,
                            style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                            fontWeight = FontWeight.Bold,
                            fontSize = 9.sp,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            // Info below thumbnail
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 7.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = item.title.ifBlank { "media_${item.id.takeLast(6)}" },
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = dimText,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                    Text(
                        text = categoryText,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
fun WebbyDiscoveredMediaGrid(
    items: List<ScrapedMediaItem>,
    onDownloadSingle: (ScrapedMediaItem) -> Unit,
    onDownloadSelected: (List<ScrapedMediaItem>) -> Unit,
    onOpenInStudio: (String) -> Unit,
    onClearAll: () -> Unit,
    searchHistory: List<String> = emptyList(),
    onSelectHistoryUrl: (String) -> Unit = {},
    onClearHistory: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var selectedIds by remember(items) { mutableStateOf(items.map { it.id }.toSet()) }
    Column(modifier = modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${items.size} media found",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onClearAll) {
                    Text("Clear")
                }
                Button(
                    onClick = { onDownloadSelected(items.filter { it.id in selectedIds }) },
                    enabled = selectedIds.isNotEmpty()
                ) {
                    Text("Download (${selectedIds.size})")
                }
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f)
        ) {
            items(items, key = { it.id }) { item ->
                val isSelected = item.id in selectedIds
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    tonalElevation = 1.dp,
                    border = if (isSelected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            selectedIds = if (isSelected) selectedIds - item.id else selectedIds + item.id
                        }
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text(
                            text = item.title.ifBlank { item.format },
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            TextButton(onClick = { onOpenInStudio(item.url) }) {
                                Text("Studio")
                            }
                            TextButton(onClick = { onDownloadSingle(item) }) {
                                Text("Save")
                            }
                        }
                    }
                }
            }
        }
    }
}
