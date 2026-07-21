package gravitris.app.gl

import android.opengl.GLSurfaceView
import android.util.Log
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay

/**
 * Picks an EGL surface config with hardware MSAA (`docs/ux/visual-direction.md`
 * §17), falling back gracefully when the driver offers none.
 *
 * §17 chose hardware multisampling — resolved by the driver at the rasteriser/
 * resolve stage — as the right tool for smoothing the opaque silhouette edges of
 * the pieces, the §16 rounded corners and the well frame all at once, with **no
 * shader change** and no violation of `Shaders.kt`'s "no blend, no discard"
 * rules. `GLSurfaceView`'s default chooser gives a single-sample config, so this
 * replaces it.
 *
 * ## Why a custom chooser rather than `setEGLConfigChooser(r,g,b,a,depth,stencil)`
 *
 * That built-in overload cannot request a multisample buffer at all. The full
 * `EGLConfigChooser` is the only way to ask `eglChooseConfig` for
 * `EGL_SAMPLE_BUFFERS`/`EGL_SAMPLES`.
 *
 * ## Graceful fallback is the whole point of doing it by hand
 *
 * A device — or the software emulator this project screenshots on — that has no
 * matching MSAA config must still get a working surface, not a black screen. So
 * this tries the requested sample count, then 2×, then no MSAA, and returns the
 * first that the driver actually offers. A build-time constant
 * ([gravitris.app.Tunables.MSAA_SAMPLES]) of 0 skips MSAA entirely — that is the
 * §17 "cut it if the on-device budget disagrees" dial, expressed at config time
 * because the sample count is fixed when the surface is created and cannot ride
 * the per-frame `shadeLevel`.
 *
 * Depth and stencil are requested at 0: the renderer disables the depth test and
 * never uses stencil (`GameRenderer.onSurfaceCreated`), so asking for either
 * would only cost attachment bandwidth — which under MSAA is multiplied.
 */
class MsaaConfigChooser(private val requestedSamples: Int) : GLSurfaceView.EGLConfigChooser {

    override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
        if (requestedSamples >= 2) {
            find(egl, display, requestedSamples)?.let {
                Log.i(TAG, "MSAA: using ${requestedSamples}x multisample config")
                return it
            }
            if (requestedSamples > 2) {
                find(egl, display, 2)?.let {
                    Log.i(TAG, "MSAA: ${requestedSamples}x unavailable, using 2x")
                    return it
                }
            }
            Log.i(TAG, "MSAA: no multisample config available, falling back to single-sample")
        }
        return find(egl, display, 0)
            ?: error("no EGL config available even without MSAA — the driver offers no RGBA8 surface")
    }

    private fun find(egl: EGL10, display: EGLDisplay, samples: Int): EGLConfig? {
        val attribs = buildList {
            add(EGL10.EGL_RENDERABLE_TYPE); add(EGL_OPENGL_ES2_BIT) // ES2 bit also selects ES3 contexts
            add(EGL10.EGL_RED_SIZE); add(8)
            add(EGL10.EGL_GREEN_SIZE); add(8)
            add(EGL10.EGL_BLUE_SIZE); add(8)
            add(EGL10.EGL_ALPHA_SIZE); add(8)
            add(EGL10.EGL_DEPTH_SIZE); add(0)
            add(EGL10.EGL_STENCIL_SIZE); add(0)
            if (samples > 0) {
                add(EGL10.EGL_SAMPLE_BUFFERS); add(1)
                add(EGL10.EGL_SAMPLES); add(samples)
            }
            add(EGL10.EGL_NONE)
        }.toIntArray()

        val count = IntArray(1)
        if (!egl.eglChooseConfig(display, attribs, null, 0, count) || count[0] == 0) return null

        val configs = arrayOfNulls<EGLConfig>(count[0])
        if (!egl.eglChooseConfig(display, attribs, configs, count[0], count) || count[0] == 0) {
            return null
        }

        // eglChooseConfig treats EGL_SAMPLES as a minimum and returns matches
        // sorted best-first, so the first entry satisfies the request.
        return configs[0]
    }

    private companion object {
        const val TAG = "GravitrisGL"

        /** EGL_OPENGL_ES2_BIT. EGL10 does not expose an ES3 bit; on Android the
         *  ES2 renderable bit is the correct selector for an ES3 context too. */
        const val EGL_OPENGL_ES2_BIT = 0x0004
    }
}
