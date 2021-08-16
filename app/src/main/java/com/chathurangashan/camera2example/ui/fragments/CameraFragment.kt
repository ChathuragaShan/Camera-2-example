package com.chathurangashan.camera2example.ui.fragments

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.RectF
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.*
import androidx.fragment.app.Fragment
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import com.chathurangashan.camera2example.R
import com.chathurangashan.camera2example.databinding.FragmentCameraBinding
import com.chathurangashan.camera2example.di.activityViewModel
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
    private lateinit var decodedExifOrientationOfTheImage : Matrix
    override val viewModel by viewModel { fragmentSubComponent.cameraViewModel }
    private val timer = Timer()

    private lateinit var fragmentSubComponent: FragmentSubComponent
    private lateinit var viewBinding: FragmentCameraBinding
    private var adjustedCameraPreviewWidth = 0
    private var adjustedCameraPreviewHeight = 0

    private var previewSize: Size? = null
    private var captureSize: Size? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {

        viewBinding = FragmentCameraBinding.bind(view)

        initialization()
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
        }, 5000)

        requestPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted: Boolean ->
            if (isGranted) {
                viewBinding.cameraSurfaceView.visibility = View.VISIBLE
                setupCameraIdWithView()
            } else {
                // Permission request was denied.
                Snackbar.make(
                    viewBinding.root,
                    R.string.camera_permission_denied, Snackbar.LENGTH_SHORT
                )
                    .setAction(R.string.ok) {
                        navigationController.navigateUp()
                    }.show()

            }
        }

        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            setupCameraIdWithView()
        } else {
            viewBinding.cameraSurfaceView.visibility = View.GONE
            requestCameraPermission()
        }

        ORIENTATIONS.append(Surface.ROTATION_0, 90)
        ORIENTATIONS.append(Surface.ROTATION_90, 0)
        ORIENTATIONS.append(Surface.ROTATION_180, 270)
        ORIENTATIONS.append(Surface.ROTATION_270, 180)

        super.initialization(null, null)
    }

    private fun onClickCameraButton(){

        viewBinding.cameraFabButton.setOnClickListener {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {

                it.isEnabled = false

                lifecycleScope.launch(Dispatchers.IO) {

                    takePhoto().use { result ->

                        Log.d(TAG, "Result received: $result")

                        // Save the result to disk
                        val output = saveResult(result)
                        Log.d(TAG, "Image saved: ${output.absolutePath}")

                        // If the result is a JPEG file, update EXIF metadata with orientation info
                        if (output.extension == "jpg") {

                            decodedExifOrientationOfTheImage = decodeExifOrientation(result.orientation)

                            val exif = ExifInterface(output.absolutePath)
                            exif.setAttribute(
                                ExifInterface.TAG_ORIENTATION, result.orientation.toString()
                            )
                            exif.saveAttributes()
                            Log.d(TAG, "EXIF metadata saved: ${output.absolutePath}")
                        }
                    }

                    it.post {
                        it.isEnabled = true
                    }
                }

            }else{
                viewBinding.cameraSurfaceView.visibility = View.GONE
                requestCameraPermission()
            }

        }
    }

    /**
     * This function is responsible for selecting appropriate camera id for the user case passing
     * it into [selectSuitableCameraID] to start initialize camera view
     */
    private fun setupCameraIdWithView() {
        cameraId = selectSuitableCameraID(CameraCharacteristics.LENS_FACING_BACK)
        if (cameraId != null) {
            characteristics = cameraManager.getCameraCharacteristics(cameraId!!)

            // Used to rotate the output media to match device orientation
            relativeOrientation = OrientationLiveData(requireContext(), characteristics).apply {
                observe(viewLifecycleOwner,{
                        orientation -> Log.d(TAG, "Orientation changed: $orientation")
                })
            }

            settingUpCameraView(cameraId!!)

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

    /**
     * Request camera permission if it is not given
     */
    private fun requestCameraPermission() {
        if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
            Snackbar.make(viewBinding.root, R.string.camera_access_required, Snackbar.LENGTH_INDEFINITE)
                .setAction(R.string.ok) {
                    requestPermissionLauncher.launch(Manifest.permission.CAMERA)
                }.show()
        } else {
            // You can directly ask for the permission.
            Snackbar.make(
                viewBinding.root,
                R.string.camera_permission_not_available,
                Snackbar.LENGTH_INDEFINITE
            )
                .setAction(R.string.ok) {
                    requestPermissionLauncher.launch(Manifest.permission.CAMERA)
                }.show()
        }
    }

    /**
     * This function is responsible for setting camera preview into Surface view with the correct
     * preview size match the phone screen
     */
    private fun settingUpCameraView(selectedCameraId: String) {

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

                    Log.d(TAG,
                        "View finder size: " +
                                "${viewBinding.cameraSurfaceView.width} x " +
                                "${viewBinding.cameraSurfaceView.height}"
                    )

                    viewBinding.cameraSurfaceView
                        .setAspectRatio(previewSize!!.width, previewSize!!.height)

                    // To ensure that size is set, initialize camera in the view's thread
                    view?.post { configureCamera(selectedCameraId) }
                }
            }

        })
    }

    /** This function is responsible for creating camera session, defining target to receive
     * camera frames and setting up with appropriate parameters
     */
    private fun configureCamera(selectedCameraId: String) {

        lifecycleScope.launch(Dispatchers.Main) {

            camera = openCamera(cameraManager, selectedCameraId, cameraHandler)

            val previewFraction = DisplayUtils
                .asFraction(previewSize!!.width.toLong(), previewSize!!.height.toLong())

            val previewSizeMatchingCaptureSize = characteristics.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
                .getOutputSizes(ImageFormat.JPEG)
                .filter { DisplayUtils
                    .asFraction(it.width.toLong(),it.height.toLong()) == previewFraction }
                .filter { it.width.toLong() == previewSize!!.width.toLong() &&
                        it.height.toLong() == previewSize!!.height.toLong()}
                .sortedBy { it.height * it.width}
                .reversed()

            // Select capture size which matches the preview size which or select the highest
            // capture size camera support which has the same aspect ratio as the preview size
            captureSize = if(previewSizeMatchingCaptureSize.isNotEmpty()){
                previewSizeMatchingCaptureSize.first()
            }else{
                characteristics.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
                    .getOutputSizes(ImageFormat.JPEG)
                    .filter { DisplayUtils.asFraction(it.width.toLong(),it.height.toLong()) == previewFraction }
                    .sortedBy { it.height * it.width}
                    .reversed()
                    .first()
            }

            if (captureSize != null) {

                captureImageReader = ImageReader.newInstance(
                    captureSize!!.width, captureSize!!.height, ImageFormat.JPEG, IMAGE_BUFFER_SIZE
                )

                Log.d(TAG, "Selected capture size: $captureSize")

                val targets = listOf(
                    viewBinding.cameraSurfaceView.holder.surface,
                    captureImageReader.surface,
                )

                session = createCaptureSession(camera, targets, cameraHandler)

                val captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                captureBuilder.set(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                )
                captureBuilder.addTarget(viewBinding.cameraSurfaceView.holder.surface)

                session.setRepeatingRequest(captureBuilder.build(), null, cameraHandler)

                adjustedCameraPreviewWidth = viewBinding.cameraSurfaceView.adjustedWidth
                adjustedCameraPreviewHeight = viewBinding.cameraSurfaceView.adjustedHeight
                viewModel.documentPreviewSizeToCaptureSizeScaleFactor =
                    captureSize!!.width/previewSize!!.width.toFloat()

            }
        }
    }

}