package com.example.otginterval

import android.Manifest
import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentActivity
import com.jiangdg.ausbc.callback.ICaptureCallBack
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : FragmentActivity() {
    private val prefs by lazy { getSharedPreferences("camera_settings", MODE_PRIVATE) }
    private val ui = Handler(Looper.getMainLooper())
    private val writer = Executors.newSingleThreadExecutor()
    private val writing = AtomicBoolean(false)
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss_SSS", Locale.US)
    private lateinit var root: FrameLayout
    private lateinit var cameraHolder: FrameLayout
    private lateinit var statusText: TextView
    private lateinit var folderButton: Button
    private lateinit var settingsButton: Button
    private lateinit var pipButton: Button
    private var cameraFragment: UsbPreviewFragment? = null
    private var folderUri: Uri? = null
    private var intervalSeconds = 5
    private var takingSeries = false
    private var cameraIsOpen = false
    private var photoCount = 0

    private val requestCamera = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) showPreview()
        else status("Необходимо разрешение CAMERA для USB-камеры")
    }
    private val chooseFolder = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            folderUri = uri
            prefs.edit().putString("folder", uri.toString()).apply()
            folderButton.visibility = View.GONE
            connectCamera()
        } catch (ex: Exception) { status("Ошибка выбора папки: " + ex.localizedMessage) }
    }
    private val tick = object : Runnable {
        override fun run() {
            if (!takingSeries) return
            if (cameraIsOpen && writing.compareAndSet(false, true)) capture()
            if (takingSeries) ui.postDelayed(this, intervalSeconds * 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        folderUri = prefs.getString("folder", null)?.let(Uri::parse)
        intervalSeconds = prefs.getInt("interval", 5).coerceIn(1, 300)
        buildUi()
        if (folderUri == null) root.post { chooseFolder.launch(null) }
        else connectCamera()
    }

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        cameraHolder = FrameLayout(this).apply { id = View.generateViewId() }
        root.addView(cameraHolder, FrameLayout.LayoutParams(-1, -1))
        folderButton = Button(this).apply {
            text = "Выбрать папку"
            setOnClickListener { if (!takingSeries) chooseFolder.launch(null) }
        }
        root.addView(folderButton, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        statusText = TextView(this).apply {
            text = "Подключите USB-камеру"
            textSize = 13f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(170, 0, 0, 0))
            setPadding(dp(12), dp(9), dp(12), dp(9))
            maxLines = 4
        }
        root.addView(statusText, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        settingsButton = Button(this).apply {
            text = "⚙"
            textSize = 24f
            setOnClickListener { settings() }
        }
        root.addView(settingsButton, FrameLayout.LayoutParams(dp(60), dp(56),
            Gravity.TOP or Gravity.END).apply { setMargins(0, dp(10), dp(12), 0) })
        pipButton = Button(this).apply {
            text = "▣"
            contentDescription = "Показать поверх приложений (PiP)"
            setOnClickListener { floatingWindow() }
        }
        root.addView(pipButton, FrameLayout.LayoutParams(dp(58), dp(56),
            Gravity.TOP or Gravity.START).apply { setMargins(dp(12), dp(10), 0, 0) })
        setContentView(root)
        folderButton.visibility = if (folderUri == null) View.VISIBLE else View.GONE
    }

    private fun connectCamera() {
        if (checkSelfPermission(Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED) {
            status("Ожидаем разрешение CAMERA…")
            requestCamera.launch(Manifest.permission.CAMERA)
        } else showPreview()
    }
    private fun showPreview() {
        if (isFinishing || isDestroyed || cameraFragment != null) return
        reconnectPreview()
    }
    private fun reconnectPreview() {
        if (takingSeries) {
            Toast.makeText(this, "Сначала остановите съёмку", Toast.LENGTH_SHORT).show()
            return
        }
        cameraIsOpen = false
        val fragment = UsbPreviewFragment()
        cameraFragment = fragment
        supportFragmentManager.beginTransaction().replace(cameraHolder.id, fragment, "usb").commit()
        status("Открываем USB-видеопоток. USB-устройств: " + usbCount())
    }
    fun onUsbState(opened: Boolean, detail: String?) {
        runOnUiThread {
            cameraIsOpen = opened
            if (!opened && takingSeries) stopSeries()
            status(if (opened) "USB-камера открыта. Нажмите ⚙" else
                (detail ?: "Нет видеопотока") + ". USB-устройств: " + usbCount())
        }
    }
    private fun usbCount() = (getSystemService(Context.USB_SERVICE) as UsbManager).deviceList.size
    private fun diagnostics() {
        val usb = getSystemService(Context.USB_SERVICE) as UsbManager
        val info = StringBuilder()
        info.append("Разрешение CAMERA: ")
            .append(checkSelfPermission(Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED).append("\n")
        info.append("Видеопоток открыт: ").append(cameraIsOpen).append("\n")
        info.append("USB-устройств: ").append(usb.deviceList.size).append("\n")
        usb.deviceList.values.forEach { dev ->
            info.append("\n").append(dev.productName ?: dev.deviceName)
            info.append("\nVID:PID ")
                .append("%04X:%04X".format(Locale.US, dev.vendorId, dev.productId))
            info.append("\nUSB-разрешение: ").append(usb.hasPermission(dev)).append("\n")
            for (i in 0 until dev.interfaceCount) {
                val intf = dev.getInterface(i)
                info.append("interface ").append(i).append(" class=")
                    .append(intf.interfaceClass).append(" subclass=")
                    .append(intf.interfaceSubclass).append("\n")
            }
        }
        AlertDialog.Builder(this).setTitle("Диагностика USB")
            .setMessage(info.toString()).setPositiveButton("Закрыть", null).show()
    }
    private fun settings() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(8))
        }
        fun heading(s: String): TextView = TextView(this).apply {
            text = s
            textSize = 16f
            setPadding(0, dp(12), 0, dp(5))
        }
        val reported = cameraFragment?.availableSizes().orEmpty()
        val modes = (reported + listOf(
            640 to 480, 1280 to 720, 1920 to 1080, 2560 to 1440
        )).distinct().sortedByDescending { it.first.toLong() * it.second }
        box.addView(heading("Разрешение камеры"))
        val sizeSpin = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                modes.map { it.first.toString() + " × " + it.second })
            val ix = modes.indexOf(prefs.getInt("width", 640) to prefs.getInt("height", 480))
            if (ix >= 0) setSelection(ix)
        }
        box.addView(sizeSpin)
        box.addView(TextView(this).apply {
            text = "Не все разрешения поддерживаются каждой камерой."
        })
        box.addView(heading("Формат видеопотока"))
        val formats = listOf("AUTO", "MJPEG", "YUYV")
        val fmtSpin = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("Auto", "MJPEG", "YUYV"))
            setSelection(formats.indexOf(prefs.getString("format", "AUTO")).coerceAtLeast(0))
        }
        box.addView(fmtSpin)
        box.addView(heading("Интервал снимков (1–300 секунд)"))
        val field = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            setText(intervalSeconds.toString())
        }
        box.addView(field)
        val choose = Button(this).apply { text = "Изменить папку" }
        val retry = Button(this).apply { text = "Переподключить USB-камеру" }
        val diag = Button(this).apply { text = "Диагностика USB" }
        val apply = Button(this).apply { text = "Применить разрешение" }
        val start = Button(this).apply {
            text = if (takingSeries) "Остановить съёмку" else "Начать съёмку"
        }
        listOf(choose, retry, diag, apply, start).forEach(box::addView)
        val dialog = AlertDialog.Builder(this).setTitle("Настройки камеры")
            .setView(ScrollView(this).apply { addView(box) })
            .setNegativeButton("Закрыть", null).create()
        fun save(): Boolean {
            val n = field.text.toString().toIntOrNull()
            if (n == null || n !in 1..300) {
                field.error = "Введите число 1–300"
                return false
            }
            intervalSeconds = n
            val mode = modes[sizeSpin.selectedItemPosition]
            prefs.edit().putInt("interval", n).putInt("width", mode.first)
                .putInt("height", mode.second)
                .putString("format", formats[fmtSpin.selectedItemPosition]).apply()
            return true
        }
        choose.setOnClickListener { if (!takingSeries) { dialog.dismiss(); chooseFolder.launch(null) } }
        retry.setOnClickListener { dialog.dismiss(); reconnectPreview() }
        diag.setOnClickListener { diagnostics() }
        apply.setOnClickListener {
            if (!takingSeries && save()) { dialog.dismiss(); reconnectPreview() }
        }
        start.setOnClickListener {
            if (takingSeries) { stopSeries(); dialog.dismiss() }
            else {
                val old = Triple(prefs.getInt("width", 640),
                    prefs.getInt("height", 480), prefs.getString("format", "AUTO"))
                if (!save()) return@setOnClickListener
                val now = Triple(prefs.getInt("width", 640),
                    prefs.getInt("height", 480), prefs.getString("format", "AUTO"))
                if (old != now) {
                    dialog.dismiss()
                    reconnectPreview()
                    Toast.makeText(this, "Выбран новый режим. После появления видео повторите запуск.",
                        Toast.LENGTH_LONG).show()
                } else if (folderUri == null || !cameraIsOpen ||
                    cameraFragment?.isReady() != true) {
                    Toast.makeText(this, "Выберите папку и подключите USB-камеру",
                        Toast.LENGTH_LONG).show()
                } else { dialog.dismiss(); startSeries() }
            }
        }
        dialog.show()
    }
    private fun startSeries() {
        if (takingSeries || !cameraIsOpen) return
        takingSeries = true
        photoCount = 0
        ui.removeCallbacks(tick)
        ui.post(tick)
        status("Съёмка каждые " + intervalSeconds + " с. Экран не гаснет")
    }
    private fun stopSeries() {
        takingSeries = false
        ui.removeCallbacks(tick)
        status("Остановлено. Сохранено фото: " + photoCount)
    }
    private fun capture() {
        val tree = folderUri
        val fragment = cameraFragment
        if (tree == null || fragment == null) { writing.set(false); return }
        val fileName = "OTG_" + dateFmt.format(Date()) + ".jpg"
        val temp = File(cacheDir, fileName)
        fragment.takeShot(temp.absolutePath, object : ICaptureCallBack {
            override fun onBegin() = Unit
            override fun onError(error: String?) {
                temp.delete()
                writing.set(false)
                runOnUiThread { status("Ошибка снимка: " + (error ?: "неизвестная")) }
            }
            override fun onComplete(path: String?) {
                val actual = path?.takeIf { it.isNotBlank() } ?: temp.absolutePath
                writer.execute {
                    try {
                        PhotoWriter.moveToChosenFolder(applicationContext, tree, actual, fileName)
                        runOnUiThread {
                            photoCount++
                            if (takingSeries) status("Сохранено " + photoCount + " фото")
                        }
                    } catch (ex: Exception) {
                        runOnUiThread { status("Ошибка записи: " + ex.localizedMessage) }
                    } finally { writing.set(false) }
                }
            }
        })
    }
    private fun floatingWindow() {
        if (!cameraIsOpen ||
            !packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            Toast.makeText(this, "PiP недоступен или камера не открыта", Toast.LENGTH_LONG).show()
            return
        }
        try {
            enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(
                Rational(prefs.getInt("width", 640), prefs.getInt("height", 480))).build())
        } catch (ex: Exception) {
            Toast.makeText(this, "Ошибка PiP: " + ex.localizedMessage, Toast.LENGTH_LONG).show()
        }
    }
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (cameraIsOpen && !isInPictureInPictureMode) floatingWindow()
    }
    override fun onPictureInPictureModeChanged(inPip: Boolean, cfg: Configuration) {
        super.onPictureInPictureModeChanged(inPip, cfg)
        settingsButton.visibility = if (inPip) View.GONE else View.VISIBLE
        pipButton.visibility = if (inPip) View.GONE else View.VISIBLE
        statusText.visibility = if (inPip) View.GONE else View.VISIBLE
        folderButton.visibility = if (inPip || folderUri != null) View.GONE else View.VISIBLE
    }
    private fun status(s: String) { statusText.text = s }
    override fun onDestroy() {
        takingSeries = false
        ui.removeCallbacks(tick)
        writer.shutdown()
        super.onDestroy()
    }
    private fun dp(n: Int) = (resources.displayMetrics.density * n + 0.5f).toInt()
}
