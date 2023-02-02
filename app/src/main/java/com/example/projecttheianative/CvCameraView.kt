package com.example.projecttheianative

import android.content.Context
import android.graphics.*
import android.media.Image
import android.util.AttributeSet
import android.util.Size
import android.view.SurfaceHolder
import android.view.SurfaceView
import org.opencv.core.Core
import org.opencv.core.Mat
import kotlin.math.min

abstract class CvCameraView : SurfaceView, SurfaceHolder.Callback {
    constructor(context: Context) : super(context)
    constructor(context: Context, attr: AttributeSet) : super(context, attr)

    var maxPreviewSize: Size? = null
    var fpsMeasure: FpsMeasure? = null
    var frameListener: (suspend (Mat) -> Mat)? = null

    // Size (4200, 2200) > 4k. If size is small, it will not reconfigure
    // TODO: Check Bitmap Config
    protected val bitmapCache: Bitmap =
        Bitmap.createBitmap(4200, 2200, BITMAP_FORMAT)

    suspend fun drawFrame(img: Image) {
        // withContext(Dispatchers.Default) { img.process(bitmapCache) }
        // Screen turns green, then it freezes

        img.process(bitmapCache)

        val src = Rect(0, 0, bitmapCache.width, bitmapCache.height)

        val scale =
            min(width.toDouble() / bitmapCache.width, height.toDouble() / bitmapCache.height)

        val scaledWt = (scale * bitmapCache.width).toInt()
        val scaledHt = (scale * bitmapCache.height).toInt()

        val adjLeft = (this.width - scaledWt) / 2
        val adjTop = (this.height - scaledHt) / 2

        val dst = Rect(
            adjLeft, adjTop,
            adjLeft + scaledWt,
            adjTop + scaledHt
        )

        val canvas: Canvas? = this@CvCameraView.holder.lockCanvas()
        canvas?.run {
            drawColor(0, PorterDuff.Mode.CLEAR)
            drawBitmap(bitmapCache, src, dst, null)

            fpsMeasure?.measure()?.let { fps ->
                val text = "${fps fmt "%.2f"} FPS ${bitmapCache.width}x${bitmapCache.height}"
                this@run.drawText(text, 20F, 60F, FpsMeasure.PAINT)
            }
        }
        this@CvCameraView.holder.unlockCanvasAndPost(canvas)
    }

    companion object {
        const val TAG = "CvCameraView"
        val BITMAP_FORMAT = Bitmap.Config.ARGB_8888
    }
}

class FpsMeasure(
    private val step: Int = 20,
    private val countType: CountType = CountType.TICK
) {
    enum class CountType { TICK, MILLI, NANO }

    private var framesCount: Int = 0
    private var fps: Double = 0.0

    private val frequency = when (countType) {
        CountType.TICK -> Core.getTickFrequency()
        CountType.MILLI -> 1000.0
        CountType.NANO -> 1e9
    }
    private var prevTime = when (countType) {
        CountType.TICK -> Core.getTickCount()
        CountType.MILLI -> System.currentTimeMillis()
        CountType.NANO -> System.nanoTime()
    }

    fun measure(): Double {
        framesCount = (framesCount + 1) % step

        if (framesCount == 0) {
            val time = when (countType) {
                CountType.TICK -> Core.getTickCount()
                CountType.MILLI -> System.currentTimeMillis()
                CountType.NANO -> System.nanoTime()
            }
            fps = step * frequency / (time - prevTime)
            prevTime = time
        }

        return fps
    }

    companion object {
        const val TAG = "FpsMeter"
        val PAINT = Paint().apply {
            color = Color.RED
            textSize = 40F // px
        }
    }
}
