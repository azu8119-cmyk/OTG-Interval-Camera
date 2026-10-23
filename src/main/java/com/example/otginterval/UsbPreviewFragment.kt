package com.example.otginterval

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.base.CameraFragment
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.callback.ICaptureCallBack
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.widget.AspectRatioTextureView
import com.jiangdg.ausbc.widget.IAspectRatio

/** AUSBC handles USB permission, UVC decoding and preview. */
class UsbPreviewFragment : CameraFragment() {
    private var previewContainer: FrameLayout? = null
    private var cameraView: AspectRatioTextureView? = null

    override fun getRootView(inflater: LayoutInflater, container: ViewGroup?): View {
        val ctx = requireContext()
        val root = FrameLayout(ctx)
        val texture = AspectRatioTextureView(ctx)
        root.addView(texture, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            Gravity.CENTER
        ))
        previewContainer = root
        cameraView = texture
        return root
    }

    override fun getCameraView(): IAspectRatio? = cameraView

    override fun getCameraViewContainer(): ViewGroup? = previewContainer

    override fun getGravity(): Int = Gravity.CENTER

    override fun getCameraRequest(): CameraRequest = CameraRequest.Builder()
        .setPreviewWidth(1280)
        .setPreviewHeight(720)
        .setPreviewFormat(CameraRequest.PreviewFormat.FORMAT_MJPEG)
        .setRenderMode(CameraRequest.RenderMode.OPENGL)
        .setAudioSource(CameraRequest.AudioSource.NONE)
        .create()

    override fun onCameraState(self: MultiCameraClient.ICamera,
                               code: ICameraStateCallBack.State,
                               msg: String?) {
        val host = activity as? MainActivity ?: return
        when (code) {
            ICameraStateCallBack.State.OPENED -> host.onUsbState(true, null)
            ICameraStateCallBack.State.CLOSED -> host.onUsbState(false, "Камера отключена")
            ICameraStateCallBack.State.ERROR -> host.onUsbState(false, msg ?: "Ошибка USB-камеры")
        }
    }

    fun availableSizes(): List<Pair<Int, Int>> =
        getCurrentCamera()?.getAllPreviewSizes()
            ?.map { it.width to it.height }
            ?.distinct()
            ?.sortedWith(compareByDescending<Pair<Int, Int>> { it.first * it.second })
            ?: emptyList()

    fun changeSize(width: Int, height: Int) {
        getCurrentCamera()?.updateResolution(width, height)
    }

    fun takeShot(targetPath: String, callback: ICaptureCallBack) {
        if (getCurrentCamera()?.isCameraOpened() != true) {
            callback.onError("USB-камера не открыта")
            return
        }
        captureImage(callback, targetPath)
    }

    fun isReady(): Boolean = getCurrentCamera()?.isCameraOpened() == true

    override fun onDestroyView() {
        cameraView = null
        previewContainer = null
        super.onDestroyView()
    }
}
