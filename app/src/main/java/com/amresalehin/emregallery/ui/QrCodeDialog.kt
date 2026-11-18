package com.amresalehin.emregallery.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Pure-Kotlin ISO/IEC 18004 QR Code encoder (Byte mode, ECC Level L, Versions 1..10).
 * Zero external dependencies — encodes media URLs up to 271 bytes into a scannable QR matrix.
 */
object QrCodeEncoder {

    // Total codewords, EC codewords per block, number of blocks for Versions 1..10 (ECC Level L)
    private data class VersionSpec(
        val version: Int,
        val size: Int,
        val dataCodewords: Int,
        val ecPerBlock: Int,
        val blocks: Int,
        val alignmentCenters: IntArray
    )

    private val VERSIONS_L = arrayOf(
        VersionSpec(1, 21, 19, 7, 1, intArrayOf()),
        VersionSpec(2, 25, 34, 10, 1, intArrayOf(6, 18)),
        VersionSpec(3, 29, 55, 15, 1, intArrayOf(6, 22)),
        VersionSpec(4, 33, 80, 20, 1, intArrayOf(6, 26)),
        VersionSpec(5, 37, 108, 26, 1, intArrayOf(6, 30)),
        VersionSpec(6, 41, 136, 18, 2, intArrayOf(6, 34)),
        VersionSpec(7, 45, 156, 20, 2, intArrayOf(6, 22, 38)),
        VersionSpec(8, 49, 194, 24, 2, intArrayOf(6, 24, 42)),
        VersionSpec(9, 53, 232, 30, 2, intArrayOf(6, 26, 46)),
        VersionSpec(10, 57, 274, 18, 4, intArrayOf(6, 28, 50))
    )

    // Precomputed GF(256) log/exp tables with primitive polynomial 0x11D
    private val gfExp = IntArray(512)
    private val gfLog = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) {
            gfExp[i] = x
            gfLog[x] = i
            x = x shl 1
            if ((x and 0x100) != 0) x = x xor 0x11D
        }
        for (i in 255 until 512) {
            gfExp[i] = gfExp[i - 255]
        }
    }

    private fun gfMul(a: Int, b: Int): Int {
        if (a == 0 || b == 0) return 0
        return gfExp[gfLog[a] + gfLog[b]]
    }

    private fun rsGeneratorPoly(degree: Int): IntArray {
        var poly = intArrayOf(1)
        for (i in 0 until degree) {
            val next = IntArray(poly.size + 1)
            val factor = gfExp[i]
            for (j in poly.indices) {
                next[j] = next[j] xor poly[j]
                next[j + 1] = next[j + 1] xor gfMul(poly[j], factor)
            }
            poly = next
        }
        return poly
    }

    private fun rsComputeEc(data: ByteArray, ecCount: Int): ByteArray {
        val gen = rsGeneratorPoly(ecCount)
        val remainder = IntArray(ecCount)
        for (b in data) {
            val factor = (b.toInt() and 0xFF) xor remainder[0]
            System.arraycopy(remainder, 1, remainder, 0, ecCount - 1)
            remainder[ecCount - 1] = 0
            for (j in 0 until ecCount) {
                remainder[j] = remainder[j] xor gfMul(gen[j + 1], factor)
            }
        }
        return ByteArray(ecCount) { remainder[it].toByte() }
    }

    /**
     * Encodes [text] into a square boolean matrix (true = dark module, false = light module).
     * Returns null if the payload exceeds Version 10 ECC-L capacity (271 bytes).
     */
    fun encode(text: String): Array<BooleanArray>? {
        val payload = text.toByteArray(Charsets.UTF_8)
        val spec = VERSIONS_L.firstOrNull { v ->
            val countBits = if (v.version <= 9) 8 else 16
            val neededBits = 4 + countBits + (payload.size * 8)
            neededBits <= v.dataCodewords * 8
        } ?: return null

        // 1. Build data bit stream (Byte mode 0100)
        val bits = ArrayList<Boolean>(spec.dataCodewords * 8)
        fun appendBits(value: Int, length: Int) {
            for (i in length - 1 downTo 0) {
                bits.add(((value ushr i) and 1) != 0)
            }
        }
        appendBits(0b0100, 4)
        appendBits(payload.size, if (spec.version <= 9) 8 else 16)
        for (b in payload) appendBits(b.toInt() and 0xFF, 8)

        // Terminator & byte alignment
        val maxBits = spec.dataCodewords * 8
        val termLen = minOf(4, maxBits - bits.size)
        appendBits(0, termLen)
        while (bits.size % 8 != 0) bits.add(false)

        val dataBytes = ByteArray(spec.dataCodewords)
        var byteIdx = 0
        while (byteIdx * 8 < bits.size) {
            var v = 0
            for (b in 0 until 8) {
                v = (v shl 1) or (if (bits[byteIdx * 8 + b]) 1 else 0)
            }
            dataBytes[byteIdx++] = v.toByte()
        }
        var padToggle = true
        while (byteIdx < spec.dataCodewords) {
            dataBytes[byteIdx++] = if (padToggle) 0xEC.toByte() else 0x11.toByte()
            padToggle = !padToggle
        }

        // 2. Split into RS blocks & interleave
        val numBlocks = spec.blocks
        val shortBlockLen = spec.dataCodewords / numBlocks
        val numLongBlocks = spec.dataCodewords % numBlocks
        val numShortBlocks = numBlocks - numLongBlocks

        val dataBlocks = ArrayList<ByteArray>(numBlocks)
        val ecBlocks = ArrayList<ByteArray>(numBlocks)
        var offset = 0
        for (b in 0 until numBlocks) {
            val len = shortBlockLen + if (b >= numShortBlocks) 1 else 0
            val block = dataBytes.copyOfRange(offset, offset + len)
            offset += len
            dataBlocks.add(block)
            ecBlocks.add(rsComputeEc(block, spec.ecPerBlock))
        }

        val finalCodewords = ArrayList<Byte>(spec.dataCodewords + numBlocks * spec.ecPerBlock)
        val maxDataLen = shortBlockLen + if (numLongBlocks > 0) 1 else 0
        for (i in 0 until maxDataLen) {
            for (block in dataBlocks) {
                if (i < block.size) finalCodewords.add(block[i])
            }
        }
        for (i in 0 until spec.ecPerBlock) {
            for (ec in ecBlocks) {
                finalCodewords.add(ec[i])
            }
        }

        // 3. Place function patterns
        val n = spec.size
        val modules = Array(n) { BooleanArray(n) }
        val isFunction = Array(n) { BooleanArray(n) }

        fun setFunc(r: Int, c: Int, dark: Boolean) {
            if (r in 0 until n && c in 0 until n) {
                modules[r][c] = dark
                isFunction[r][c] = true
            }
        }

        fun drawFinder(top: Int, left: Int) {
            for (dr in -1..7) {
                for (dc in -1..7) {
                    val r = top + dr
                    val c = left + dc
                    if (r !in 0 until n || c !in 0 until n) continue
                    val dark = dr in 0..6 && dc in 0..6 &&
                        (dr == 0 || dr == 6 || dc == 0 || dc == 6 || (dr in 2..4 && dc in 2..4))
                    setFunc(r, c, dark)
                }
            }
        }

        drawFinder(0, 0)
        drawFinder(0, n - 7)
        drawFinder(n - 7, 0)

        // Timing patterns
        for (i in 8 until n - 8) {
            setFunc(6, i, i % 2 == 0)
            setFunc(i, 6, i % 2 == 0)
        }

        // Alignment patterns
        val centers = spec.alignmentCenters
        for (cr in centers) {
            for (cc in centers) {
                if ((cr == 6 && cc == 6) || (cr == 6 && cc == n - 7) || (cr == n - 7 && cc == 6)) continue
                for (dr in -2..2) {
                    for (dc in -2..2) {
                        val dark = maxOf(kotlin.math.abs(dr), kotlin.math.abs(dc)) != 1
                        setFunc(cr + dr, cc + dc, dark)
                    }
                }
            }
        }

        // Reserve format info areas & dark module
        for (i in 0..8) {
            if (i != 6) {
                setFunc(8, i, false)
                setFunc(i, 8, false)
            }
        }
        for (i in 0..7) {
            setFunc(8, n - 1 - i, false)
            setFunc(n - 1 - i, 8, false)
        }
        setFunc(n - 8, 8, true) // Dark module

        // Version info for Version >= 7
        if (spec.version >= 7) {
            val verBits = computeVersionInfo(spec.version)
            for (i in 0 until 18) {
                val bit = ((verBits ushr i) and 1) != 0
                val a = n - 11 + (i % 3)
                val b = i / 3
                setFunc(a, b, bit)
                setFunc(b, a, bit)
            }
        }

        // 4. Place data bits in zigzag
        var bitIndex = 0
        val totalDataBits = finalCodewords.size * 8
        var col = n - 1
        var upward = true
        while (col > 0) {
            if (col == 6) col--
            for (step in 0 until n) {
                val row = if (upward) n - 1 - step else step
                for (dc in 0..1) {
                    val c = col - dc
                    if (!isFunction[row][c]) {
                        val dark = if (bitIndex < totalDataBits) {
                            val cw = finalCodewords[bitIndex / 8].toInt() and 0xFF
                            ((cw ushr (7 - (bitIndex % 8))) and 1) != 0
                        } else false
                        // Apply Mask 0: (row + c) % 2 == 0
                        modules[row][c] = if ((row + c) % 2 == 0) !dark else dark
                        bitIndex++
                    }
                }
            }
            col -= 2
            upward = !upward
        }

        // 5. Write format info for ECC Level L (01) + Mask 0 (000) -> 01000
        val formatBits = computeFormatInfo(0b01000)
        for (i in 0..5) setFunc(8, i, ((formatBits ushr (14 - i)) and 1) != 0)
        setFunc(8, 7, ((formatBits ushr 8) and 1) != 0)
        setFunc(8, 8, ((formatBits ushr 7) and 1) != 0)
        setFunc(7, 8, ((formatBits ushr 6) and 1) != 0)
        for (i in 9..14) setFunc(14 - i, 8, ((formatBits ushr (14 - i)) and 1) != 0)

        for (i in 0..7) setFunc(8, n - 1 - i, ((formatBits ushr i) and 1) != 0)
        for (i in 0..6) setFunc(n - 7 + i, 8, ((formatBits ushr (14 - i)) and 1) != 0)

        return modules
    }

    private fun computeFormatInfo(data5: Int): Int {
        var rem = data5 shl 10
        val gen = 0b10100110111
        for (i in 14 downTo 10) {
            if (((rem ushr i) and 1) != 0) {
                rem = rem xor (gen shl (i - 10))
            }
        }
        return ((data5 shl 10) or rem) xor 0b101010000010010
    }

    private fun computeVersionInfo(version: Int): Int {
        var rem = version shl 12
        val gen = 0b1111100100101
        for (i in 17 downTo 12) {
            if (((rem ushr i) and 1) != 0) {
                rem = rem xor (gen shl (i - 12))
            }
        }
        return (version shl 12) or rem
    }
}

/**
 * Material 3 dialog displaying a scannable QR code for transferring a detected media link
 * to another device, with Copy Link and Share actions.
 */
@Composable
fun QrCodeShareDialog(
    title: String,
    url: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val matrix = remember(url) { QrCodeEncoder.encode(url) }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("qr_code_share_dialog"),
        title = {
            Text(
                text = "Share via QR Code",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = title.ifBlank { url },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                if (matrix != null) {
                    Box(
                        modifier = Modifier
                            .size(220.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.White)
                            .padding(14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val dim = matrix.size
                            val cellSize = size.minDimension / dim.toFloat()
                            val offsetX = (size.width - cellSize * dim) / 2f
                            val offsetY = (size.height - cellSize * dim) / 2f
                            for (r in 0 until dim) {
                                for (c in 0 until dim) {
                                    if (matrix[r][c]) {
                                        drawRect(
                                            color = Color.Black,
                                            topLeft = Offset(offsetX + c * cellSize, offsetY + r * cellSize),
                                            size = Size(cellSize + 0.4f, cellSize + 0.4f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "This media link is too long (${url.length} chars) for a compact QR code. Use Copy or Share below.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            cm?.setPrimaryClip(ClipData.newPlainText("Media URL", url))
                            Toast.makeText(context, "Media link copied", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Copy")
                    }

                    Button(
                        onClick = {
                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                putExtra(Intent.EXTRA_TEXT, url)
                                type = "text/plain"
                            }
                            context.startActivity(Intent.createChooser(sendIntent, "Share media link"))
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Share")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}
