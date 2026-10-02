package com.tuchus.measure

import android.opengl.GLES11Ext
import android.opengl.GLES20
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** Draws the live camera image behind everything else. */
class BackgroundRenderer {
    var textureId = -1
        private set
    private var program = 0
    private var positionAttr = 0
    private var texCoordAttr = 0

    private val quad: FloatBuffer = floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val texCoords: FloatBuffer = floatBuffer(FloatArray(8))
    private val corners = FloatArray(8)          // texture coords at bottom-left, bottom-right, top-left, top-right
    private val zoomCoords: FloatBuffer = floatBuffer(FloatArray(8))

    fun create() {
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        textureId = tex[0]
        val target = GLES11Ext.GL_TEXTURE_EXTERNAL_OES
        GLES20.glBindTexture(target, textureId)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, shader(GLES20.GL_VERTEX_SHADER, VERTEX))
        GLES20.glAttachShader(program, shader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT))
        GLES20.glLinkProgram(program)
        positionAttr = GLES20.glGetAttribLocation(program, "a_Position")
        texCoordAttr = GLES20.glGetAttribLocation(program, "a_TexCoord")
    }

    fun draw(frame: Frame) {
        if (frame.hasDisplayGeometryChanged()) {
            quad.position(0); texCoords.position(0)
            frame.transformCoordinates2d(
                Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, quad,
                Coordinates2d.TEXTURE_NORMALIZED, texCoords
            )
            texCoords.position(0)
            texCoords.get(corners)
        }
        if (frame.timestamp == 0L) return
        quad.position(0); texCoords.position(0)

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUseProgram(program)
        GLES20.glVertexAttribPointer(positionAttr, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glVertexAttribPointer(texCoordAttr, 2, GLES20.GL_FLOAT, false, 0, texCoords)
        GLES20.glEnableVertexAttribArray(positionAttr)
        GLES20.glEnableVertexAttribArray(texCoordAttr)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(positionAttr)
        GLES20.glDisableVertexAttribArray(texCoordAttr)
        GLES20.glDepthMask(true)
    }

    /**
     * Draws a zoomed-in square of the camera image into a square of the screen.
     * [x], [y], [size] are in GL pixels (y from the bottom); the region shown is centred on the screen.
     */
    fun drawZoomed(x: Int, y: Int, size: Int, zoom: Float, screenW: Int, screenH: Int) {
        if (screenW <= 0 || screenH <= 0) return
        val hu = size / zoom / 2f / screenW
        val hv = size / zoom / 2f / screenH
        val region = floatArrayOf(0.5f - hu, 0.5f - hv, 0.5f + hu, 0.5f - hv, 0.5f - hu, 0.5f + hv, 0.5f + hu, 0.5f + hv)
        zoomCoords.position(0)
        for (i in 0 until 4) {
            val u = region[i * 2]; val v = region[i * 2 + 1]
            for (k in 0..1) {
                zoomCoords.put(
                    corners[k] * (1 - u) * (1 - v) + corners[2 + k] * u * (1 - v) +
                        corners[4 + k] * (1 - u) * v + corners[6 + k] * u * v
                )
            }
        }
        zoomCoords.position(0); quad.position(0)
        GLES20.glViewport(x, y, size, size)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUseProgram(program)
        GLES20.glVertexAttribPointer(positionAttr, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glVertexAttribPointer(texCoordAttr, 2, GLES20.GL_FLOAT, false, 0, zoomCoords)
        GLES20.glEnableVertexAttribArray(positionAttr)
        GLES20.glEnableVertexAttribArray(texCoordAttr)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(positionAttr)
        GLES20.glDisableVertexAttribArray(texCoordAttr)
        GLES20.glViewport(0, 0, screenW, screenH)
    }

    private fun shader(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        return s
    }

    companion object {
        private fun floatBuffer(values: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(values); position(0)
            }

        private const val VERTEX = """
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;
            void main() { gl_Position = a_Position; v_TexCoord = a_TexCoord; }
        """

        private const val FRAGMENT = """#extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 v_TexCoord;
            uniform samplerExternalOES sTexture;
            void main() { gl_FragColor = texture2D(sTexture, v_TexCoord); }
        """
    }
}
