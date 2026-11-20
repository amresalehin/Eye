package com.amresalehin.emregallery.data

import android.content.Context
import android.os.Environment
import android.util.Log
import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Shared session HTTP client, cookie synchronization bridge, HTML page fetcher (for DOM scraping),
 * and deterministic download storage helpers.
 * All actual media file downloads are routed exclusively through yt-dlp and gallery-dl in [GalleryDlEngine].
 */
object OkHttpDownloader {

    private const val TAG = "OkHttpDownloader"
    const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    /**
     * Shared bidirectional CookieJar bridging Android WebView CookieManager and in-memory cache.
     */
    class SharedSessionCookieJar : CookieJar {
        private val cookieLock = Any()
        private val inMemoryCookies = ConcurrentHashMap<String, MutableList<Cookie>>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            if (cookies.isEmpty()) return
            val host = url.host
            val urlString = url.toString()

            synchronized(cookieLock) {
                val currentList = inMemoryCookies.getOrPut(host) { mutableListOf() }
                for (newCookie in cookies) {
                    currentList.removeAll { it.name == newCookie.name }
                    currentList.add(newCookie)
                }

                // Sync into Android WebView CookieManager if available
                try {
                    val cookieManager = CookieManager.getInstance()
                    for (cookie in cookies) {
                        cookieManager.setCookie(urlString, cookie.toString())
                    }
                    cookieManager.flush()
                } catch (_: Throwable) {
                    // Ignore in non-Android or JVM unit test environments
                }
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val host = url.host
            val urlString = url.toString()
            val now = System.currentTimeMillis()

            synchronized(cookieLock) {
                val result = mutableListOf<Cookie>()

                // 1. Load from in-memory cache
                inMemoryCookies[host]?.let { list ->
                    list.removeAll { it.expiresAt <= now }
                    result.addAll(list)
                }

                // 2. Load from Android WebView CookieManager if available
                try {
                    val cookieManager = CookieManager.getInstance()
                    val rawCookieStr = cookieManager.getCookie(urlString)
                    if (!rawCookieStr.isNullOrBlank()) {
                        val parsed = rawCookieStr.split(";").mapNotNull { part ->
                            val trimmed = part.trim()
                            if (trimmed.isNotEmpty()) {
                                Cookie.parse(url, trimmed)
                            } else null
                        }
                        for (c in parsed) {
                            if (result.none { it.name == c.name }) {
                                result.add(c)
                            }
                        }
                    }
                } catch (_: Throwable) {
                    // Ignore in unit test environments
                }

                return result
            }
        }

        fun getCookieString(url: String): String {
            val httpUrl = url.toHttpUrlOrNull() ?: return ""
            return loadForRequest(httpUrl).joinToString("; ") { "${it.name}=${it.value}" }
        }

        fun setManualCookie(url: String, cookieName: String, cookieValue: String) {
            val httpUrl = url.toHttpUrlOrNull() ?: return
            val cookie = Cookie.Builder()
                .domain(httpUrl.host)
                .path("/")
                .name(cookieName)
                .value(cookieValue)
                .build()
            saveFromResponse(httpUrl, listOf(cookie))
        }

        fun clearSession() {
            synchronized(cookieLock) {
                inMemoryCookies.clear()
                try {
                    val cm = CookieManager.getInstance()
                    cm.removeAllCookies(null)
                    cm.flush()
                } catch (_: Throwable) {}
            }
        }
    }

    val sharedCookieJar = SharedSessionCookieJar()

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(sharedCookieJar)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor { chain ->
                val original = chain.request()
                val requestBuilder = original.newBuilder()

                if (original.header("User-Agent").isNullOrBlank()) {
                    requestBuilder.header("User-Agent", DEFAULT_USER_AGENT)
                }
                if (original.header("Accept").isNullOrBlank()) {
                    requestBuilder.header(
                        "Accept",
                        "text/html,application/xhtml+xml,application/xml;q=0.9,image/*;q=0.8,video/*;q=0.8,audio/*;q=0.8,*/*;q=0.7"
                    )
                }
                chain.proceed(requestBuilder.build())
            }
            .build()
    }

    data class HttpResponse(
        val isSuccessful: Boolean,
        val statusCode: Int,
        val contentType: String,
        val finalUrl: String,
        val bodyString: String? = null,
        val errorMessage: String? = null
    )

    /**
     * Parses the HTTP 'Retry-After' header (RFC 7231/9110) as seconds or HTTP-date.
     */
    fun parseRetryAfterHeader(header: String?): Long? {
        if (header.isNullOrBlank()) return null
        val trimmed = header.trim()
        val seconds = trimmed.toLongOrNull()
        if (seconds != null) {
            return seconds.coerceIn(1L, 300L)
        }
        return try {
            val format = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US)
            val date = format.parse(trimmed)
            if (date != null) {
                val diffSec = (date.time - System.currentTimeMillis()) / 1000L
                if (diffSec > 0) diffSec.coerceIn(1L, 300L) else null
            } else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Executes an HTTP GET request with OkHttp, following redirects and utilizing shared session cookies.
     */
    suspend fun getHtml(
        url: String,
        referer: String? = null,
        maxBytes: Long = 2 * 1024 * 1024
    ): HttpResponse = withContext(Dispatchers.IO) {
        val reqBuilder = Request.Builder().url(url)
        if (!referer.isNullOrBlank()) {
            reqBuilder.header("Referer", referer)
        }

        try {
            client.newCall(reqBuilder.build()).execute().use { response ->
                val code = response.code
                val contentType = response.header("Content-Type")?.lowercase() ?: ""
                val finalUrl = response.request.url.toString()

                if (!response.isSuccessful) {
                    return@withContext HttpResponse(
                        isSuccessful = false,
                        statusCode = code,
                        contentType = contentType,
                        finalUrl = finalUrl,
                        errorMessage = "Server returned HTTP status $code"
                    )
                }

                val body = response.body
                val responseString = if (body != null) {
                    val source = body.source()
                    source.request(maxBytes)
                    val buffer = source.buffer
                    val toRead = minOf(buffer.size, maxBytes)
                    buffer.clone().readUtf8(toRead)
                } else {
                    ""
                }

                HttpResponse(
                    isSuccessful = true,
                    statusCode = code,
                    contentType = contentType,
                    finalUrl = finalUrl,
                    bodyString = responseString
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "getHtml failed for $url", e)
            HttpResponse(
                isSuccessful = false,
                statusCode = -1,
                contentType = "",
                finalUrl = url,
                errorMessage = e.message ?: "Network request failed"
            )
        }
    }

    /**
     * Generates a deterministic file identifier from URL and file name to persist partial downloads across retries.
     */
    fun generateDeterministicId(mediaUrl: String, fileName: String): String {
        val hash = runCatching {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val bytes = md.digest(mediaUrl.toByteArray(Charsets.UTF_8))
            bytes.take(8).joinToString("") { "%02x".format(it) }
        }.getOrDefault(Integer.toHexString(mediaUrl.hashCode()))
        val base = fileName.substringBeforeLast('.').replace(Regex("[^a-zA-Z0-9_-]"), "_").take(32)
        val ext = fileName.substringAfterLast('.', "")
        return if (ext.isNotEmpty()) "${base}_$hash.$ext" else "${base}_$hash"
    }

    /**
     * Resolves and ensures existence of the target storage directory (public Downloads or app external storage).
     */
    fun getTargetStorageDir(context: Context, subfolder: String? = null): File {
        val publicDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val emreGalleryDir = File(publicDownloads, "Emre Gallery")
        if (!emreGalleryDir.exists()) {
            emreGalleryDir.mkdirs()
        }
        val baseDir = if (emreGalleryDir.exists() && emreGalleryDir.canWrite()) {
            emreGalleryDir
        } else {
            val appExternal = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            val fallbackFolder = File(appExternal ?: context.filesDir, "Emre Gallery")
            if (!fallbackFolder.exists()) fallbackFolder.mkdirs()
            fallbackFolder
        }
        val cleanSub = subfolder?.trim()?.takeIf { it.isNotEmpty() }
        return if (cleanSub != null) {
            val subDir = File(baseDir, cleanSub)
            if (!subDir.exists()) subDir.mkdirs()
            if (subDir.exists() && subDir.canWrite()) subDir else baseDir
        } else {
            baseDir
        }
    }
}
