package com.chathurangashan.camera2example.ui.fragments

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Bundle
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import com.chathurangashan.camera2example.R
import com.chathurangashan.camera2example.databinding.FragmentCameraBinding
import com.chathurangashan.camera2example.di.injector
import com.chathurangashan.camera2example.di.subcomponents.FragmentSubComponent
import com.chathurangashan.camera2example.di.viewModel
import com.chathurangashan.camera2example.utils.DisplayUtils
import com.chathurangashan.camera2example.utils.camera.OrientationLiveData
import com.chathurangashan.camera2example.utils.camera.decodeExifOrientation
import com.chathurangashan.camera2example.utils.camera.getPreviewOutputSize
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.*
import javax.inject.Inject

class CameraFragment : CameraBaseFragment(R.layout.fragment_camera) {

    @Inject
    override lateinit var navigationController: NavController
    private lateinit var decodedExifOrientationOfTheImage: Matrix
    private val timer = Timer()
    override val viewModel by viewModel { fragmentSubComponent.cameraViewModel }

    private lateinit var fragmentSubComponent: FragmentSubComponent
    private lateinit var viewBinding: FragmentCameraBinding
    private var adjustedCameraPreviewWidth = 0
    private var adjustedCameraPreviewHeight = 0

    private var videoRecodeFPS = 0
    private var previewSize: Size? = null
    private var captureSize: Size? = null

    private var videoRecordeSize: Size? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {

        viewBinding = FragmentCameraBinding.bind(view)

        initialization()
        onSurfaceViewChangeListener()
        onClickCameraButton()
    }

    private fun initialization() {

        fragmentSubComponent = injector.fragmentComponent().create(requireView())
        fragmentSubComponent.inject(this)

        timer.schedule(object : TimerTask() {
            override fun run() {
                requireActivity().runOnUiThread {
                    view?.keepScreenOn = true
                }
            }
        }, 10000)

        requestPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()) {

            it.entries.forEach { permission ->

                when (permission.key) {

                    Manifest.permission.CAMERA -> {

                        if (!permission.value) {
                            Snackbar.make(
                                viewBinding.root,
                                R.string.camera_permission_denied, Snackbar.LENGTH_SHORT
                            ).setAction(R.string.ok) {
                                navigationController.navigateUp()
                            }.show()
                        }
                    }
                    Manifest.permission.RECORD_AUDIO -> {

                        if (!permission.value) {
                            Snackbar.make(
                                viewBinding.root,
                                R.string.audio_record_permission_denied, Snackbar.LENGTH_SHORT
                            ).setAction(R.string.ok) {
                                navigationController.navigateUp()
                            }.show()
                        }
                    }
                }
            }

            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
            ) {
                getIdForSelectedCamera()
                viewBinding.cameraSurfaceView.visibility = View.VISIBLE
                startCameraPreview()
            }
        }


        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            getIdForSelectedCamera()
            viewBinding.cameraSurfaceView.visibility = View.VISIBLE
            startCameraPreview()

        } else {
            requestCameraPermission()
        }

        ORIENTATIONS.append(Surface.ROTATION_0, 90)
        ORIENTATIONS.append(Surface.ROTATION_90, 0)
        ORIENTATIONS.append(Surface.ROTATION_180, 270)
        ORIENTATIONS.append(Surface.ROTATION_270, 180)

        super.initialization(null, null)
    }

    private fun onClickCameraButton() {

        viewBinding.cameraFabButton.setOnClickListener {

            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
            )  {

                it.isEnabled = false

                //captureImage()

                if (isRecording) {
                    isRecording = false
                    stopRecording()
                } else {
                    isRecording = true
                    startRecoding()
                }

                view?.post { it.isEnabled = true  }

            } else {
                viewBinding.cameraSurfaceView.visibility = View.GONE
                requestCameraPermission()
            }
        }
    }

    private fun onSurfaceViewChangeListener() {

        viewBinding.cameraSurfaceView.holder.addCallback(object : SurfaceHolder.Callback {

            override fun surfaceChanged(
                holder: SurfaceHolder, format: Int,
                width: Int, height: Int
            ) {
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                if (isCameraInitialized) {
                    camera.close()
                }
            }

            override fun surfaceCreated(holder: SurfaceHolder) {

                // Selects appropriate preview size and configures view finder
                previewSize = getPreviewOutputSize(
                    viewBinding.cameraSurfaceView.display,
                    characteristics,
                    SurfaceHolder::class.java
                )

                if (previewSize != null) {

                    Log.d(
                        TAG,
                        "View finder size: " +
                                "${viewBinding.cameraSurfaceView.width} x " +
                                "${viewBinding.cameraSurfaceView.height}"
                    )

                    viewBinding.cameraSurfaceView
                        .setAspectRatio(previewSize!!.width, previewSize!!.height)

                }
            }

        })
    }

    /**
     * This function is responsible for selecting appropriate camera id for the user case passing
     * it into [selectSuitableCameraID] to start initialize camera view. [relativeOrientation]
     * observer also defined here
     */
    private fun getIdForSelectedCamera() {

        cameraId = selectSuitableCameraID(CameraCharacteristics.LENS_FACING_BACK)

        if (cameraId != null) {

            characteristics = cameraManager.getCameraCharacteristics(cameraId!!)

            // Used to rotate the output media to match device orientation
            relativeOrientation = OrientationLiveData(requireContext(), characteristics).apply {
                observe(viewLifecycleOwner, { orientation ->
                    Log.d(TAG, "Orientation changed: $orientation")
                })
            }

        } else {
            Snackbar.make(
                viewBinding.root, R.string.camera_id_find_error,
                Snackbar.LENGTH_INDEFINITE
            )
                .setAction(R.string.ok) {
                    navigationController.navigateUp()
                }.show()
        }
    }

    private fun startCameraPreview(){

        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED){

            lifecycleScope.launch(Dispatchers.Main) {

                camera = openCamera(cameraManager, cameraId!!, cameraHandler)

                val cameraOutputTargets = listOf(viewBinding.cameraSurfaceView.holder.surface)
                session = createCaptureSession(camera, cameraOutputTargets, cameraHandler)

                val captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)

                captureBuilder.set(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                captureBuilder.addTarget(viewBinding.cameraSurfaceView.holder.surface)

                session.setRepeatingRequest(captureBuilder.build(), null, cameraHandler)

                selectMostMatchingImageCaptureSize()
                selectMostMatchingVideoRecordSize()
            }

        }else{
            requestCameraPermission()
        }
    }

    private fun resetCameraPreview(){

        lifecycleScope.launch(Dispatchers.Main) {

            val cameraOutputTargets = listOf(viewBinding.cameraSurfaceView.holder.surface)
            session = createCaptureSession(camera, cameraOutputTargets, cameraHandler)

            val captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)

            captureBuilder.set(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            captureBuilder.addTarget(viewBinding.cameraSurfaceView.holder.surface)

            session.setRepeatingRequest(captureBuilder.build(), null, cameraHandler)

        }
    }

    private fun selectMostMatchingImageCaptureSize(){

        val previewFraction = DisplayUtils
            .asFraction(previewSize!!.width.toLong(), previewSize!!.height.toLong())

        val captureSizesMatchingCaptureSize = characteristics
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            .getOutputSizes(ImageFormat.JPEG)
            .filter {
                DisplayUtils
                    .asFraction(it.width.toLong(), it.height.toLong()) == previewFraction
            }
            .filter {
                it.width.toLong() == previewSize!!.width.toLong() &&
                        it.height.toLong() == previewSize!!.height.toLong()
            }
            .sortedBy { it.height * it.width }
            .reversed()

        // Select capture size which matches the preview size which or select the highest
        // capture size camera support which has the same aspect ratio as the preview size
        captureSize = if (captureSizesMatchingCaptureSize.isNotEmpty()) {
            captureSizesMatchingCaptureSize.first()
        } else {
            characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
                .getOutputSizes(ImageFormat.JPEG)
                .filter {
                    DisplayUtils.asFraction(
                        it.width.toLong(),
                        it.height.toLong()
                    ) == previewFraction
                }
                .sortedBy { it.height * it.width }
                .reversed()
                .first()
        }

        Log.d(TAG, "Selected capture size: $captureSize")
        adjustedCameraPreviewWidth = viewBinding.cameraSurfaceView.adjustedWidth
        adjustedCameraPreviewHeight = viewBinding.cameraSurfaceView.adjustedHeight
    }

    private fun selectMostMatchingVideoRecordSize(){

        val previewFraction = DisplayUtils
            .asFraction(previewSize!!.width.toLong(), previewSize!!.height.toLong())

        val videoSizesMatchingVideoRecordSize = characteristics
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            .getOutputSizes(MediaRecorder::class.java)
            .filter {
                DisplayUtils
                    .asFraction(it.width.toLong(), it.height.toLong()) == previewFraction
            }
            .filter {
                it.width.toLong() == previewSize!!.width.toLong() &&
                        it.height.toLong() == previewSize!!.height.toLong()
            }
            .sortedBy { it.height * it.width }
            .reversed()

        val capabilities = characteristics
            .get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)!!

        if (capabilities
                .contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE)) {

            videoRecordeSize = if (videoSizesMatchingVideoRecordSize.isNotEmpty()) {
                videoSizesMatchingVideoRecordSize.first()
            } else {
                characteristics
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
                    .getOutputSizes(MediaRecorder::class.java)
                    .filter {
                        DisplayUtils.asFraction(
                            it.width.toLong(),
                            it.height.toLong()
                        ) == previewFraction
                    }
                    .sortedBy { it.height * it.width }
                    .reversed()
                    .first()
            }

            val secondsPerFrame = characteristics
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
                .getOutputMinFrameDuration(MediaRecorder::class.java, videoRecordeSize) / 1_000_000_000.0

            videoRecodeFPS = if (secondsPerFrame > 0) (1.0 / secondsPerFrame).toInt() else 0
        }

        Log.d(TAG, "Selected video record size: $videoRecordeSize")
    }

    private fun captureImage(){

        if (captureSize != null) {

            captureImageReader = ImageReader.newInstance(
                captureSize!!.width, captureSize!!.height, ImageFormat.JPEG, IMAGE_BUFFER_SIZE)

            viewModel.documentPreviewSizeToCaptureSizeScaleFactor =
                captureSize!!.width / previewSize!!.width.toFloat()

            lifecycleScope.launch(Dispatchers.IO) {

                val cameraOutputTargets = listOf(
                    viewBinding.cameraSurfaceView.holder.surface,
                    captureImageReader.surface
                )

                session = createCaptureSession(camera, cameraOutputTargets, cameraHandler)

                takePhoto().use { result ->

                    Log.d(TAG, "Result received: $result")

                    // Save the result to disk
                    val output = saveResult(result)
                    Log.d(TAG, "Image saved: ${output.absolutePath}")

                    // If the result is a JPEG file, update EXIF metadata with orientation info
                    if (output.extension == "jpg") {

                        decodedExifOrientationOfTheImage =
                            decodeExifOrientation(result.orientation)

                        val exif = ExifInterface(output.absolutePath)
                        exif.setAttribute(
                            ExifInterface.TAG_ORIENTATION, result.orientation.toString()
                        )
                        exif.saveAttributes()
                        Log.d(TAG, "EXIF metadata saved: ${output.absolutePath}")
                    }
                }
            }
        }
    }

    private fun startRecoding(){

        if (videoRecordeSize != null) {

            lifecycleScope.launch(Dispatchers.IO) {

            configureMediaRecorder(videoRecodeFPS,videoRecordeSize!!)

                val cameraOutputTargets = listOf(
                    viewBinding.cameraSurfaceView.holder.surface,
                    mediaRecorder.surface
                )

                session = createCaptureSession(camera, cameraOutputTargets, cameraHandler)

                recordVideo()
            }

        }
    }

    /**
     * Request camera permission if it is not given
     */
    private fun requestCameraPermission() {

        if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) ||
            shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
        ) {
            Snackbar.make(
                viewBinding.root,
                R.string.camera_access_required,
                Snackbar.LENGTH_INDEFINITE
            )
                .setAction(R.string.ok) {
                    requestPermissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.CAMERA,
                            Manifest.permission.RECORD_AUDIO
                        )
                    )
                }.show()

        } else {
            // You can directly ask for the permission.
            Snackbar.make(
                viewBinding.root,
                R.string.camera_permission_not_available,
                Snackbar.LENGTH_INDEFINITE
            )
                .setAction(R.string.ok) {
                    requestPermissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.CAMERA,
                            Manifest.permission.RECORD_AUDIO
                        )
                    )
                }.show()
        }
    }

}