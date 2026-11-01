package com.udi.flickkick

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : ComponentActivity() {

    private lateinit var game: GameView
    private var tracker: HandTracker? = null

    private val detector = FlickDetector { power -> game.queueKick(GameView.Kick(power)) }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startTracking() else game.cameraDenied = true
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        game = GameView(this)
        game.detector = detector
        setContentView(game)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, game).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) startTracking()
        else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun startTracking() {
        tracker = HandTracker(
            context = this,
            owner = this,
            listener = { lm, ts ->
                detector.onFrame(lm, ts)
                game.handPts = lm
            },
            onError = { msg -> game.errorText = msg }
        ).also { it.start() }
    }

    override fun onDestroy() {
        tracker?.close()
        super.onDestroy()
    }
}
