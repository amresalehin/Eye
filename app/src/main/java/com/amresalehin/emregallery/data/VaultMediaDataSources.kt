@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.amresalehin.emregallery.data

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.webkit.MimeTypeMap
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import coil.ImageLoader
import coil.decode.DataSource as CoilDataSource
import coil.decode.ImageSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import coil.size.Dimension
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.buffer
import okio.source
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * Coil Fetcher that decrypts Locked Vault photos and video thumbnails purely in memory
 * using [VaultCrypto] without ever writing plaintext temporary files to storage or cache.
 */
class VaultImageFetcher(
    private val context: Context,
    private val file: File,
    private val options: Options
) : Fetcher {

    override suspend fun fetch(): FetchResult? = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext null

        val cleanName = file.name.removeSuffix(".enc").lowercase(Locale.ROOT)
        val ext = cleanName.substringAfterLast('.', "")
        val isVideo = ext in VIDEO_EXTENSIONS

        if (isVideo) {
            val reader = VaultCrypto.openChunkReader(file)
            val retriever = MediaMetadataRetriever()
            try {
                if (reader != null) {
                    retriever.setDataSource(object : MediaDataSource() {
                        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                            return reader.readAt(position, buffer, offset, size)
                        }

                        override fun getSize(): Long = reader.plaintextLength

                        override fun close() {
                            reader.close()
                        }
                    })
                } else {
                    retriever.setDataSource(file.absolutePath)
                }

                val widthDim = options.size.width
                val heightDim = options.size.height
                val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 &&
                    widthDim is Dimension.Pixels && heightDim is Dimension.Pixels
                ) {
                    retriever.getScaledFrameAtTime(
                        -1L,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        widthDim.px,
                        heightDim.px
                    ) ?: retriever.getFrameAtTime(-1L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                } else {
                    retriever.getFrameAtTime(-1L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                } ?: return@withContext null

                DrawableResult(
                    drawable = BitmapDrawable(context.resources, bitmap),
                    isSampled = true,
                    dataSource = CoilDataSource.DISK
                )
            } finally {
                runCatching { retriever.release() }
                runCatching { reader?.close() }
            }
        } else {
            val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            val bufferedSource = VaultCrypto.openDecryptedInputStream(file).source().buffer()
            SourceResult(
                source = ImageSource(bufferedSource, context),
                mimeType = mimeType,
                dataSource = CoilDataSource.DISK
            )
        }
    }

    class Factory(private val context: Context) : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (data.scheme != "file") return null
            if (!LockedVaultManager.isVaultFileUri(context, data.toString())) return null
            val path = data.path ?: return null
            val file = File(path)
            if (!VaultCrypto.isEncryptedVaultFile(file)) return null
            return VaultImageFetcher(context, file, options)
        }
    }

    companion object {
        private val VIDEO_EXTENSIONS = setOf("mp4", "webm", "mkv", "mov", "avi", "3gp", "ts", "m4v")
    }
}

/**
 * Media3 [DataSource.Factory] that routes encrypted Locked Vault URIs to [VaultEncryptedDataSource]
 * for zero-temp-file random-access video playback, while passing all other URIs to [fallbackFactory].
 */
class VaultAwareDataSourceFactory(
    private val context: Context,
    private val fallbackFactory: DataSource.Factory
) : DataSource.Factory {

    override fun createDataSource(): DataSource {
        return RoutingVaultDataSource(context.applicationContext, fallbackFactory.createDataSource())
    }

    private class RoutingVaultDataSource(
        private val context: Context,
        private val fallback: DataSource
    ) : DataSource {
        private val encryptedDataSource = VaultEncryptedDataSource()
        private var active: DataSource? = null
        private val pendingListeners = mutableListOf<TransferListener>()

        override fun addTransferListener(transferListener: TransferListener) {
            pendingListeners.add(transferListener)
            fallback.addTransferListener(transferListener)
            encryptedDataSource.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            val uri = dataSpec.uri
            val useEncrypted = uri.scheme == "file" &&
                LockedVaultManager.isVaultFileUri(context, uri.toString()) &&
                uri.path?.let { VaultCrypto.isEncryptedVaultFile(File(it)) } == true
            val delegate = if (useEncrypted) encryptedDataSource else fallback
            active = delegate
            return delegate.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            return active?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT
        }

        override fun getUri(): Uri? = active?.uri

        override fun getResponseHeaders(): Map<String, List<String>> {
            return active?.responseHeaders ?: emptyMap()
        }

        override fun close() {
            try {
                active?.close()
            } finally {
                active = null
            }
        }
    }
}

/**
 * Seekable Media3 [BaseDataSource] decrypting "EMREVLT1" vault files chunk-by-chunk on demand.
 */
class VaultEncryptedDataSource : BaseDataSource(/* isNetwork = */ false) {

    private var uri: Uri? = null
    private var reader: VaultCrypto.VaultChunkReader? = null
    private var position: Long = 0L
    private var bytesRemaining: Long = 0L
    private var opened: Boolean = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)

        val path = dataSpec.uri.path ?: throw IOException("Missing encrypted vault file path")
        val file = File(path)
        val chunkReader = VaultCrypto.openChunkReader(file)
            ?: throw IOException("File is not an encrypted vault file: $path")
        reader = chunkReader

        val totalLen = chunkReader.plaintextLength
        if (dataSpec.position < 0L || dataSpec.position > totalLen) {
            chunkReader.close()
            reader = null
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }

        position = dataSpec.position
        val available = totalLen - dataSpec.position
        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            minOf(dataSpec.length, available)
        } else {
            available
        }

        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val chunkReader = reader ?: return C.RESULT_END_OF_INPUT
        val toRead = minOf(length.toLong(), bytesRemaining).toInt()
        val bytesRead = chunkReader.readAt(position, buffer, offset, toRead)
        if (bytesRead <= 0) {
            return C.RESULT_END_OF_INPUT
        }
        position += bytesRead
        bytesRemaining -= bytesRead
        bytesTransferred(bytesRead)
        return bytesRead
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        try {
            reader?.close()
        } finally {
            reader = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }
}
