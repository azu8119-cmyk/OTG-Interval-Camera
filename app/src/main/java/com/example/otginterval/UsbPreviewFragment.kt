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

class UsbPreviewFragment : CameraFragment() {
    private var frame: FrameLayout? = null
    private var texture: AspectRatioTextureView? = null
    override fun getRootView(inflater: LayoutInflater, container: ViewGroup?): View {
        val root = FrameLayout(requireContext())
        val view = AspectRatioTextureView(requireContext())
        root.addView(view, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        frame = root
        texture = view
        return root
    }
    override fun getCameraView(): IAspectRatio? = texture
    override fun getCameraViewContainer(): ViewGroup? = frame
    override fun getGravity(): Int = Gravity.CENTER
    override fun getCameraRequest(): CameraRequest {
        val prefs = requireContext().getSharedPreferences("camera_settings", 0)
        val w = prefs.getInt("width", 640)
        val h = prefs.getInt("height", 480)
        val f = prefs.getString("format", "AUTO")
        val mjpeg = f == "MJPEG" || (f == "AUTO" && w * h > 640 * 480)
        return CameraRequest.Builder().setPreviewWidth(w).setPreviewHeight(h)
            .setPreviewFormat(if (mjpeg) CameraRequest.PreviewFormat.FORMAT_MJPEG
                else CameraRequest.PreviewFormat.FORMAT_YUYV)
            .setRenderMode(CameraRequest.RenderMode.OPENGL)
            // Capture full-size NV21 data directly from the USB camera instead
            // of saving the screen-sized OpenGL preview (e.g. 1080×607).
            .setRawPreviewData(true)
            .setCaptureRawImage(true)
            .setAudioSource(CameraRequest.AudioSource.NONE).create()
    }
    override fun onCameraState(self: MultiCameraClient.ICamera,
        code: ICameraStateCallBack.State, msg: String?) {
        val host = activity as? MainActivity ?: return
        when (code) {
            ICameraStateCallBack.State.OPENED -> host.onUsbState(true, null)
            ICameraStateCallBack.State.CLOSED -> host.onUsbState(false, "Камера отключена")
            ICameraStateCallBack.State.ERROR -> host.onUsbState(false, msg ?: "Ошибка USB UVC")
        }
    }
    fun availableSizes(): List<Pair<Int, Int>> =
        getCurrentCamera()?.getAllPreviewSizes()?.map { it.width to it.height }
            ?.distinct()?.sortedByDescending { it.first.toLong() * it.second } ?: emptyList()
    fun activeSize(): Pair<Int, Int>? = getCurrentCamera()?.getCameraRequest()?.let {
        it.previewWidth to it.previewHeight
    }
    fun isReady(): Boolean = getCurrentCamera()?.isCameraOpened() == true
    fun takeShot(path: String, callback: ICaptureCallBack) {
        if (!isReady()) { callback.onError("USB-камера не открыта"); return }
        captureImage(callback, path)
    }
    override fun onDestroyView() {
        texture = null
        frame = null
        super.onDestroyView()
    }
}
