package com.chathurangashan.camera2example.ui.fragments

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.SparseIntArray
import android.view.Surface
import androidx.activity.result.ActivityResultLauncher
import androidx.lifecycle.lifecycleScope
import com.chathurangashan.camera2example.interfaces.NavigationUpListener
import com.chathurangashan.camera2example.utils.camera.OrientationLiveData
import com.chathurangashan.camera2example.utils.camera.computeExifOrientation
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeoutException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

abstract class CameraBaseFragment(layoutResource: Int) : BaseFragment(layoutResource){

    protected lateinit var characteristics: CameraCharacteristics
    protected lateinit var camera: CameraDevice
    protected lateinit var session: CameraCaptureSession
    protected lateinit var requestPermissionLauncher: ActivityResultLauncher<String>
    protected lateinit var captureImageReader: ImageReader

    /** Live data listener for changes in the device orientation relative to the camera */
    protected lateinit var relativeOrientation: OrientationLiveData

    protected var cameraId: String? = null
    private var androidImageCompressionRatio = 95

    /** [HandlerThread] and [Handler] where all camera operations run */
    private val cameraThread = HandlerThread("CameraThread").apply { start() }
    protected val cameraHandler = Handler(cameraThread.looper)

    /** [imageReaderThread] and [imageReaderHandler] where all camera still image capturing operations run */
    private val imageReaderThread = HandlerThread("imageReaderThread").apply { start() }
    protected val imageReaderHandler = Handler(imageReaderThread.looper)

    protected val isCameraInitialized get() = this::camera.isInitialized

    protected val cameraManager: CameraManager by lazy {
        requireContext().applicationContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    companion object {

        val TAG: String = CameraBaseFragment::class.java.simpleName

        /** Maximum number of images that will be held in the reader's buffer */
        const val IMAGE_BUFFER_SIZE: Int = 3

        /** Maximum time allowed to wait for the result of an image capture */
        const val IMAGE_CAPTURE_TIMEOUT_MILLIS: Long = 5000

        /** Helper data class used to hold capture metadata with their associated image */
        data class CombinedCaptureResult(
            val image: Image,
            val metadata: CaptureResult,
            val orientation: Int,
            val format: Int
        ) : Closeable {
            override fun close() = image.close()
        }

        /**
         * Create a [File] named a using formatted timestamp with the current date and time.
         *
         * @return [File] created.
         */
        fun createFile(context: Context, extension: String): File {
            val sdf = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSS", Locale.US)
            return File(context.filesDir, "IMG_${sdf.format(Date())}.$extension")
        }

        val ORIENTATIONS = SparseIntArray(4)

    }

    /**
     * This Function is responsible for selecting appropriate facing camera id out of the
     * cameras available in the device
     */
    protected fun selectSuitableCameraID(lensFacingDirection: Int): String? {
        //Select the camera device has a feature set that's comparable to the baseline requirements.
        //Specially in older hardware
        val cameraIds = cameraManager.cameraIdList.filter {

            val characteristics = cameraManager.getCameraCharacteristics(it)

            val capabilities = characteristics
                .get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            capabilities?.contains(
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE
            ) ?: false
        }

        cameraIds.forEach { id ->

            val characteristics = cameraManager.getCameraCharacteristics(id)
            val cameraOrientation = characteristics.get(CameraCharacteristics.LENS_FACING)

            // All cameras *must* support JPEG output so we don't need to check characteristics
            if (cameraOrientation == lensFacingDirection) {
                return id
            }
        }

        return null
    }

    @SuppressLint("MissingPermission")
    protected suspend fun openCamera(
        manager: CameraManager,
        cameraId: String,
        handler: Handler? = null
    ): CameraDevice = suspendCancellableCoroutine { cont ->

        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) = cont.resume(device)

            override fun onDisconnected(device: CameraDevice) {
                Log.w(TAG, "Camera $cameraId has been disconnected")
                requireActivity().finish()
            }

            override fun onError(device: CameraDevice, error: Int) {
                val msg = when (error) {
                    ERROR_CAMERA_DEVICE -> "Fatal (device)"
                    ERROR_CAMERA_DISABLED -> "Device policy"
                    ERROR_CAMERA_IN_USE -> "Camera in use"
                    ERROR_CAMERA_SERVICE -> "Fatal (service)"
                    ERROR_MAX_CAMERAS_IN_USE -> "Maximum cameras in use"
                    else -> "Unknown"
                }
                val exc = RuntimeException("Camera $cameraId error: ($error) $msg")
                Log.e(TAG, exc.message, exc)
                if (cont.isActive) cont.resumeWithException(exc)
            }
        }, handler)
    }

    protected suspend fun createCaptureSession(
        device: CameraDevice, targets: List<Surface>,
        handler: Handler? = null
    ): CameraCaptureSession = suspendCoroutine { cont ->

        // Create a capture session using the predefined targets; this also involves defining the
        // session state callback to be notified of when the session is ready
        device.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {

            override fun onConfigured(session: CameraCaptureSession) = cont.resume(session)

            override fun onConfigureFailed(session: CameraCaptureSession) {
                val exc = RuntimeException("Camera ${device.id} session configuration failed")
                Log.e(tag, exc.message, exc)
                cont.resumeWithException(exc)
            }
        }, handler)
    }

    /**
     * Helper function used to capture a still image using the [CameraDevice.TEMPLATE_STILL_CAPTURE]
     * template. It performs synchronization between the [CaptureResult] and the [Image] resulting
     * from the single capture, and outputs a [CombinedCaptureResult] object.
     */
    protected suspend fun takePhoto():
            CombinedCaptureResult = suspendCoroutine { cont ->

        // Flush any images left in the image reader
        @Suppress("ControlFlowWithEmptyBody")
        while (captureImageReader.acquireNextImage() != null) {
        }

        // Start a new image queue
        val imageQueue = ArrayBlockingQueue<Image>(IMAGE_BUFFER_SIZE)
        captureImageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireNextImage()
            Log.d(TAG, "Image available in queue: ${image.timestamp}")
            imageQueue.add(image)
        }, imageReaderHandler)

        val captureRequest = session.device.createCaptureRequest(
            CameraDevice.TEMPLATE_STILL_CAPTURE
        ).apply { addTarget(captureImageReader.surface) }
        session.capture(captureRequest.build(), object : CameraCaptureSession.CaptureCallback() {

            override fun onCaptureStarted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                timestamp: Long,
                frameNumber: Long
            ) {
                super.onCaptureStarted(session, request, timestamp, frameNumber)
            }

            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
            ) {
                super.onCaptureCompleted(session, request, result)
                val resultTimestamp = result.get(CaptureResult.SENSOR_TIMESTAMP)
                Log.d(TAG, "Capture result received: $resultTimestamp")

                // Set a timeout in case image captured is dropped from the pipeline
                val exc = TimeoutException("Image dequeuing took too long")
                val timeoutRunnable = Runnable { cont.resumeWithException(exc) }
                imageReaderHandler.postDelayed(timeoutRunnable, IMAGE_CAPTURE_TIMEOUT_MILLIS)

                // Loop in the coroutine's context until an image with matching timestamp comes
                // We need to launch the coroutine context again because the callback is done in
                //  the handler provided to the `capture` method, not in our coroutine context
                @Suppress("BlockingMethodInNonBlockingContext")
                lifecycleScope.launch(cont.context) {
                    while (true) {

                        val image = imageQueue.take()

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                            image.format != ImageFormat.DEPTH_JPEG &&
                            image.timestamp != resultTimestamp
                        ) continue
                        Log.d(TAG, "Matching image dequeued: ${image.timestamp}")

                        // Unset the image reader listener
                        imageReaderHandler.removeCallbacks(timeoutRunnable)
                        captureImageReader.setOnImageAvailableListener(null, null)

                        // Clear the queue of images, if there are left
                        while (imageQueue.size > 0) {
                            imageQueue.take().close()
                        }

                        // Compute EXIF orientation metadata
                        val rotation = relativeOrientation.value ?: defaultOrientation()
                        Log.d(TAG,"EXIF rotation value $rotation")
                        val mirrored = characteristics.get(CameraCharacteristics.LENS_FACING) ==
                                CameraCharacteristics.LENS_FACING_FRONT
                        val exifOrientation = computeExifOrientation(rotation, mirrored)

                        // Build the result and resume progress
                        cont.resume(
                            CombinedCaptureResult(
                                image, result, exifOrientation, captureImageReader.imageFormat
                            )
                        )

                        // There is no need to break out of the loop, this coroutine will suspend
                    }
                }
            }
        }, cameraHandler)
    }

    /** Helper function used to save a [CombinedCaptureResult] into a [File] */
    protected suspend fun saveResult(result: CombinedCaptureResult): File = suspendCoroutine { cont ->
        when (result.format) {

            // When the format is JPEG or DEPTH JPEG we can simply save the bytes as-is
            ImageFormat.JPEG, ImageFormat.DEPTH_JPEG -> {
                val buffer = result.image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining()).apply { buffer.get(this) }

                @Suppress("BlockingMethodInNonBlockingContext")
                try {
                    val output = createFile(requireContext(), "jpg")
                    FileOutputStream(output).use { it.write(bytes) }
                    cont.resume(output)
                } catch (exc: IOException) {
                    Log.e(TAG, "Unable to write JPEG image to file", exc)
                    cont.resumeWithException(exc)
                }
            }

            // No other formats are supported by this sample
            else -> {
                val exc = RuntimeException("Unknown image format: ${result.image.format}")
                Log.e(TAG, exc.message, exc)
                cont.resumeWithException(exc)
            }
        }
    }

    /**
     * Function from saving bitmap to a file
     */
    protected fun saveToInternalStorage(bitmapImage: Bitmap, fileName: String): String {

        val cw = ContextWrapper(requireContext())
        // path to /data/data/yourapp/app_data/imageDir
        val directory = cw.getDir("cropped_images", Context.MODE_PRIVATE)
        // Create imageDir
        val imageName = "$fileName.jpg"
        val filePath = File(directory, imageName)
        var fileOutputStream: FileOutputStream? = null
        try {
            fileOutputStream = FileOutputStream(filePath)
            bitmapImage.compress(Bitmap.CompressFormat.JPEG, androidImageCompressionRatio, fileOutputStream)
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try {
                fileOutputStream!!.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }

        return filePath.absolutePath
    }

    /**
     * use this function to get jpeg rotation value to when [relativeOrientation] null
     */
    protected fun defaultOrientation(): Int{

        val displayRotation = requireActivity().windowManager?.defaultDisplay?.rotation
        val sensorOrientation =
            characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)
        val surfaceRotation = ORIENTATIONS.get(displayRotation!!)
        return (surfaceRotation + sensorOrientation!! + 270) % 360
    }

    override fun onStop() {
        super.onStop()
        try {
            if (::camera.isInitialized) {
                camera.close()
            }
        } catch (exc: Throwable) {
            Log.e(tag, "Error closing camera", exc)
        }
    }

    override fun onDestroy() {

        cameraThread.quitSafely()
        imageReaderThread.quitSafely()
        imageReaderThread.quitSafely()

        if(::captureImageReader.isInitialized){
            captureImageReader.close()
        }

        if(::session.isInitialized){
            session.close()
        }

        super.onDestroy()
    }
}