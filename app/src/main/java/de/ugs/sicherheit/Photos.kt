package de.ugs.sicherheit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Mitarbeiter- und Profilfotos: 256 × 256 PNG ohne Metadaten, verschlüsselt gespeichert. */
object Photos {
    private val cache = LruCache<String, Any>(256)
    var revision by mutableIntStateOf(0)
        private set

    fun invalidate() {
        cache.evictAll()
        revision++
    }

    /** Quadratischer, richtig gedrehter Ausschnitt; Metadaten (Ort, Kamera) entfallen. */
    fun thumbnail(c: Context, uri: Uri): ByteArray {
        val size = ImportService.size(c, uri)
        require(size <= FileLimits.FILE_BYTES) { "Maximal ${FileLimits.FILE_LABEL} erlaubt." }
        val bitmap =
            if (Build.VERSION.SDK_INT >= 28)
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(c.contentResolver, uri)) { d, info, _ ->
                    val s = info.size
                    val scale = 512.0 / minOf(s.width, s.height).coerceAtLeast(1)
                    if (scale < 1) d.setTargetSize((s.width * scale).toInt().coerceAtLeast(1), (s.height * scale).toInt().coerceAtLeast(1))
                    d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            else
                c.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 4 })
                } ?: error("Bild kann nicht gelesen werden.")
        return square(bitmap ?: error("Bild kann nicht gelesen werden."))
    }

    fun square(bitmap: Bitmap): ByteArray {
        val side = minOf(bitmap.width, bitmap.height)
        require(side > 0) { "Bild kann nicht gelesen werden." }
        val crop = Bitmap.createBitmap(bitmap, (bitmap.width - side) / 2, (bitmap.height - side) / 2, side, side)
        val scaled = Bitmap.createScaledBitmap(crop, 256, 256, true)
        return ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    suspend fun load(vm: UGSViewModel, id: String): ImageBitmap? {
        cache.get(id)?.let { return it as? ImageBitmap }
        val bytes = withContext(Dispatchers.IO) { runCatching { vm.repo.photo(id) }.getOrNull() }
        val image = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
        cache.put(id, image ?: Unit)
        return image
    }

    private var atlas: List<ImageBitmap>? = null

    /** Zwölf gezeichnete Avatare (je vier männlich, weiblich, neutral). */
    fun atlas(c: Context): List<ImageBitmap> =
        atlas
            ?: runCatching {
                    val source = c.assets.open("avatars/ugs-worker-avatars.png").use { BitmapFactory.decodeStream(it) }
                    (0 until 12).map { i ->
                        val col = i % 4
                        val row = i / 4
                        val l = col * source.width / 4
                        val t = row * source.height / 3
                        val r = (col + 1) * source.width / 4
                        val b = (row + 1) * source.height / 3
                        Bitmap.createScaledBitmap(Bitmap.createBitmap(source, l, t, r - l, b - t), 160, 160, true)
                            .asImageBitmap()
                    }
                }
                .getOrDefault(emptyList())
                .also { atlas = it }
}

fun StatusTone.color(): Color =
    when (this) {
        StatusTone.GREEN -> Color(0xFF34C759)
        StatusTone.GRAY -> Color(0xFF8E8E93)
        StatusTone.RED -> Color(0xFFFF3B30)
        StatusTone.ORANGE -> Color(0xFFFF9500)
        StatusTone.YELLOW -> Color(0xFFE6B800)
        StatusTone.BLUE -> Color(0xFF007AFF)
    }

@Composable
fun CircleAvatar(image: ImageBitmap?, name: String, tone: StatusTone, size: Dp, status: String = "") {
    val color = tone.color()
    Box(
        Modifier.size(size).semantics { contentDescription = "$name${if (status.isNotBlank()) ", $status" else ""}" }
    ) {
        Box(
            Modifier.fillMaxSize()
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = .06f))
                .border(1.5.dp, color, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (image != null)
                Image(image, null, Modifier.fillMaxSize().clip(CircleShape), contentScale = ContentScale.Crop)
            else
                Text(
                    name.trim().take(2).uppercase(),
                    fontSize = (size.value * .3f).sp,
                    fontWeight = FontWeight.SemiBold,
                )
        }
        Box(
            Modifier.size(size * .27f)
                .align(Alignment.BottomEnd)
                .clip(CircleShape)
                .background(color)
                .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
        )
    }
}

@Composable
fun WorkerAvatar(vm: UGSViewModel, worker: Entry, size: Dp = 40.dp) {
    val status = vm.status(worker)
    var photo by remember(worker.id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(worker.id, Photos.revision) { photo = Photos.load(vm, worker.id) }
    val art = Photos.atlas(vm.app)
    val index = Avatars.index(Avatars.group(worker["gender"], worker["salutation"]), worker.id)
    CircleAvatar(photo ?: art.getOrNull(index), worker.title, StatusTone.of(status), size, status)
}

@Composable
fun UserAvatar(vm: UGSViewModel, size: Dp = 32.dp) {
    val id = "user-${vm.user?.id}"
    var photo by remember(id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(id, Photos.revision) { photo = Photos.load(vm, id) }
    CircleAvatar(photo, vm.user?.username.orEmpty(), StatusTone.BLUE, size)
}

@Composable
fun StatusBadge(status: String) {
    val c = StatusTone.of(status).color()
    Text(
        status.ifBlank { "—" },
        Modifier.clip(RoundedCornerShape(6.dp)).background(c.copy(alpha = .15f)).padding(horizontal = 8.dp, vertical = 4.dp),
        color = c,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
    )
}
