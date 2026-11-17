# Media Downloader Fix & Upgrade Tasks

## Phase 1: Audio & Extraction Pipeline Fixes

* \[x\] **T1.1 Fix `extractorEngine` attribution in `GalleryDlEngine.kt`**

  * Updated `parseExtractedItems()` (`GalleryDlEngine.kt`) to set `extractorEngine = itemSource` (`"yt-dlp"` or `"gallery-dl"`) instead of hardcoded `"gallery-dl / yt-dlp"`.

* \[x\] **T1.2 Expand `YtDlpProvider` & `MediaDownloadWorker` social domain coverage**

  * Added `reddit.com`, `redd.it`, `v.redd.it`, `twitter.com`, `x.com`, and `fb.watch` to `YtDlpProvider.isApplicable()` (`DualMediaExtractor.kt`), `check_support()` (`gallery_dl_runner.py`), and `isSocialUrl` (`MediaDownloadWorker.kt`).

  * Updated `MediaDownloadWorker.kt` to check `sourceEngine.contains("yt-dlp", ignoreCase = true)`.

* \[x\] **T1.3 Add split DASH audio+video download & Android `MediaMuxer` merging**

  * Created `app/src/main/java/com/amresalehin/emregallery/data/AndroidMediaMuxer.kt` using `MediaExtractor` and `MediaMuxer` to mux separate `.mp4` video and `.m4a`/`.mp4` audio tracks losslessly.

  * Updated `gallery_dl_runner.py` (`_select_best_ytdlp_formats`, `extract_yt_dlp`, `_download_with_ytdlp_and_mux`, and `download_media_file`) to download split video + audio tracks when no high-res pre-muxed stream exists and mux via `AndroidMediaMuxer`, plus added `GalleryDlEngine.ensureRedditAudioMuxed()` fallback for direct `v.redd.it` downloads.

* \[x\] **T1.4 Gate `BrowserProvider` & fix `deduplicationKey()` stream collisions**

  * In `DualMediaExtractor.kt`, gated `BrowserProvider` via `shouldSkipHeadlessBrowser()` when `YtDlpProvider` or `GalleryDlProvider` is applicable and returns >= 1 valid item on a supported platform.

  * In `MediaClassifier.kt`, removed `itag` and `mime` from `sizingParams` in `deduplicationKey()` so video-only and audio-only streams remain distinct.

## Phase 2: Smoothness & Performance

* \[x\] **T2.1 Throttle `ImageDimensionProber` and batch state updates**

  * Updated `ImageDimensionProber` (`MediaScraperDrawer.kt`) and `DownloaderResultsPage` (`DownloaderComponents.kt`) with `Semaphore(4)`, `HttpUrl` validation, `Accept-Encoding: identity`, immediate 8 KB stream closure, and `250ms` / 5-item batched `dynamicDimensions` commits.

* \[x\] **T2.2 Remove blocking `WorkManager` `.get()` calls on queue**

  * Removed synchronous `workManager.getWorkInfosForUniqueWork(...).get()` in `DownloadQueueManager.kt`; replaced with non-blocking in-memory active work tracking and `ExistingWorkPolicy.KEEP` / `ExistingWorkPolicy.REPLACE`.

* \[x\] **T2.3 Wire live Python download progress & deterministic filenames**

  * Passed `PythonProgressBridge` into `gallery_dl_runner.py:download_media_file()` via `yt-dlp` `progress_hooks` and resumable `requests.iter_content()` to update `DownloadActivityManager.updateProgress()` and `WorkManager` progress in real time.

  * Replaced `${System.currentTimeMillis()}_$sanitizedName` in `GalleryDlEngine.kt` with `OkHttpDownloader.generateDeterministicId(mediaUrl, sanitizedName)`.

* \[x\] **T2.4 Optimize headless WebView scrolling**

  * Changed `behavior: 'smooth'` to `behavior: 'instant'` in `MediaCaptureEngine.getAdaptiveScrollScript()` (`MediaCaptureEngine.kt`).

## Phase 3: UX, Dark Mode & Media Handling

* \[ \] **T3.1 Convert `DownloaderResultsPage` & `DownloaderMediaCard` to Material 3 theme tokens**

  * Replace hardcoded light-mode hex colors (`0xFFF1F3F5`, `0xFFE9ECEF`, `0xFFF8F9FA`, `0xFF212529`, `0xFF495057`, `0xFF6C757D`) in `DownloaderComponents.kt` with `MaterialTheme.colorScheme` tokens.

* \[ \] **T3.2 Make `ShareReceiverActivity` a translucent bottom sheet**

  * Update `ShareReceiverScreen` (`ShareReceiverActivity.kt:128`) to present a rounded bottom-anchored sheet over a dim scrim with tap-outside dismissal and a 1-tap quick download flow for single-media shares.

* \[x\] **T3.3 Fix `40 × 40` fallback & audio placeholders**

  * Replaced `"40 × 40"` fallback in `DownloaderMediaCard` (`DownloaderComponents.kt` + `formatScrapedMediaDimensionOrSize` in `MediaScraperDrawer.kt`) with dynamic dimensions, non-generic resolution hints, formatted `sizeBytes` / probed `Content-Length`, or clean source/format labels.

  * Added dedicated audio icon placeholder (`Icons.Default.Audiotrack`) in `DownloaderMediaCard` when an audio item has no artwork thumbnail.

* \[x\] **T3.4 Fix audio item classification & playback in Saved Stacks & Viewer**

  * Added `MediaClassifier.resolveLocalMediaMimeType()` and `MediaClassifier.isAudioMimeOrUri()` covering `.mp3`, `.m4a`, `.aac`, `.wav`, `.ogg`, `.opus`, `.flac`, `.wma`, video formats, and image formats.

  * Updated `GalleryApp.kt` (`onOpenMediaItem`, `onOpenMediaWithList`, `shareMultipleMediaItems`), `ViewerScreen.kt` (ExoPlayer audio playback + `VideoPlayerControlsBar` + centered Audio Player Card in `ZoomableMediaItem`), and `MediaInboxScreen.kt` (`ImageStackCard`, `extractedDescription`, `DetailTagChip`, `RealDeckMediaCard`).

## Phase 4: Customizability & Persistent Settings

* \[ \] **T4.1 Create `DownloaderPreferencesManager` & expose via `PreferencesRepository`**

  * Add `app/src/main/java/com/amresalehin/emregallery/data/preferences/DownloaderPreferencesManager.kt` storing:

    * `videoQualityCap` (`BEST`, `1080P`, `720P`, `480P`, `360P`)

    * `audioOnlyMode` + `preferredAudioFormat` (`M4A`, `MP3`, `OPUS`)

    * `minKbFilter`, `minWidthFilter`, `minHeightFilter`, `showSvg`

    * `subfolderMode` (`FLAT`, `BY_DOMAIN`, `BY_STACK`) & `filenameTemplate`

    * `wifiOnlyDownloads`, `maxConcurrentDownloads`

    * `enabledProviders` (`gallery-dl`, `yt-dlp`, `static`, `browser`)

    * `searchEngine` (`BrowserSearchEngine`)

* \[ \] **T4.2 Add Min KB filter UI & Quality/Audio-Only picker**

  * Add the missing `"Hide media smaller than __ KB"` control to `DownloaderResultsPage`'s filter drawer (`DownloaderComponents.kt:1548`).

  * Add a compact Quality / Audio-Only selector pill on `DownloaderResultsPage` and `ShareReceiverActivity`.

* \[ \] **T4.3 Add Downloader Settings sheet & wire preferences into workers/browser**

  * Add a Downloader Settings bottom sheet accessible from `DownloaderSearchMainPage` and `DownloaderActivityPage`.

  * Apply `wifiOnlyDownloads` (`NetworkType.UNMETERED`) in `DownloadQueueManager`, `subfolderMode` + `filenameTemplate` in `OkHttpDownloader` / `GalleryDlEngine`, and `searchEngine` in `DownloaderSearchMainPage` & `DirectBrowserView`.

## Phase 5: Verification

* \[x\] **T5.1 Add unit tests for Phase 1 & Phase 2 fixes**

  * Expanded `MediaDownloaderTest.kt` and `MediaDeduplicationPureUnitTest.kt` to cover `GalleryDlEngine` provenance attribution, Reddit/X `YtDlpProvider` matching, `AndroidMediaMuxer` Reddit audio resolution, `DualMediaExtractor.shouldSkipHeadlessBrowser`, `deduplicationKey` `itag`/`mime` stream separation, `MediaCaptureEngine` instant scroll, and `ImageDimensionProber` `Accept-Encoding: identity` header probing.
* \[x\] **T5.2 Fix R8/ProGuard release rules, Manifest `READ_MEDIA_AUDIO`, and `applicationId`**

  * Added `@Keep` annotations and explicit ProGuard rules (`app/proguard-rules.pro`) for `PythonProgressBridge`, `AndroidMediaMuxer`, `GalleryDlEngine`, `BrowserMediaExtractor$EmreMediaBridge` (`@JavascriptInterface`), `MediaDownloadWorker`, Chaquopy, and ML Kit.

  * Added `android.permission.READ_MEDIA_AUDIO` to `AndroidManifest.xml` and set `applicationId = "com.amresalehin.emregallery"` in `app/build.gradle.kts`.
