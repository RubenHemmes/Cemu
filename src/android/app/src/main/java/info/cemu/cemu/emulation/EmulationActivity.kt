package info.cemu.cemu.emulation

import android.annotation.SuppressLint
import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.view.Display
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import info.cemu.cemu.BuildConfig
import info.cemu.cemu.common.ui.components.ActivityContent
import info.cemu.cemu.common.ui.localization.TranslatableContent
import info.cemu.cemu.common.android.display.DisplayUtils
import kotlin.system.exitProcess

class EmulationActivity : AppCompatActivity() {
    private lateinit var sensorManager: SensorManager
    private lateinit var viewModel: EmulationViewModel
    private var padPresentation: PadPresentation? = null

    private inner class PadPresentation(context: Context, display: Display) :
            Presentation(context, display) {
        private lateinit var surfaceView: SurfaceView

        @SuppressLint("ClickableViewAccessibility")
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            window?.addFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            )
            val mode = display.mode
            surfaceView = SurfaceView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )

                // Get physical dimensions and swap if needed for landscape
                var surfaceWidth = mode.physicalWidth
                var surfaceHeight = mode.physicalHeight

                if (surfaceWidth < surfaceHeight) {
                    val tmp = surfaceWidth
                    surfaceWidth = surfaceHeight
                    surfaceHeight = tmp
                }

                // Swap dimensions again if rotating left
                val sideMenuState = viewModel.sideMenuState.value
                if (sideMenuState.isExternalScreenRotatedLeft) {
                    val tmp = surfaceWidth
                    surfaceWidth = surfaceHeight
                    surfaceHeight = tmp
                }
                holder.setFixedSize(surfaceWidth, surfaceHeight)

                holder.addCallback(viewModel.padHolderCallback)
                holder.addCallback(object : android.view.SurfaceHolder.Callback {
                    override fun surfaceChanged(
                        holder: android.view.SurfaceHolder,
                        format: Int,
                        width: Int,
                        height: Int
                    ) {
                        viewModel.updateSurfaceDimensions(isMainCanvas = false, width, height)
                    }

                    override fun surfaceCreated(holder: android.view.SurfaceHolder) {
                    }
                    override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {
                    }
                })

                CanvasOnTouchListener().also { listener ->
                    setOnTouchListener(listener)
                    viewModel.padPresentationTouchListener = listener;
                    viewModel.updateTouchListenerConfigurations();
                }
            }
            setContentView(surfaceView)
        }
    }

    private fun updatePadPresentation() {
        dismissPadPresentation()

        val sideMenuState = viewModel.sideMenuState.value
        if (!sideMenuState.isPadVisible || !sideMenuState.isPadOnExternalDisplay) {
            return
        }

        val externalDisplay = DisplayUtils.getExternalDisplay(this)

        if (externalDisplay != null) {
            padPresentation = PadPresentation(this, externalDisplay)
            padPresentation?.show()
        }
    }

    private fun dismissPadPresentation() {
        padPresentation?.dismiss()
        padPresentation = null
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (InputHandler.onMotionEvent(event)) {
            return true
        }

        return super.onGenericMotionEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            return super.dispatchKeyEvent(event)
        }

        if (InputHandler.onKeyEvent(event)) {
            return true
        }

        return super.dispatchKeyEvent(event)
    }

    private fun getGamePath(): String {
        val extras = intent.extras
        val data = intent.data
        var launchPath: String? = null

        if (extras != null) {
            launchPath = extras.getString(EXTRA_LAUNCH_PATH)
        }

        if (launchPath == null && data != null) {
            launchPath = data.toString()
        }

        if (launchPath == null) {
            throw RuntimeException("launchPath is null")
        }

        return launchPath
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sensorManager = SensorManager(this)
        sensorManager.setDeviceRotationProvider { display.rotation }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setFullscreen()

        val gamePath = getGamePath()

        setContent {
            TranslatableContent {
                ActivityContent {
                    EmulationScreen(
                        gamePath = gamePath,
                        setMotionSensorEnabled = sensorManager::setIsListening,
                        onQuit = ::onQuit,
                        onViewModelReady = { vm ->
                            viewModel = vm
                        },
                        onPadOnExternalDisplayChange = {
                            updatePadPresentation()
                        },
                        onExternalScreenRotationChange = {
                            updatePadPresentation()
                        }
                    )
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.pauseListening()
    }

    override fun onResume() {
        super.onResume()
        sensorManager.resumeListening()
        // Recreate pad presentation if it should be shown
        if (::viewModel.isInitialized &&
            viewModel.isEmulationInitialized.value) {
            updatePadPresentation()
        }
    }

    override fun onStop() {
        super.onStop()
        dismissPadPresentation()
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorManager.pauseListening()
        dismissPadPresentation()
    }

    private fun setFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun onQuit() {
        finish()
        exitProcess(0)
    }

    companion object {
        const val EXTRA_LAUNCH_PATH: String = BuildConfig.APPLICATION_ID + ".LaunchPath"
    }
}
