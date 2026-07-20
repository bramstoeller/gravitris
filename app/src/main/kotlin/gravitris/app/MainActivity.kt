package gravitris.app

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.widget.TextView
import gravitris.coresim.CoreSimBuildScaffold
// The Android `namespace` (nl.brainbuilders.gravitris, in app/build.gradle.kts)
// is the reverse-domain applicationId, kept deliberately separate from the
// `gravitris.*` Kotlin source package convention shared with :core-sim — so
// the generated R class needs an explicit import rather than living in this
// file's own package.
import nl.brainbuilders.gravitris.R

/**
 * Stage 0 placeholder. It exists only to prove :app installs, launches, and
 * depends on :core-sim correctly — it is not the game shell.
 *
 * Deliberately plain `Activity` + `TextView`, no AndroidX: the real shell
 * (GLSurfaceView, edge-to-edge insets, predictive back, haptics — ADR 0010)
 * is Stage 1B's job (docs/build-order.md) and belongs to the
 * frontend-engineer. Replace this file wholesale rather than growing it.
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            TextView(this).apply {
                text = "${getString(R.string.stage0_placeholder)}\n\n" +
                    "linked module: ${CoreSimBuildScaffold.MODULE_NAME}"
                setBackgroundColor(Color.BLACK)
                setTextColor(Color.WHITE)
                textSize = 18f
                setPadding(48, 96, 48, 48)
            }
        )
    }
}
