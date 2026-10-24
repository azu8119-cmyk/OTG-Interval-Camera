package com.example.otginterval

import android.Manifest
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import java.util.Calendar
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
    private lateinit var seriesButton: Button
    private var cameraFragment: UsbPreviewFragment? = null
    private var folderUri: Uri? = null
    private var intervalSeconds = 5
    private var takingSeries = false
    private var waitingForStart = false
    private var scheduledStartMillis = 0L
    private var scheduledEndMillis = 0L
    private var scheduleEnabled = false
    private var startMinutes = 8 * 60
    private var endMinutes = 18 * 60
    private var lastStartButtonTap = 0L
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
        root.addView(statusText, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            setMargins(0, 0, 0, dp(64))
        })
        settingsButton = Button(this).apply {
            text = "⚙"
            textSize = 24f
            setOnClickListener { settings() }
        }
        root.addView(settingsButton, FrameLayout.LayoutParams(dp(60), dp(56),
            Gravity.TOP or Gravity.END).apply { setMargins(0, dp(42), dp(12), 0) })
        seriesButton = Button(this).apply {
            textSize = 16f
            contentDescription = "Двойное нажатие: начать или остановить съёмку"
            setOnClickListener {
                val now = SystemClock.elapsedRealtime()
                if (now - lastStartButtonTap in 1..500) {
                    lastStartButtonTap = 0L
                    toggleSeries()
                } else {
                    lastStartButtonTap = now
                }
            }
        }
        root.addView(seriesButton, FrameLayout.LayoutParams(dp(130), dp(56),
            Gravity.TOP or Gravity.END).apply { setMargins(0, dp(112), dp(12), 0) })
        updateSeriesButton()
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
            val size = cameraFragment?.activeSize()
            status(if (opened) "USB-камера открыта. Поток: " +
                (size?.let { "${it.first}×${it.second}" } ?: "неизвестно") + ". Нажмите ⚙" else
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
            640 to 480,
            1280 to 720,
            1920 to 1080,
            2560 to 1440,
            3840 to 3104,
            3840 to 2160,
            4624 to 3468,
            4168 to 3126
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
                        val bounds = android.graphics.BitmapFactory.Options().apply {
                            inJustDecodeBounds = true
                        }
                        android.graphics.BitmapFactory.decodeFile(actual, bounds)
                        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                            throw java.io.IOException("JPEG не распознан")
                        }
                        PhotoWriter.moveToChosenFolder(applicationContext, tree, actual, fileName)
                        runOnUiThread {
                            photoCount++
                            val size = bounds.outWidth.toString() + "×" + bounds.outHeight
                            val expected = cameraFragment?.activeSize()
                            if (expected != null && (bounds.outWidth != expected.first ||
                                    bounds.outHeight != expected.second)) {
                                status("Сохранён JPEG " + size + ", поток " +
                                    expected.first + "×" + expected.second +
                                    ". Проверьте разрешение камеры.")
                            } else {
                                status("Сохранено " + photoCount + " фото · JPEG " + size)
                            }
                        }
                    } catch (ex: Exception) {
                        runOnUiThread { status("Ошибка записи: " + ex.localizedMessage) }
                    } finally { writing.set(false) }
                }
            }
        })
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
