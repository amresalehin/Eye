# Media Downloader Overhaul Plan (Emre Gallery)

## 1. Goals & Scope
Make the Emre Gallery media downloader **reliable (zero silent/no-audio videos)**, **smooth (60/120Hz UI with no main-thread stalls or recomposition storms)**, **user-friendly (dark-mode native, translucent share sheet, in-place media preview, accurate metadata)**, and **customizable (persistent quality/format presets, size/dimension filters, subfolder templates, concurrency & network constraints, and search engine choice)**.

---

## 2. Architecture & Root-Cause Fixes

### 2.1 Audio & Extraction Pipeline (`DL-01` – `DL-04`)
1. **Accurate Engine Attribution (`GalleryDlEngine.kt`)**
   - In `parseExtractedItems()` (`GalleryDlEngine.kt:217`), stop hardcoding `extractorEngine = "gallery-dl / yt-dlp"`.
   - Use the item's actual `source` (`"yt-dlp"` or `"gallery-dl"`) so `MediaDownloadWorker` (`sourceEngine.contains("yt-dlp", ignoreCase = true)`) reliably routes `yt-dlp` items to the stream/social downloader instead of falling back to raw `OkHttpDownloader`.
2. **Full Social Video Platform Coverage (`DualMediaExtractor.kt`, `gallery_dl_runner.py`, `MediaDownloadWorker.kt`)**
   - Add `reddit.com`, `redd.it`, `v.redd.it`, `twitter.com`, `x.com`, and `fb.watch` to:
     - `YtDlpProvider.isApplicable()` (`DualMediaExtractor.kt:130`)
     - `check_support()` (`gallery_dl_runner.py:84`)
     - `isSocialUrl` (`MediaDownloadWorker.kt:121`)
3. **Separate DASH Video + Audio Muxing (`gallery_dl_runner.py` + `AndroidMediaMuxer.kt`)**
   - When `yt-dlp` encounters platforms serving split DASH video + audio (e.g., Reddit `v.redd.it`, YouTube 1080p+, Instagram Reels) without a pre-muxed HTTP stream:
     - Prefer pre-muxed HLS/HTTP when available at or above the user's selected quality cap, or download the best video-only stream + best audio-only stream (`bv*+ba/b`) as two temporary files (`video_part.mp4`, `audio_part.m4a`).
     - Introduce a lightweight Kotlin helper `AndroidMediaMuxer` (using `android.media.MediaExtractor` + `android.media.MediaMuxer`) invoked from `MediaDownloadWorker` / `GalleryDlEngine` to losslessly mux the video and audio tracks into a single `.mp4` on-device without bundling an external FFmpeg binary.
   - Remove bare `/best` fallback when it would silently pick a video-only DASH stream without attempting audio+video muxing.
4. **Prevent WebView & Deduplication Collisions (`DualMediaExtractor.kt`, `MediaClassifier.kt`)**
   - Gate `BrowserProvider` in `DualMediaExtractor.kt:486`: if `YtDlpProvider` or `GalleryDlProvider` is applicable and succeeds with >= 1 item on a known social video URL, skip launching the headless `WebView` unless the user explicitly triggers **Live Browser**.
   - In `MediaClassifier.deduplicationKey()` (`MediaClassifier.kt:919`), stop stripping `itag` and `mime` query parameters so `/videoplayback` video-only and audio-only streams retain distinct keys.

---

### 2.2 Smoothness & Performance (`DL-05` – `DL-07`)
1. **Throttled Dimension Probing (`DownloaderComponents.kt`, `MediaScraperDrawer.kt`)**
   - Replace the unbounded 50-coroutine loop in `DownloaderResultsPage` (`DownloaderComponents.kt:1085–1099`) with a `Semaphore(4)` concurrency cap.
   - Batch-commit `dynamicDimensions` updates (e.g., every `250ms` or in chunks of 5) instead of mutating Compose `SnapshotStateMap` 50 times individually.
   - Ensure `ImageDimensionProber` uses `HttpUrl` / `Accept-Encoding: identity` cleanly and closes response bodies immediately after reading the 8 KB header.
2. **Non-Blocking WorkManager Queueing (`DownloadQueueManager.kt`)**
   - Remove synchronous `workManager.getWorkInfosForUniqueWork(uniqueWorkName).get()` calls (`DownloadQueueManager.kt:118, 213`) from `enqueueDownload()` and `enqueueBatch()`.
   - Rely directly on `ExistingWorkPolicy.KEEP` (for new items) and `ExistingWorkPolicy.REPLACE` (for retries) so enqueueing never blocks the Main UI thread.
3. **Live Python/yt-dlp Progress & Deterministic Resume (`gallery_dl_runner.py`, `GalleryDlEngine.kt`, `MediaDownloadWorker.kt`)**
   - Pass a Kotlin progress listener bridge to `gallery_dl_runner.py:download_media_file()` and wire `yt-dlp`'s `progress_hooks` + `requests.iter_content()` to `DownloadActivityManager.updateProgress()` and `setProgressAsync()`.
   - Replace `${System.currentTimeMillis()}_$sanitizedName` (`GalleryDlEngine.kt:319`) with `OkHttpDownloader.generateDeterministicId(mediaUrl, sanitizedName)` so interrupted Python downloads can resume cleanly via `.part` / HTTP Range.
4. **Lightweight Headless Browser Extraction (`BrowserMediaExtractor.kt`, `MediaCaptureEngine.kt`)**
   - Replace `window.scrollBy({ top: scrollDelta, behavior: 'smooth' })` (`MediaCaptureEngine.kt:327`) with `behavior: 'instant'` during automated background scraping to avoid 500ms animation overhead per scroll step.

---

### 2.3 User-Friendliness & Visual Polish (`DL-08` – `DL-11`)
1. **Full Dark Mode Support (`DownloaderComponents.kt`)**
   - Replace all hardcoded light-mode colors (`Color(0xFFF1F3F5)`, `Color(0xFFE9ECEF)`, `Color(0xFFF8F9FA)`, `Color(0xFF212529)`, `Color(0xFF495057)`, `Color(0xFF6C757D)`) in `DownloaderResultsPage` and `DownloaderMediaCard` with semantic `MaterialTheme.colorScheme` tokens (`surfaceContainerHigh`, `surfaceContainerHighest`, `onSurface`, `onSurfaceVariant`, `primary`).
2. **Translucent Share Sheet Experience (`ShareReceiverActivity.kt`)**
   - Wrap `ShareReceiverScreen` in a translucent scrim + `ModalBottomSheet` (or rounded bottom-anchored sheet) over the sharing app instead of an opaque full-screen background.
   - Add a **Quick Save** mode for single-item social video/image shares with an inline quality/audio-only pill before queuing.
3. **Accurate Card Metadata & In-Place Preview (`DownloaderComponents.kt`, `MediaInboxScreen.kt`, `GalleryApp.kt`)**
   - Remove the fake `"40 × 40"` fallback in `DownloaderMediaCard` (`DownloaderComponents.kt:1860`); display formatted file size (`sizeBytes`) or source/format when dimensions are unknown.
   - Render a clean audio icon/waveform placeholder for `MediaCategory.AUDIO` in `DownloaderMediaCard` instead of a broken `AsyncImage`.
   - Add a tap-to-preview dialog/lightbox on scraped cards (with checkbox tap dedicated to selection) so users can inspect media before downloading.
   - Fix audio labeling and MIME detection in `MediaInboxScreen.kt` (`1306`, `1847`, `1991`) and `GalleryApp.kt` (`1200–1240`) so `.mp3`, `.m4a`, `.aac`, `.wav`, `.ogg`, `.opus`, and `.flac` display `"Audio"` and open with `mimeType = "audio/*"`.

---

### 2.4 Customizability (`DL-12`)
1. **Persistent `DownloaderPreferencesManager` (`data/preferences/DownloaderPreferencesManager.kt` + `PreferencesRepository.kt`)**
   - Store user preferences in `galleryDataStore`:
     - **Video Quality Cap**: `BEST` (4K/Original), `1080P`, `720P`, `480P`, `360P`
     - **Audio-Only Mode**: Boolean + preferred format (`M4A`, `MP3`, `OPUS`)
     - **Min File Size Filter (KB)**: Persisted `minKbThreshold` (default `1` KB)
     - **Default Min Width / Height & Show SVG**: Persisted filter defaults
     - **Download Folder Organization**: `FLAT` (`Downloads/Emre Gallery`), `BY_DOMAIN` (`Emre Gallery/<domain>`), `BY_STACK` (`Emre Gallery/<stackTitle>`)
     - **Filename Template**: `ORIGINAL`, `TITLE_ONLY`, `DOMAIN_DATE_TITLE`
     - **Network & Concurrency**: `wifiOnlyDownloads: Boolean`, `maxConcurrentDownloads: Int` (1–6)
     - **Enabled Extractors**: Toggles for `gallery-dl`, `yt-dlp`, `Static HTML`, `Headless Browser`
     - **Default Browser Search Engine**: `BrowserSearchEngine` (`DUCKDUCKGO`, `GOOGLE`, `BING`, `WIKIPEDIA`)
2. **UI Exposure**
   - Expose the missing **Min Size (KB)** slider/field and **Max Resolution** option inside `DownloaderResultsPage`'s filter drawer (`DownloaderComponents.kt:1548`).
   - Add a **Quality / Audio-Only** selector pill on `DownloaderResultsPage` and `ShareReceiverActivity` when video/stream items are present.
   - Add a **Downloader Settings** modal sheet accessible from `DownloaderSearchMainPage` and `DownloaderActivityPage` (`Tune` icon).
   - Wire `DirectBrowserView` and `DownloaderSearchMainPage` to read the user's selected `BrowserSearchEngine` instead of hardcoding DuckDuckGo or Google.

---

## 3. Verification Strategy
- **Unit Tests (`MediaDownloaderTest.kt`, `DownloaderSearchPageUnitTest.kt`)**:
  - Verify `GalleryDlEngine` preserves `"yt-dlp"` vs `"gallery-dl"` provenance.
  - Verify `YtDlpProvider.isApplicable()` matches Reddit, X/Twitter, Instagram, TikTok, and YouTube.
  - Verify `MediaClassifier.deduplicationKey()` keeps distinct `itag`/`mime` streams separate.
  - Verify `DownloaderPreferencesManager` DataStore persistence and `DownloadQueueManager` constraint mapping (`NetworkType.UNMETERED` when Wi-Fi-only is enabled).
- **Manual / Device Verification**:
  - Share a Reddit `v.redd.it` video, Instagram Reel, and YouTube Short via `ShareReceiverActivity` and confirm the resulting `.mp4` plays with synchronized audio.
  - Toggle Dark Mode on `DownloaderResultsPage`, verify filter drawer Min KB control, and test `DirectBrowserView` search engine switching.
