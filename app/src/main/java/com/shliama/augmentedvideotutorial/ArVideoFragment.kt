package com.shliama.augmentedvideotutorial

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.RectF
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.Image
import android.media.ImageReader
import android.media.ImageReader.OnImageAvailableListener
import android.media.MediaMetadataRetriever
import android.media.MediaMetadataRetriever.*
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.*
import android.view.animation.LinearInterpolator
import android.widget.Toast
import androidx.core.animation.doOnStart
import androidx.core.graphics.rotationMatrix
import androidx.core.graphics.transform
import com.google.android.gms.tasks.OnCompleteListener
import com.google.android.gms.tasks.OnFailureListener
import com.google.android.gms.tasks.OnSuccessListener
import com.google.android.gms.tasks.Task
import com.google.ar.core.AugmentedImage
import com.google.ar.core.AugmentedImageDatabase
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.sceneform.FrameTime
import com.google.ar.sceneform.rendering.ExternalTexture
import com.google.ar.sceneform.rendering.ModelRenderable
import com.google.ar.sceneform.ux.ArFragment
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.TextRecognizerOptions
import java.io.IOException
import java.util.*

open class ArVideoFragment : ArFragment() {

    private lateinit var mediaPlayer: MediaPlayer
    private lateinit var externalTexture: ExternalTexture
    private lateinit var videoRenderable: ModelRenderable
    private lateinit var videoAnchorNode: VideoAnchorNode
    private lateinit var backgroundHandler: Handler
    private lateinit var backgroundThread: HandlerThread
    private lateinit var textRecognizer: TextRecognizer

    private var activeAugmentedImage: AugmentedImage? = null
    private var activeAugmentedImageID: String? = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mediaPlayer = MediaPlayer()
//        startBackgroundThread()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = super.onCreateView(inflater, container, savedInstanceState)

        planeDiscoveryController.hide()
        planeDiscoveryController.setInstructionView(null)
        arSceneView.planeRenderer.isEnabled = false
        arSceneView.isLightEstimationEnabled = false

        initializeSession()
        createArScene()
//        setupCPUImage()
        initTextRecognizer()
        return view
    }

//    override fun getSessionFeatures(): MutableSet<Session.Feature> {
//        return EnumSet.of(Session.Feature.SHARED_CAMERA)
//    }

    private fun initTextRecognizer(){
        textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

//    fun setupCPUImage() {
//        arSceneView.session?.also { sharedSession ->
//
//            val sharedCamera = sharedSession.sharedCamera
//
//            // Use the currently configured CPU image size.
//            val desiredCpuImageSize: Size = sharedSession.cameraConfig.imageSize
//            val cpuImageReader = ImageReader.newInstance(
//                desiredCpuImageSize.width,
//                desiredCpuImageSize.height,
//                ImageFormat.YUV_420_888,
//                2
//            )
//            cpuImageReader.setOnImageAvailableListener(this, backgroundHandler)
//
//            val surfaces = sharedCamera.arCoreSurfaces
//            surfaces.add(cpuImageReader.surface)
//            // When ARCore is running, make sure it also updates our CPU image surface.
//            sharedCamera.setAppSurfaces(sharedSession.cameraConfig.cameraId, surfaces)
//        }
//    }

    private fun startBackgroundThread() {
        HandlerThread("sharedCameraBackground").also { it ->
            backgroundThread = it
            backgroundThread.start()
            backgroundHandler = Handler(backgroundThread.getLooper())
        }
    }

    // Stop background handler thread.
//    private fun stopBackgroundThread() {
//        if (backgroundThread != null) {
//            backgroundThread.quitSafely()
//            try {
//                backgroundThread.join()
//                backgroundThread = null
//                backgroundHandler = null
//            } catch (e: InterruptedException) {
//                Log.e(
//                    TAG,
//                    "Interrupted while trying to join background handler thread",
//                    e
//                )
//            }
//        }
//    }

//    override fun onImageAvailable(imageReader: ImageReader?) {
//        imageReader?.acquireLatestImage().also { image ->
//
//        }
//    }

    override fun getSessionConfiguration(session: Session): Config {

        fun loadAugmentedImageBitmap(imageName: String): Bitmap =
            requireContext().assets.open(imageName).use { return BitmapFactory.decodeStream(it) }

        fun setupAugmentedImageDatabase(config: Config, session: Session): Boolean {
            try {
                config.augmentedImageDatabase = AugmentedImageDatabase(session).also { db ->
                    db.addImage(TEST_VIDEO_1, loadAugmentedImageBitmap(TEST_IMAGE_1))
                    db.addImage(TEST_VIDEO_2, loadAugmentedImageBitmap(TEST_IMAGE_2))
                    db.addImage(TEST_VIDEO_3, loadAugmentedImageBitmap(TEST_IMAGE_3))
                    db.addImage(TEST_VIDEO_4, loadAugmentedImageBitmap(TEST_IMAGE_4))
                    db.addImage(TEST_VIDEO_5, loadAugmentedImageBitmap(TEST_IMAGE_5))
                }
                return true
            } catch (e: IllegalArgumentException) {
                Log.e("lbddk", "Could not add bitmap to augmented image database", e)
            } catch (e: IOException) {
                Log.e("lbddk", "IO exception loading augmented image bitmap.", e)
            }
            return false
        }

        return super.getSessionConfiguration(session).also {
            it.lightEstimationMode = Config.LightEstimationMode.DISABLED
            it.focusMode = Config.FocusMode.AUTO

            if (!setupAugmentedImageDatabase(it, session)) {
                Toast.makeText(requireContext(), "Could not setup augmented image database", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun createArScene() {
        // Create an ExternalTexture for displaying the contents of the video.
        externalTexture = ExternalTexture().also {
            mediaPlayer.setSurface(it.surface)
        }

        // Create a renderable with a material that has a parameter of type 'samplerExternal' so that
        // it can display an ExternalTexture.
        ModelRenderable.builder()
            .setSource(requireContext(), R.raw.augmented_video_model)
            .build()
            .thenAccept { renderable ->
                videoRenderable = renderable
                renderable.isShadowCaster = false
                renderable.isShadowReceiver = false
                renderable.material.setExternalTexture("videoTexture", externalTexture)
            }
            .exceptionally { throwable ->
                Log.e(TAG, "Could not create ModelRenderable", throwable)
                return@exceptionally null
            }

        videoAnchorNode = VideoAnchorNode().apply {
            setParent(arSceneView.scene)
        }
    }

    /**
     * In this case, we want to support the playback of one video at a time.
     * Therefore, if ARCore loses current active image FULL_TRACKING we will pause the video.
     * If the same image gets FULL_TRACKING back, the video will resume.
     * If a new image will become active, then the corresponding video will start from scratch.
     */
    var frameImage: Image? = null
    override fun onUpdate(frameTime: FrameTime) {
        val frame = arSceneView.arFrame ?: return
        val updatedAugmentedImages = frame.getUpdatedTrackables(AugmentedImage::class.java)


        // If current active augmented image isn't tracked anymore and video playback is started - pause video playback
        val nonFullTrackingImages = updatedAugmentedImages.filter { it.trackingMethod != AugmentedImage.TrackingMethod.FULL_TRACKING }
        activeAugmentedImage?.let { activeAugmentedImage ->
            if (isArVideoPlaying() && nonFullTrackingImages.any { it.index == activeAugmentedImage.index }) {
                pauseArVideo()
            }
        }

        val fullTrackingImages = updatedAugmentedImages.filter { it.trackingMethod == AugmentedImage.TrackingMethod.FULL_TRACKING }
        if (fullTrackingImages.isEmpty()) return

        // If current active augmented image is tracked but video playback is paused - resume video playback
        activeAugmentedImage?.let { activeAugmentedImage ->
            if (fullTrackingImages.any { it.index == activeAugmentedImage.index }) {
                if (!isArVideoPlaying()) {
                    resumeArVideo()
                }
                return
            }
        }

        // Otherwise - make the first tracked image active and start video playback
        fullTrackingImages.firstOrNull()?.let { augmentedImage ->
            Log.e("lbddk", augmentedImage.name)
            if (frameImage == null) {
                try {
                        frameImage = frame.acquireCameraImage()

                        val result: Task<Text> = textRecognizer.process(
                            InputImage.fromMediaImage(
                                frameImage,
                                getCameraSensorToDisplayRotation(arSceneView.session?.cameraConfig?.cameraId)
                            )
                        )
                            .addOnSuccessListener {
                                Log.e("lbddk", it.text)
                                playbackArVideo(augmentedImage, it.text)
                            }
                            .addOnFailureListener {
                                Log.e("lbddk", it.localizedMessage)
                            }
                            .addOnCompleteListener {
                                frameImage?.close()
                                frameImage = null
                            }
                }catch (e: Exception){
                    Log.e("lbddk", e.localizedMessage)
                }
            }
//            try {
//
////                playbackArVideo(augmentedImage)
//            } catch (e: Exception) {
//                Log.e(TAG, "Could not play video [${augmentedImage.name}]", e)
//            }
        }
    }

    private fun isArVideoPlaying() = mediaPlayer.isPlaying

    private fun pauseArVideo() {
        videoAnchorNode.renderable = null
        mediaPlayer.pause()
    }

    private fun resumeArVideo() {
        mediaPlayer.start()
        fadeInVideo()
    }

    private fun dismissArVideo() {
        videoAnchorNode.anchor?.detach()
        videoAnchorNode.renderable = null
        activeAugmentedImage = null
        mediaPlayer.reset()
    }

    private fun playbackArVideo(augmentedImage: AugmentedImage, augmentedImageID: String) {
        Log.d(TAG, "playbackVideo = ${augmentedImage.name}")

        requireContext().assets.openFd(augmentedImage.name)
            .use { descriptor ->

                val metadataRetriever = MediaMetadataRetriever()
                metadataRetriever.setDataSource(
                    descriptor.fileDescriptor,
                    descriptor.startOffset,
                    descriptor.length
                )

                val videoWidth = metadataRetriever.extractMetadata(METADATA_KEY_VIDEO_WIDTH).toFloatOrNull() ?: 0f
                val videoHeight = metadataRetriever.extractMetadata(METADATA_KEY_VIDEO_HEIGHT).toFloatOrNull() ?: 0f
                val videoRotation = metadataRetriever.extractMetadata(METADATA_KEY_VIDEO_ROTATION).toFloatOrNull() ?: 0f

                // Account for video rotation, so that scale logic math works properly
                val imageSize = RectF(0f, 0f, augmentedImage.extentX, augmentedImage.extentZ)
                    .transform(rotationMatrix(videoRotation))

                val videoScaleType = VideoScaleType.CenterCrop

                videoAnchorNode.setVideoProperties(
                    videoWidth = videoWidth, videoHeight = videoHeight, videoRotation = videoRotation,
                    imageWidth = imageSize.width(), imageHeight = imageSize.height(),
                    videoScaleType = videoScaleType
                )

                // Update the material parameters
                videoRenderable.material.setFloat2(MATERIAL_IMAGE_SIZE, imageSize.width(), imageSize.height())
                videoRenderable.material.setFloat2(MATERIAL_VIDEO_SIZE, videoWidth, videoHeight)
                videoRenderable.material.setBoolean(MATERIAL_VIDEO_CROP, VIDEO_CROP_ENABLED)

                mediaPlayer.reset()
                mediaPlayer.setDataSource(descriptor)
            }.also {
                mediaPlayer.isLooping = true
                mediaPlayer.prepare()
                mediaPlayer.start()
            }


        videoAnchorNode.anchor?.detach()
        videoAnchorNode.anchor = augmentedImage.createAnchor(augmentedImage.centerPose)

        activeAugmentedImage = augmentedImage
        activeAugmentedImageID = augmentedImageID

        externalTexture.surfaceTexture.setOnFrameAvailableListener {
            it.setOnFrameAvailableListener(null)
            fadeInVideo()
        }
    }

    private fun fadeInVideo() {
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 400L
            interpolator = LinearInterpolator()
            addUpdateListener { v ->
                videoRenderable.material.setFloat(MATERIAL_VIDEO_ALPHA, v.animatedValue as Float)
            }
            doOnStart { videoAnchorNode.renderable = videoRenderable }
            start()
        }
    }

    override fun onPause() {
        super.onPause()
        dismissArVideo()
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer.release()
    }

    companion object {
        private const val TAG = "ArVideoFragment"

        private const val TEST_IMAGE_1 = "test_image_1.jpg"
        private const val TEST_IMAGE_2 = "test_image_2.jpg"
        private const val TEST_IMAGE_3 = "test_image_3.jpg"

        private const val TEST_IMAGE_4 = "4w4Dd5CE6UDpHBj4.jpg"
        private const val TEST_IMAGE_5 = "5fhUJ6RCfKKU56ef.jpg"

        private const val TEST_VIDEO_1 = "test_video_1.mp4"
        private const val TEST_VIDEO_2 = "test_video_2.mp4"
        private const val TEST_VIDEO_3 = "test_video_3.mp4"

        private const val TEST_VIDEO_4 = "4w4Dd5CE6UDpHBj4.mp4"
        private const val TEST_VIDEO_5 = "5fhUJ6RCfKKU56ef.mp4"

        private const val VIDEO_CROP_ENABLED = true

        private const val MATERIAL_IMAGE_SIZE = "imageSize"
        private const val MATERIAL_VIDEO_SIZE = "videoSize"
        private const val MATERIAL_VIDEO_CROP = "videoCropEnabled"
        private const val MATERIAL_VIDEO_ALPHA = "videoAlpha"
    }

    /**
     * Returns the rotation of the back-facing camera with respect to the display. The value is one of
     * 0, 90, 180, 270.
     */

    private fun getCameraSensorToDisplayRotation(cameraId: String?): Int {
        val characteristics: CameraCharacteristics
        characteristics = try {

            // Store a reference to the camera system service.
            (context!!.getSystemService(Context.CAMERA_SERVICE) as CameraManager).getCameraCharacteristics(cameraId!!)

        } catch (e: CameraAccessException) {
            throw RuntimeException("Unable to determine display orientation", e)
        }

        // Camera sensor orientation.
        val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)!!

        // Current display orientation.
        val displayOrientation = toDegrees((context!!.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation)

        // Make sure we return 0, 90, 180, or 270 degrees.
        return (sensorOrientation - displayOrientation + 360) % 360
    }

    private fun toDegrees(rotation: Int): Int {
        return when (rotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> throw RuntimeException("Unknown rotation $rotation")
        }
    }
}
