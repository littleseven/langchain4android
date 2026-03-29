package com.picme.core.image

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES20
import android.util.Log
import androidx.camera.view.PreviewView
import jp.co.cyberagent.android.gpuimage.GPUImage
import jp.co.cyberagent.android.gpuimage.filter.GPUImageFilter
import kotlinx.coroutines.*

class BeautyPreviewProcessor(
    private val context: Context,
    private val previewView: PreviewView
) {
    companion object {
        private const val TAG = "PicMe:BeautyPreview"
    }
    
    private val gpuImage: GPUImage = GPUImage(context)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    var smoothingStrength: Float = 0f
    var whiteningStrength: Float = 0f
    var slimFaceStrength: Float = 0f
    var bigEyesStrength: Float = 0f
    
    fun updateBeautyFilters() {
        scope.launch {
            val filters = mutableListOf<GPUImageFilter>()
            
            if (smoothingStrength > 0f) {
                filters.add(GPUImageSmoothSkinFilter().apply {
                    setIntensity(smoothingStrength / 100f)
                })
            }
            
            if (whiteningStrength > 0f) {
                filters.add(GPUImageColorMatrixFilter().apply {
                    setBrightness(whiteningStrength / 100f * 0.3f)
                })
            }
            
            withContext(Dispatchers.Main) {
                if (filters.isEmpty()) {
                    gpuImage.setFilter(null)
                } else {
                    val filterGroup = GPUImageFilterGroup()
                    filters.forEach { filter -> filterGroup.addFilter(filter) }
                    gpuImage.setFilter(filterGroup)
                }
            }
        }
    }
    
    fun release() {
        scope.cancel()
    }
}

class GPUImageSmoothSkinFilter : GPUImageFilter(
    NO_FILTER_VERTEX_SHADER,
    SMOOTH_SKIN_FRAGMENT_SHADER
) {
    private var intensityLocation = -1
    private var intensity: Float = 0.5f
    
    override fun onInit() {
        super.onInit()
        intensityLocation = GLES20.glGetUniformLocation(program, "intensity")
    }
    
    override fun onInitialized() {
        super.onInitialized()
        setIntensity(intensity)
    }
    
    fun setIntensity(value: Float) {
        intensity = value.coerceIn(0f, 1f)
        setFloat(intensityLocation, intensity)
    }
    
    companion object {
        private const val SMOOTH_SKIN_FRAGMENT_SHADER = """
            precision highp float;
            uniform sampler2D inputImageTexture;
            uniform float intensity;
            varying vec2 textureCoordinate;
            void main() {
                vec4 textureColor = texture2D(inputImageTexture, textureCoordinate);
                vec3 smoothed = textureColor.rgb * (1.0 + intensity * 0.2);
                gl_FragColor = vec4(smoothed, textureColor.a);
            }
        """
    }
}

class GPUImageColorMatrixFilter : GPUImageFilter(
    NO_FILTER_VERTEX_SHADER,
    COLOR_MATRIX_FRAGMENT_SHADER
) {
    private var brightnessLocation = -1
    private var brightness: Float = 0f
    
    override fun onInit() {
        super.onInit()
        brightnessLocation = GLES20.glGetUniformLocation(program, "brightness")
    }
    
    override fun onInitialized() {
        super.onInitialized()
        setBrightness(brightness)
    }
    
    fun setBrightness(value: Float) {
        brightness = value
        setFloat(brightnessLocation, brightness)
    }
    
    companion object {
        private const val COLOR_MATRIX_FRAGMENT_SHADER = """
            precision highp float;
            uniform sampler2D inputImageTexture;
            uniform float brightness;
            varying vec2 textureCoordinate;
            void main() {
                vec4 textureColor = texture2D(inputImageTexture, textureCoordinate);
                vec3 color = textureColor.rgb;
                color = color * (1.0 + brightness) + brightness * 0.1;
                gl_FragColor = vec4(color, textureColor.a);
            }
        """
    }
}

class GPUImageFilterGroup : jp.co.cyberagent.android.gpuimage.filter.GPUImageFilterGroup() {
    override fun addFilter(filter: GPUImageFilter) {
        super.addFilter(filter)
    }
}
