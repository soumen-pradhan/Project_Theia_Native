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
import android.util.Range
import android.util.Size
import android.view.Surface
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.cancellable
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.suspendCancellableCoroutine
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Marker class for our custom exception */
class CvCamera2Exception(msg: String? = null, cause: Throwable? = null) :
    AndroidException(msg, cause)

/** Allow destructuring of class Size */
operator fun Size.component1() = width
operator fun Size.component2() = height

/** Allow destructuring of class Range */
operator fun <T : Comparable<T>?> Range<T>.component1(): T = lower
operator fun <T : Comparable<T>?> Range<T>.component2(): T = upper

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
                if (continuation.isActive) continuation.resume(cameraDevice)
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) { // API Level 28
            val executor = continuation.context[CoroutineDispatcher]?.asExecutor()
                ?: throw CvCamera2Exception("No Dispatcher found")

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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) { // API Level 28
            val executor = continuation.context[CoroutineDispatcher]?.asExecutor()
                ?: throw CvCamera2Exception("No Dispatcher found")

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
fun ImageReader.acquireImage(): Flow<Image> = callbackFlow {
    val listener = ImageReader.OnImageAvailableListener {
        val image: Image? = it.acquireLatestImage()

        if (image == null) {
            Log.d("KotlinExt", "Image cannot be acquired")
            return@OnImageAvailableListener
        }

        trySend(image).isSuccess.trueOrNull()
            ?: run {
                image.close()
                // Log.d("KotlinExt", "Image Closed")
            }
    }

    /*
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {  // API Level 28
        val executor = coroutineContext[CoroutineDispatcher]?.asExecutor()
            ?: throw CvCamera2Exception("No Dispatcher found")

        setOnImageAvailableListenerWithExecutor(listener, executor)
    } else {
        setOnImageAvailableListener(listener, null)
    }
    */

    setOnImageAvailableListener(listener, null)
    awaitClose { setOnImageAvailableListener({}, null) }

}.cancellable().conflate()

/** Process image and write to Bitmap */
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
