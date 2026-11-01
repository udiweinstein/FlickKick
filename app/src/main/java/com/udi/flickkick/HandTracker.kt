package com.udi.flickkick

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * מצלמה קדמית (CameraX) + MediaPipe Hand Landmarker במצב LIVE_STREAM.
 * לכל פריים מחזיר מערך של 42 מספרים (x,y של 21 נקודות, מנורמלים, במראה)
 * או null כשאין יד בפריים.
 */
class HandTracker(
    private val context: Context,
    private val owner: LifecycleOwner,
    private val listener: (landmarks: FloatArray?, timeMs: Long) -> Unit,
    private val onError: (String) -> Unit
) {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var landmarker: HandLandmarker? = null
    private var lastTs = 0L

    fun start() {
        landmarker = createLandmarker(Delegate.GPU) ?: createLandmarker(Delegate.CPU)
        if (landmarker == null) {
            onError("לא נמצא מודל זיהוי היד (hand_landmarker.task)")
            return
        }
        bindCamera()
    }

    private fun createLandmarker(delegate: Delegate): HandLandmarker? = try {
        val base = BaseOptions.builder()
            .setModelAssetPath("hand_landmarker.task")
            .setDelegate(delegate)
            .build()
        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumHands(1)
            .setMinHandDetectionConfidence(0.5f)
            .setMinHandPresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setResultListener { result: HandLandmarkerResult, _ -> deliver(result) }
            .setErrorListener { e -> Log.e(TAG, "MediaPipe error", e) }
            .build()
        HandLandmarker.createFromOptions(context, options)
    } catch (e: Exception) {
        Log.w(TAG, "Cannot create landmarker with $delegate", e)
        null
    }

    private fun bindCamera() {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val provider = future.get()
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    Size(640, 480),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                                )
                            ).build()
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                analysis.setAnalyzer(executor) { proxy -> analyze(proxy) }

                provider.unbindAll()
                provider.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed", e)
                onError("לא ניתן לפתוח את המצלמה הקדמית")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun analyze(proxy: ImageProxy) {
        val lm = landmarker
        if (lm == null) {
            proxy.close(); return
        }
        val rotation = proxy.imageInfo.rotationDegrees
        val src: Bitmap = try { proxy.toBitmap() } finally { proxy.close() }
        // סיבוב לזקוף + היפוך אופקי (תמונת מראה, כמו "סלפי")
        val m = Matrix().apply {
            postRotate(rotation.toFloat())
            postScale(-1f, 1f)
        }
        val upright = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        var ts = SystemClock.uptimeMillis()
        if (ts <= lastTs) ts = lastTs + 1
        lastTs = ts
        try {
            lm.detectAsync(BitmapImageBuilder(upright).build(), ts)
        } catch (e: Exception) {
            Log.w(TAG, "detectAsync failed", e)
        }
    }

    private fun deliver(result: HandLandmarkerResult) {
        val hands = result.landmarks()
        if (hands.isEmpty()) {
            listener(null, result.timestampMs())
            return
        }
        val hand = hands[0]
        val arr = FloatArray(42)
        for (i in 0 until minOf(21, hand.size)) {
            arr[i * 2] = hand[i].x()
            arr[i * 2 + 1] = hand[i].y()
        }
        listener(arr, result.timestampMs())
    }

    fun close() {
        executor.shutdown()
        landmarker?.close()
        landmarker = null
    }

    companion object {
        private const val TAG = "HandTracker"
    }
}
