package io.github.xlabb.speedometer

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.xlabb.speedometer.ui.SpeedometerRoute
import io.github.xlabb.speedometer.ui.SpeedometerTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Android 15+ enforces edge-to-edge, so the content must lay itself out under
        // the system bars. The screen applies safeDrawingPadding() to compensate.
        enableEdgeToEdge()

        // A speedometer that blanks out after 30 seconds on a windscreen mount is
        // useless. Cleared automatically when the activity goes away.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            SpeedometerTheme {
                SpeedometerRoute()
            }
        }
    }
}
