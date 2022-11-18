package com.example.projecttheianative

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.util.AndroidException
import android.util.Log
import android.util.Size
import android.view.Surface
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.suspendCancellableCoroutine
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Marker class for our custom exception */
class CvCamera2Exception(msg: String? = null, cause: Throwable? = null) :
    AndroidException(msg, cause)

/** Allow destructuring of class Size */
operator fun Size.component1() = width
operator fun Size.component2() = height

/** Printf style formatting */
infix fun Any.fmt(template: String) = template.format(this)

/** Boolean to Nullable Boolean */
fun Boolean.trueOrNull(): Boolean? = if (this) true else null

/** Camera Open */
@OptIn(ExperimentalStdlibApi::class)
@SuppressLint("MissingPermission")
suspend fun CameraManager.open(cameraId: String): CameraDevice =
    suspendCancellableCoroutine { continuation ->

        val cameraDeviceCallback = object : CameraDevice.StateCallback() {
            /** Camera opened */
            override fun onOpened(cameraDevice: CameraDevice) {
                continuation.resume(cameraDevice)
                continuation.invokeOnCancellation { cameraDevice.close() }
            }

            /** App loses control of camera. Camera opened twice in app.  */
            override fun onDisconnected(cameraDevice: CameraDevice) {
                cameraDevice.close()
                continuation.resumeWithException(CvCamera2Exception("Camera disconnected"))
            }

            /** App never opened Camera. Next time it tries, CameraAccessException */
            override fun onError(cameraDevice: CameraDevice, error: Int) {
                cameraDevice.close()
                continuation.resumeWithException(CvCamera2Exception(errorMsg(error)))
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // API Level 28
            val executor = continuation.context[CoroutineDispatcher]?.asExecutor()
                ?: Dispatchers.Default.asExecutor()

            openCamera(cameraId, executor, cameraDeviceCallback)
        } else {
            openCamera(cameraId, cameraDeviceCallback, null)
        }
    }

fun errorMsg(err: Int): String = when (err) {
    CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> "Device encountered a fatal error"
    CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "Device policy does not allow"
    CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> "Device already in use"
    CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> "Service encountered a fatal error"
    CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "Open cameras exist"
    else -> "Unknown Error"
}

/** Capture session */
@OptIn(ExperimentalStdlibApi::class)
suspend fun CameraDevice.captureSession(surface: Surface): CameraCaptureSession =
    suspendCancellableCoroutine { continuation ->

        val captureSessionCallback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                continuation.resume(session)
                continuation.invokeOnCancellation { session.close() }
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                val exp = CvCamera2Exception("Camera Device cannot run this configuration")
                continuation.resumeWithException(exp)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // API Level 28
            val executor = continuation.context[CoroutineDispatcher]?.asExecutor()
                ?: Dispatchers.Default.asExecutor()

            val sessionConfig = SessionConfiguration(
                SessionConfiguration.SESSION_HIGH_SPEED,
                listOf(OutputConfiguration(surface)),
                executor,
                captureSessionCallback
            )
            this.createCaptureSession(sessionConfig)

        } else {
            @Suppress("DEPRECATION")
            this.createCaptureSession(listOf(surface), captureSessionCallback, null)
        }
    }

/** Computation on each frame */
// TODO Add some cancellation mechanism
fun ImageReader.acquireImage(): Flow<Image> = callbackFlow {

    val listener = ImageReader.OnImageAvailableListener {
        val image: Image = it.acquireLatestImage() ?: return@OnImageAvailableListener
        trySendBlocking(image)
            .onFailure { e ->
                Log.d("ImageReader.acquire", "Could not send Image", e)
            }
    }

    // TODO Handler to Executor
    setOnImageAvailableListener(listener, null)
    awaitClose { setOnImageAvailableListener({}, null) }
}.conflate()

/** Convert Image to Matrix */
fun Image.toRgb(): Mat {
    val chromaPixelStride = planes[1].pixelStride // for me always 2

    val planeY = planes[0].buffer
    val planeYStep = planes[0].rowStride

    val planeU = planes[1].buffer
    val planeUStep = planes[1].rowStride

    val planeV = planes[2].buffer
    val planeVStep = planes[2].rowStride

    val rgbMat = if (chromaPixelStride == 2) { // Chroma channels interleaved
        assert(planes[0].pixelStride == 1)
        assert(planes[2].pixelStride == 2)

        val yMat = Mat(height, width, CvType.CV_8UC1, planeY, planeYStep.toLong())
        val uMat = Mat(height / 2, width / 2, CvType.CV_8UC2, planeU, planeUStep.toLong())
        val vMat = Mat(height / 2, width / 2, CvType.CV_8UC2, planeV, planeVStep.toLong())

        val addressDiff = vMat.dataAddr() - uMat.dataAddr()
        if (addressDiff > 0) {
            assert(addressDiff == 1L)
            Imgproc.cvtColorTwoPlane(yMat, uMat, yMat, Imgproc.COLOR_YUV2RGB_NV12)
        } else {
            assert(addressDiff == -1L)
            Imgproc.cvtColorTwoPlane(yMat, vMat, yMat, Imgproc.COLOR_YUV2RGB_NV21)
        }

        uMat.release()
        vMat.release()

        yMat
    } else { // Chroma channels not interleaved
        val yuvBytes = ByteArray(width * (height + height / 2))
        var yuvBytesOffset = 0

        if (planeYStep == width) {
            planeY.get(yuvBytes, 0, width * height)
            yuvBytesOffset = width * height
        } else {
            val padding = planeYStep - width
            for (i in 0 until height) {
                planeY.get(yuvBytes, yuvBytesOffset, width)
                yuvBytesOffset += width
                if (i < height - 1) {
                    planeY.position(planeY.position() + padding)
                }
            }
            assert(yuvBytesOffset == width * height)
        }

        val chromaRowPadding = planes[1].rowStride - width / 2
        if (chromaRowPadding == 0) {
            /** When row stride of Chroma Channel == width, copy entire channels */
            planeU.get(yuvBytes, yuvBytesOffset, width * height / 4)
            yuvBytesOffset += width * height / 4
            planeV.get(yuvBytes, yuvBytesOffset, width * height / 4)
        } else {
            for (i in 0 until height / 2) {
                planeU.get(yuvBytes, yuvBytesOffset, width / 4)
                yuvBytesOffset += width / 2
                if (i < height / 2 - 1) {
                    planeU.position(planeU.position() + chromaRowPadding)
                }
            }

            for (i in 0 until height / 2) {
                planeV.get(yuvBytes, yuvBytesOffset, width / 4)
                yuvBytesOffset += width / 2
                if (i < height / 2 - 1) {
                    planeV.position(planeU.position() + chromaRowPadding)
                }
            }
        }

        Mat(height + height / 2, width, CvType.CV_8UC1).also {
            it.put(0, 0, yuvBytes)
            Imgproc.cvtColor(it, it, Imgproc.COLOR_YUV2RGB_I420, 3)
        }
    }

    return rgbMat
}

/** Check Mat and Bitmap Config if they are valid for matToBitmap */
val Mat.validConv: Boolean
    get() = type() == CvType.CV_8UC1 || type() == CvType.CV_8UC3 || type() == CvType.CV_8UC4

val Bitmap.validConv: Boolean
    get() = config == Bitmap.Config.ARGB_8888 || config == Bitmap.Config.RGB_565

/** Release these matrices */
fun release(vararg mats: Mat) = mats.forEach { it.release() }

fun Image.process(bitmap: Bitmap) = processYuvBuffer(
    width, height,
    planes[0].buffer, planes[0].rowStride,
    planes[1].buffer, planes[1].rowStride,
    planes[2].buffer, planes[2].rowStride,
    planes[1].pixelStride == 2,
    bitmap
)

external fun processYuvBuffer(
    img_width: Int, img_height: Int,
    plane0: ByteBuffer, planeY_row_stride: Int,
    plane1: ByteBuffer, planeU_row_stride: Int,
    plane2: ByteBuffer, planeV_row_stride: Int,
    interleaved: Boolean,
    bitmap: Bitmap
)
