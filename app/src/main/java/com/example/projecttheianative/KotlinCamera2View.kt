package com.example.projecttheianative

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.util.AttributeSet
import android.util.Log
import android.util.Size
import android.view.SurfaceHolder
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

class KotlinCamera2View : CvCameraView, DefaultLifecycleObserver {
    constructor(context: Context) : super(context)
    constructor(context: Context, attr: AttributeSet) : super(context, attr)

    private val cameraManager by lazy {
        this.context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    private val backCameraId by lazy {
        cameraManager.cameraIdList.firstOrNull()
            ?: throw CameraAccessException(CameraAccessException.CAMERA_DISCONNECTED)
    }

    // TODO("Remove Shared State into channel")
    // Only SurfaceHolder.Callback.surfaceDestroyed
    private lateinit var imageReader: ImageReader

    private val device = Channel<CameraDevice>(capacity = 1)
    private val session = Channel<CameraCaptureSession>(capacity = 1)
    private val reader = Channel<ImageReader>(capacity = 1)
    private val previewSize = Channel<Size>(capacity = 1)

    private var imageProcessingJob: Job? = null

    init {
        holder.addCallback(this)
    }

    private fun calcPreviewSize() =
        cameraManager.getCameraCharacteristics(backCameraId)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageReader::class.java)
            ?.find { (wt, ht) ->
                (wt <= width && ht <= height) && maxPreviewSize?.let { wt <= it.width && ht <= it.height } ?: true
            } ?: throw CvCamera2Exception("No Preview size fits")

    /** LifeCycleObserver */
    override fun onCreate(owner: LifecycleOwner) {
        super.onCreate(owner)
        Log.d(TAG, "onCreate: CObserver ")
    }

    override fun onStart(owner: LifecycleOwner) {
        super.onStart(owner)

        // open camera
        owner.lifecycleScope.launch {
            val cameraDevice = cameraManager.open(backCameraId)
            Log.d(TAG, "Camera Opened")
            device.send(cameraDevice)
        }

        Log.d(TAG, "onStart: SObserver")
    }

    override fun onResume(owner: LifecycleOwner) {
        super.onResume(owner)
        // calls SurfaceCreated SurfaceChanged
        visibility = VISIBLE

        // start camera preview
        owner.lifecycleScope.launch {
            val cameraDevice = device.receive()

            // Wait for preview Size coming from SurfaceChanged
            val (wt, ht) = previewSize.receive()

            // TODO("Check maxImages usage from HardwareBuffer Usages for performance")
            imageReader = ImageReader.newInstance(wt, ht, READER_FORMAT, 30)

            val captureSession = cameraDevice.captureSession(imageReader.surface)
            Log.d(TAG, "Created Capture Session")
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
                }.build()

            // TODO("Add executor version")
            Log.d(TAG, "Set Repeating Request")
            captureSession.setRepeatingRequest(captureRequest, null, null)

            session.send(captureSession)
            device.send(cameraDevice)
            reader.send(imageReader)
        }

        imageProcessingJob = owner.lifecycleScope.launch {
            /** An idea: images can be acquired more than once,
             * so maybe map image to async then collect as await */

            val imageReader = reader.receive()

            imageReader.acquireImage()
                .collect { image ->
                    assert(image.planes.size == 3)
                    drawFrame(this@launch, image)
//                    rgb.release()
                    image.close()
                }
        }

        Log.d(TAG, "onResume: RObserver")
    }

    override fun onPause(owner: LifecycleOwner) {
        super.onPause(owner)
        // calls SurfaceDestroyed
        visibility = GONE

        // Stop camera preview
        owner.lifecycleScope.launch {
            val captureSession = session.receive()
            captureSession.close()
            Log.d(TAG, "Capture Session Closed")
        }

        Log.d(TAG, "onPause: PObserver")
    }

    override fun onStop(owner: LifecycleOwner) {
        super.onStop(owner)

        // close camera
        owner.lifecycleScope.launch {
            val cameraDevice = device.receive()
            cameraDevice.close()
            Log.d(TAG, "Camera Closed")

            imageReader.close()
            Log.d(TAG, "ImageReader Closed")
        }

        Log.d(TAG, "onStop: STObserver")
    }

    override fun onDestroy(owner: LifecycleOwner) {
        super.onDestroy(owner)

        // close channels
        device.close()
        session.close()
        previewSize.close()

        bitmapCache.recycle()
        Log.d(TAG, "BitmapCache Recycled")

        Log.d(TAG, "onDestroy: DObserver")
    }

    /** SurfaceHolder.Callback */
    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.d(TAG, "surfaceCreated, surface-dim: ($width, $height)")
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        Log.d(TAG, "surfaceChanged, format: $format, param-dim: ($width, $height)")

        val preview = calcPreviewSize()
        Log.d(TAG, "View Rotation: ${display.rotation}")

        bitmapCache.reconfigure(preview.width, preview.height, Bitmap.Config.ARGB_8888)

        findViewTreeLifecycleOwner()?.lifecycleScope?.launch {
            previewSize.send(preview)
        } ?: throw CvCamera2Exception("No Lifecycle owner of Surface")
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        imageProcessingJob?.cancel()
        Log.d(TAG, "SurfaceDestroyed")
    }

    companion object {
        const val TAG = "KotlinCamera2View"
        const val READER_FORMAT = ImageFormat.YUV_420_888
    }
}
