package com.aurora.app

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.util.Size
import android.widget.ImageView
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max

/**
 * Handles real album art extraction from MediaStore, embedded file tags and
 * remote online covers, with high-performance in-memory caching, request
 * de-duplication and aesthetic Aurora cover generation as the final fallback.
 */
object ArtworkLoader {

    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = maxMemory / 8
    private val memoryCache = object : LruCache<String, Bitmap>(cacheSize) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int = bitmap.byteCount / 1024
    }

    private val executor = Executors.newFixedThreadPool(3)
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * In-flight requests keyed by cache key. When several views (mini player,
     * full player art/backdrop/glow) ask for the same bitmap we fetch/decode it
     * once and deliver it to every waiter, instead of hammering the network or
     * decoder with duplicates.
     */
    private val inFlight = ConcurrentHashMap<String, MutableList<ImageView>>()

    /**
     * Synchronously returns artwork (from cache, remote fetch, MediaStore,
     * embedded tags or Aurora generative art). Safe to call from a worker
     * thread; used for MediaSession and lock-screen art.
     */
    fun getArtworkBitmap(context: Context, track: Track, targetSizePx: Int = 512): Bitmap {
        val remote = remoteArtwork(track)
        val cacheKey = cacheKey(track, targetSizePx, remote)
        memoryCache.get(cacheKey)?.let { return it }

        var bitmap: Bitmap? = remote?.let { fetchRemoteArtwork(it, targetSizePx) }
        if (bitmap == null) bitmap = loadLocalArtworkBitmap(context, track, targetSizePx)
        if (bitmap == null) bitmap = generateAuroraArtwork(track.title, track.artist, targetSizePx)
        bitmap = normalize(bitmap, targetSizePx)

        memoryCache.put(cacheKey, bitmap)
        return bitmap
    }

    /**
     * Asynchronously loads artwork with a callback on the main thread. Used by
     * the playback service so it never blocks the UI or its own resolver.
     */
    fun loadArtworkBitmap(
        context: Context,
        track: Track,
        targetSizePx: Int,
        onLoaded: (Bitmap) -> Unit
    ) {
        executor.execute {
            val bitmap = getArtworkBitmap(context, track, targetSizePx)
            mainHandler.post { onLoaded(bitmap) }
        }
    }

    /**
     * Asynchronously loads artwork into an [ImageView].
     *
     * When [remoteArtworkUri] (or the track's own artwork URI) is an http(s)
     * URL it is fetched on the worker pool and cached; any failure falls back
     * to the regular track artwork path (embedded art, MediaStore, generative).
     */
    fun loadArtwork(
        context: Context,
        track: Track,
        targetSizePx: Int,
        imageView: ImageView,
        remoteArtworkUri: Uri? = null
    ) {
        val remote = remoteFrom(remoteArtworkUri) ?: remoteArtwork(track)
        val key = cacheKey(track, targetSizePx, remote)

        memoryCache.get(key)?.let { cached ->
            imageView.tag = key
            imageView.setImageBitmap(cached)
            return
        }

        imageView.tag = key

        val owners = synchronized(inFlight) {
            val existing = inFlight[key]
            if (existing != null) {
                existing.add(imageView)
                null
            } else {
                val created = mutableListOf(imageView)
                inFlight[key] = created
                created
            }
        }
        if (owners == null) return // another request is already loading this key

        executor.execute {
            val bitmap = if (remote != null) {
                fetchRemoteArtwork(remote, targetSizePx) ?: getArtworkBitmap(context, track, targetSizePx)
            } else {
                getArtworkBitmap(context, track, targetSizePx)
            }
            memoryCache.put(key, bitmap)
            val targets = synchronized(inFlight) { inFlight.remove(key) } ?: owners
            mainHandler.post {
                targets.forEach { view ->
                    if (view.tag == key) view.setImageBitmap(bitmap)
                }
            }
        }
    }

    // ── Local artwork ─────────────────────────────────────────────────────

    private fun loadLocalArtworkBitmap(context: Context, track: Track, targetSizePx: Int): Bitmap? {
        val scheme = track.uri.scheme?.lowercase()

        // A streaming URL has no cheap local cover: probing it with
        // MediaMetadataRetriever would mean a second network fetch just for
        // artwork, so go straight to the generative fallback instead.
        if (scheme == "http" || scheme == "https") return null

        // 1. MediaStore thumbnail (fast path for content:// audio).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && scheme == "content") {
            try {
                context.contentResolver.loadThumbnail(track.uri, Size(targetSizePx, targetSizePx), null)
                    ?.let { return it }
            } catch (_: Exception) {
                // fall through
            }
        }

        // 2. Embedded cover art (works for local files and content URIs).
        try {
            val retriever = MediaMetadataRetriever()
            try {
                when (scheme) {
                    "content", "android.resource" -> retriever.setDataSource(context, track.uri)
                    else -> track.uri.path?.let { retriever.setDataSource(it) }
                }
                retriever.embeddedPicture?.let { bytes ->
                    return decodeSampled(bytes, targetSizePx)
                }
            } finally {
                runCatching { retriever.release() }
            }
        } catch (_: Exception) {
            // fall through
        }

        // 3. Legacy album-art provider.
        try {
            val artworkUri = ContentUris.withAppendedId(
                Uri.parse("content://media/external/audio/albumart"),
                track.albumId
            )
            context.contentResolver.openInputStream(artworkUri)?.use { input ->
                val bytes = input.readBytes()
                if (bytes.isNotEmpty()) return decodeSampled(bytes, targetSizePx)
            }
        } catch (_: Exception) {
            // fall through
        }

        return null
    }

    // ── Remote artwork ────────────────────────────────────────────────────

    private fun remoteArtwork(track: Track): Uri? = remoteFrom(track.artworkUri)

    private fun remoteFrom(uri: Uri?): Uri? {
        val value = uri?.toString()?.lowercase() ?: return null
        return if (value.startsWith("http://") || value.startsWith("https://")) uri else null
    }

    /** Fetches a remote image without persisting it to disk; null on any failure. */
    private fun fetchRemoteArtwork(remote: Uri, targetSizePx: Int): Bitmap? {
        return try {
            val connection = java.net.URL(remote.toString()).openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.instanceFollowRedirects = true
            connection.doInput = true
            try {
                if (connection.responseCode !in 200..299) return null
                val bytes = connection.inputStream.use { input ->
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                    }
                    out.toByteArray()
                }
                if (bytes.isEmpty()) null else decodeSampled(bytes, targetSizePx)
            } finally {
                connection.disconnect()
            }
        } catch (_: Exception) {
            null
        }
    }

    // ── Bitmap helpers ────────────────────────────────────────────────────

    /** Decodes [bytes], downsampling so the result is near [targetSizePx]. */
    private fun decodeSampled(bytes: ByteArray, targetSizePx: Int): Bitmap? {
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = computeInSampleSize(bounds.outWidth, bounds.outHeight, targetSizePx)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private fun computeInSampleSize(width: Int, height: Int, targetSizePx: Int): Int {
        val target = targetSizePx.coerceAtLeast(1)
        var sample = 1
        var w = width
        var h = height
        while (w / 2 >= target && h / 2 >= target) {
            w /= 2
            h /= 2
            sample *= 2
        }
        return sample
    }

    /** Scales an oversized bitmap down to [targetSizePx]; never upscales. */
    private fun normalize(bitmap: Bitmap, targetSizePx: Int): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= targetSizePx) return bitmap
        val scale = targetSizePx.toFloat() / longest
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true
        )
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    private fun cacheKey(track: Track, targetSizePx: Int, remote: Uri?): String =
        if (remote != null) "remote_${remote}_$targetSizePx"
        else "track_${track.id}_${track.albumId}_$targetSizePx"

    /**
     * Generates a sleek, high-fidelity dark Aurora atmospheric cover.
     */
    fun generateAuroraArtwork(title: String, artist: String, sizePx: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val hash = abs((title + artist).hashCode())

        // Dynamic curated dark aurora palettes
        val colorPalettes = listOf(
            intArrayOf(Color.rgb(15, 10, 28), Color.rgb(45, 18, 72), Color.rgb(184, 116, 255)),
            intArrayOf(Color.rgb(10, 14, 30), Color.rgb(20, 35, 75), Color.rgb(142, 68, 255)),
            intArrayOf(Color.rgb(24, 8, 30), Color.rgb(70, 15, 60), Color.rgb(215, 120, 255)),
            intArrayOf(Color.rgb(8, 18, 25), Color.rgb(18, 48, 65), Color.rgb(105, 145, 255)),
            intArrayOf(Color.rgb(18, 8, 35), Color.rgb(60, 20, 85), Color.rgb(168, 85, 247))
        )
        val palette = colorPalettes[hash % colorPalettes.size]

        // 1. Base Dark Mesh Gradient
        val baseGradient = LinearGradient(
            0f, 0f, sizePx.toFloat(), sizePx.toFloat(),
            palette[0], palette[1],
            Shader.TileMode.CLAMP
        )
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = baseGradient
        }
        canvas.drawRect(0f, 0f, sizePx.toFloat(), sizePx.toFloat(), paint)

        // 2. Ambient Radial Glow Orb
        val glowRadius = sizePx * 0.75f
        val glowCenterX = sizePx * (0.3f + ((hash % 40) / 100f))
        val glowCenterY = sizePx * (0.3f + (((hash / 10) % 40) / 100f))

        val radialShader = RadialGradient(
            glowCenterX, glowCenterY, glowRadius,
            intArrayOf(palette[2] and 0x88FFFFFF.toInt(), palette[1] and 0x44FFFFFF.toInt(), Color.TRANSPARENT),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        paint.shader = radialShader
        canvas.drawCircle(glowCenterX, glowCenterY, glowRadius, paint)

        // 3. Subtle Abstract Aurora Sound Arc / Wave Lines
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = (sizePx * 0.015f).coerceAtLeast(2f)
        paint.color = Color.argb(45, 247, 243, 252)

        val rect = RectF(
            sizePx * 0.15f, sizePx * 0.15f,
            sizePx * 0.85f, sizePx * 0.85f
        )
        canvas.drawArc(rect, (hash % 360).toFloat(), 140f, false, paint)

        paint.color = Color.argb(30, 184, 116, 255)
        paint.strokeWidth = (sizePx * 0.008f).coerceAtLeast(1.5f)
        val rectInner = RectF(
            sizePx * 0.28f, sizePx * 0.28f,
            sizePx * 0.72f, sizePx * 0.72f
        )
        canvas.drawArc(rectInner, ((hash + 90) % 360).toFloat(), 200f, false, paint)

        // 4. Center Glowing Star/Sparkle Accent
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(180, 247, 243, 252)
        val centerPtX = sizePx * 0.5f
        val centerPtY = sizePx * 0.5f
        val dotRadius = (sizePx * 0.025f).coerceAtLeast(3f)
        canvas.drawCircle(centerPtX, centerPtY, dotRadius, paint)

        return bitmap
    }
}
