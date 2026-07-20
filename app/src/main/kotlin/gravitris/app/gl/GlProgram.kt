package gravitris.app.gl

import android.opengl.GLES30
import android.util.Log

/**
 * Shader compilation and linking, with failures reported loudly.
 *
 * GL silently produces a program that draws nothing when a shader fails to
 * compile, and a black screen is an extremely expensive thing to debug on a
 * device you do not have in the room. So every failure here throws with the
 * driver's own info log attached.
 *
 * ADR 0010 §6: all GL objects must be recreatable from scratch. Shaders are
 * compiled from source strings in the APK and there are no texture assets, so
 * recovering from context loss is just calling this again.
 */
object GlProgram {

    private const val TAG = "GravitrisGL"

    fun build(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = compile(GLES30.GL_VERTEX_SHADER, vertexSource)
        val fragmentShader = compile(GLES30.GL_FRAGMENT_SHADER, fragmentSource)

        val program = GLES30.glCreateProgram()
        if (program == 0) error("glCreateProgram returned 0")

        GLES30.glAttachShader(program, vertexShader)
        GLES30.glAttachShader(program, fragmentShader)
        GLES30.glLinkProgram(program)

        val status = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] != GLES30.GL_TRUE) {
            val log = GLES30.glGetProgramInfoLog(program)
            GLES30.glDeleteProgram(program)
            error("Program link failed: $log")
        }

        // The shaders are linked into the program; the objects themselves are
        // no longer needed and would otherwise leak for the process lifetime.
        GLES30.glDetachShader(program, vertexShader)
        GLES30.glDetachShader(program, fragmentShader)
        GLES30.glDeleteShader(vertexShader)
        GLES30.glDeleteShader(fragmentShader)

        return program
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        if (shader == 0) error("glCreateShader($type) returned 0")

        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)

        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] != GLES30.GL_TRUE) {
            val log = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            Log.e(TAG, "Shader compile failed:\n$source\n$log")
            error("Shader compile failed: $log")
        }
        return shader
    }
}
