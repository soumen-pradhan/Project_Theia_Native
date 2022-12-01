package com.example.projecttheianative

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.PixelFormat
import android.hardware.camera2.*
import android.hardware.camera2.CameraCaptureSession.CaptureCallback
import android.media.ImageReader
import android.os.Build
import android.util.AttributeSet
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.SurfaceHolder
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

// TODO Rapid Changes in Lifecycle States does not allow cancellation. Camera Disconnected Error.

class KotlinCamera2View : CvCameraView, DefaultLifecycleObserver {
    constructor(context: Context) : super(context)
    constructor(context: Context, attr: AttributeSet) : super(context, attr)

    private val cameraManager by lazy {
        this.context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    // TODO Add a way to change cameras
    private val backCameraId by lazy {
        cameraManager.cameraIdList.firstOrNull()
            ?: throw CameraAccessException(CameraAccessException.CAMERA_DISCONNECTED)
    }

    // TODO Remove Shared State into channel
    // Only SurfaceHolder.Callback.surfaceDestroyed
    private lateinit var imageReader: ImageReader
    private var imageProcessingJob: Job? = null

    private val device = Channel<CameraDevice>(capacity = 1)
    private val session = Channel<CameraCaptureSession>(capacity = 1)
    private val reader = Channel<ImageReader>(capacity = 1)
    private val previewSize = Channel<Size>(capacity = 1)

    init {
        holder.addCallback(this)
    }

    // Assumes increasing order
    private fun calcPreviewSize() = cameraManager.getCameraCharacteristics(backCameraId)
        .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        ?.getOutputSizes(ImageReader::class.java)
        ?.find { (wt, ht) -> (wt <= width && ht <= height) && maxPreviewSize?.let { wt <= it.width && ht <= it.height } ?: true }
        ?: throw CvCamera2Exception("No Preview size fits")

    // Assumes increasing order
    private fun calcFps() = cameraManager.getCameraCharacteristics(backCameraId)
        .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        // ?.onEach { Log.d(TAG, "Possible FPS: [${it.lower}, ${it.upper}]") }
        ?.last()
        ?: throw CvCamera2Exception("No FPS found")

    /** LifeCycleObserver */
    override fun onCreate(owner: LifecycleOwner) {
        super.onCreate(owner)
        Log.d(TAG, "LifecycleObserver: onCreate")
    }

    override fun onStart(owner: LifecycleOwner) {
        super.onStart(owner)
        Log.d(TAG, "LifecycleObserver: onStart")

        // open camera
        owner.lifecycleScope.launch {
            // TODO Check if it is already open or not.
            val cameraDevice = cameraManager.open(backCameraId)
            Log.d(TAG, "Camera Opened")
            device.send(cameraDevice)
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    override fun onResume(owner: LifecycleOwner) {
        super.onResume(owner)
        Log.d(TAG, "LifecycleObserver: onResume")

        // calls SurfaceCreated SurfaceChanged
        visibility = VISIBLE

        // start camera preview
        owner.lifecycleScope.launch {
            val cameraDevice = device.receive()

            // Wait for preview Size coming from SurfaceChanged
            val (wt, ht) = previewSize.receive()

            imageReader = ImageReader.newInstance(wt, ht, READER_FORMAT, 30)

            val captureSession = cameraDevice.captureSession(imageReader.surface)
            val captureRequest =
                cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(imageReader.surface)
                    set(
                        CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                    )
                    set(
                        CaptureRequest.CONTROL_AE_MODE,
                        CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH
                    )
                    set(
                        CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                        calcFps()
                    )
                }.build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) { // API Level 28
                val executor = owner.lifecycleScope
                    .coroutineContext[CoroutineDispatcher]?.asExecutor()
                    ?: throw CvCamera2Exception("No Dispatcher found")

                captureSession.setSingleRepeatingRequest(
                    captureRequest,
                    executor,
                    object : CaptureCallback() {})
            } else {
                captureSession.setRepeatingRequest(captureRequest, null, null)
            }

            Log.d(TAG, "Set Repeating Request")

            session.send(captureSession)
            device.send(cameraDevice)
            reader.send(imageReader)
        }

        imageProcessingJob = owner.lifecycleScope.launch {
            // TODO An idea: images can be acquired more than once,
            // so maybe map image to async then collect as await

            val imageReader = reader.receive()

            imageReader.acquireImage().collect { image ->
                assert(image.planes.size == 3)
                drawFrame(image)
                image.close().also { Log.d(TAG, "Image Closed") }
            }
        }
    }

    override fun onPause(owner: LifecycleOwner) {
        super.onPause(owner)
        Log.d(TAG, "LifecycleObserver: onPause")

        // calls SurfaceDestroyed
        visibility = GONE

        // Stop camera preview
        owner.lifecycleScope.launch {
            val captureSession = session.receive()
            captureSession.close()
            Log.d(TAG, "Capture Session Closed")
        }

    }

    override fun onStop(owner: LifecycleOwner) {
        super.onStop(owner)
        Log.d(TAG, "LifecycleObserver: onStop")

        // close camera
        owner.lifecycleScope.launch {
            val cameraDevice = device.receive()
            cameraDevice.close()
            Log.d(TAG, "Camera Closed")

            imageReader.close()
            Log.d(TAG, "ImageReader Closed")
        }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        super.onDestroy(owner)
        Log.d(TAG, "LifecycleObserver: onDestroy")

        // close channels
        device.close()
        session.close()
        previewSize.close()

        bitmapCache.recycle()
        Log.d(TAG, "BitmapCache Recycled")
    }

    /** SurfaceHolder.Callback */
    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.d(TAG, "SurfaceCallback: surfaceCreated ($width, $height)")
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        Log.d(TAG, "SurfaceCallback: surfaceChanged, ${formatStr(format)} ($width, $height)")

        val preview = calcPreviewSize()
        Log.d(TAG, "SurfaceCallback: Rotation ${rotStr(display.rotation)} deg")

        bitmapCache.reconfigure(preview.width, preview.height, Bitmap.Config.ARGB_8888)

        findViewTreeLifecycleOwner()?.lifecycleScope?.launch {
            previewSize.send(preview)
        } ?: throw CvCamera2Exception("No Lifecycle owner of Surface")
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.d(TAG, "SurfaceCallback: surfaceDestroyed")
        imageProcessingJob?.cancel()
    }

    companion object {
        const val TAG = "KotlinCamera2View"
        const val READER_FORMAT = ImageFormat.YUV_420_888
    }
}

fun formatStr(format: Int) = when (format) {
    PixelFormat.RGBA_8888 -> "RGBA_8888"
    PixelFormat.RGBX_8888 -> "RGBX_8888"
    PixelFormat.RGBA_F16 -> "RGBA_F16"
    PixelFormat.RGBA_1010102 -> "RGBA_1010102"
    PixelFormat.RGB_888 -> "RGB_888"
    PixelFormat.RGB_565 -> "RGB_565"
    else -> "Unknown"
}

fun rotStr(rot: Int) = when (rot) {
    Surface.ROTATION_0 -> "0"
    Surface.ROTATION_90 -> "90"
    Surface.ROTATION_180 -> "180"
    Surface.ROTATION_270 -> "270"
    else -> "Unknown"
}
