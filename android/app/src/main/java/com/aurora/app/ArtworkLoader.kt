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
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.util.Size
import android.widget.ImageView
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Handles real album art extraction from MediaStore / audio files,
 * with high-performance in-memory caching and aesthetic Aurora cover generation
 * for tracks without embedded artwork.
 */
object ArtworkLoader {

    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = maxMemory / 8
    private val memoryCache = object : LruCache<String, Bitmap>(cacheSize) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int {
            return bitmap.byteCount / 1024
        }
    }

    private val executor = Executors.newFixedThreadPool(3)
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Synchronously returns artwork bitmap (from cache, MediaStore extraction, or Aurora generative art).
     * Ideal for MediaSession and Notification lock-screen art.
     */
    fun getArtworkBitmap(context: Context, track: Track, targetSizePx: Int = 512): Bitmap {
        val cacheKey = "track_${track.id}_${track.albumId}_$targetSizePx"
        val cached = memoryCache.get(cacheKey)
        if (cached != null) {
            return cached
        }

        var bitmap: Bitmap? = null
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                bitmap = context.contentResolver.loadThumbnail(
                    track.uri,
                    Size(targetSizePx, targetSizePx),
                    null
                )
            } else {
                val artworkUri = ContentUris.withAppendedId(
                    Uri.parse("content://media/external/audio/albumart"),
                    track.albumId
                )
                context.contentResolver.openInputStream(artworkUri)?.use { input ->
                    bitmap = BitmapFactory.decodeStream(input)
                }
            }
        } catch (_: Exception) {
            // Fallback to generative Aurora art
        }

        if (bitmap == null) {
            bitmap = generateAuroraArtwork(track.title, track.artist, targetSizePx)
        }

        memoryCache.put(cacheKey, bitmap)
        return bitmap
    }

    /**
     * Asynchronously loads artwork into an ImageView with smooth tagging to avoid recycling race conditions.
     */
    fun loadArtwork(
        context: Context,
        track: Track,
        targetSizePx: Int,
        imageView: ImageView
    ) {
        val cacheKey = "track_${track.id}_${track.albumId}_$targetSizePx"
        val cached = memoryCache.get(cacheKey)
        if (cached != null) {
            imageView.setImageBitmap(cached)
            return
        }

        imageView.tag = cacheKey

        executor.execute {
            val bitmap = getArtworkBitmap(context, track, targetSizePx)
            mainHandler.post {
                if (imageView.tag == cacheKey) {
                    imageView.setImageBitmap(bitmap)
                }
            }
        }
    }

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
