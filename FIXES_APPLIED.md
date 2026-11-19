# Eye downloader repair batch

This archive contains a targeted repair of the downloader issues identified in the uploaded source. The changes focus on extraction routing, resolution/audio handling, stream safety, previews, and download-mode persistence.

## Changes made

### Extraction and platform routing
- Connected the Pinterest extractor to the shared Python `extract_media()` router.
- Added Pinterest extraction to the Android `ExtractionCoordinator` fast path and cookie-retry path.
- Added the Pinterest engine wrapper/availability checks to `GalleryDlEngine`.
- Kept Instagram and Reddit dedicated extraction paths intact; the shared route can fall through to yt-dlp/gallery-dl if a dedicated extractor does not return media.

### Video quality and audio
- Changed yt-dlp metadata extraction to prefer the highest available video representation, including video-only formats, rather than preferring a lower-resolution muxed representation.
- Kept separate audio representations available for the native YTDLnis download path.
- Routed yt-dlp-backed discovered video downloads through the Android YTDLnis engine, which downloads companion audio separately and muxes through the in-process FFmpegKit/native muxer path.
- Made the worker honor global Audio Only / Video Only preferences for discovered media.
- Added an Audio action to the discovered-video fullscreen viewer.
- Persisted per-download Audio/Video mode overrides and retained them across automatic and manual retries. Mode-specific queue identities prevent Audio and Video jobs for the same URL from aliasing each other.
- Accepted `SID` as a valid Google authentication cookie key in the YTDLnis cookie validator.

### HLS/DASH safety
- Detects HLS alternate audio renditions and avoids silently reconstructing only the video playlist.
- Detects unsupported `SAMPLE-AES`, unsupported key formats, and `EXT-X-SESSION-KEY` encryption declarations.
- Avoids treating live/incomplete playlists as completed VOD downloads.
- Returns a failure from the limited custom HLS reconstructor for these cases so the worker can try the yt-dlp fallback instead of saving a silently incomplete file.
- Preserves the existing AES-128 segment path.

### GIFV and thumbnails
- Classifies `.gifv` as video, not animated GIF/image.
- Normalizes downloaded GIFV filenames to `.mp4` and avoids requesting a companion audio track for ordinary silent GIFV clips.
- Reuses response-level preview metadata when an individual video has no thumbnail.
- Added a best-effort page poster fallback using Open Graph/Twitter image metadata or an HTML5 `<video poster>` attribute when yt-dlp has no thumbnail.

## Validation

- Python bytecode compilation: passed for all files in `app/src/main/python/`.
- Python tests: **48 passed**.
- Kotlin parser sanity check on the modified Kotlin files: no syntax diagnostics. This check does not replace an Android/Gradle compile because Android dependencies were not available to standalone `kotlinc`.
- Android Gradle build: **not completed**. The Gradle wrapper attempted to download Gradle 9.3.1 from `services.gradle.org`, but DNS resolution failed with `UnknownHostException`.

## Remaining constraints

- Instagram, Pinterest, YouTube, and other services can still require login cookies, rate-limit recovery, account access, region availability, or platform-specific extractor updates. This source repair cannot bypass those external restrictions.
- The custom HLS reconstructor deliberately does not decrypt DRM/SAMPLE-AES streams or capture live playlists. Those are delegated to yt-dlp; protected DRM streams may remain unavailable.
- No APK is included because the Android build could not run in this environment. Install/build verification on an Android device is still required.
