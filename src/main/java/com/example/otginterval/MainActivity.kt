package com.example.otginterval

import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentActivity
import com.jiangdg.ausbc.callback.ICaptureCallBack
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Foreground, full-screen UVC timelapse app. Real kiosk restrictions require Device Owner.
 * The USB preview uses CameraFragment and is not guaranteed to survive backgrounding.
 */
class MainActivity : FragmentActivity() {
    private val prefs by lazy { getSharedPreferences("camera_settings", MODE_PRIVATE) }
    private val uiHandler = Handler(Looper.getMainLooper())
    private val writer = Executors.newSingleThreadExecutor()
    private val writing = AtomicBoolean(false)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss_SSS", Locale.US)

    private lateinit var root: FrameLayout
    private lateinit var cameraHolder: FrameLayout
    private lateinit var statusText: TextView
    private lateinit var settingsButton: Button
    private lateinit var chooseFolderButton: Button
    private lateinit var lockLayer: FrameLayout
    private lateinit var lockText: TextView
    private lateinit var lockCounter: TextView
    private var cameraFragment: UsbPreviewFragment? = null

    private var folderUri: Uri? = null
    private var intervalSeconds = 5
    private var takingSeries = false
    private var touchLocked = false
    private var cameraIsOpen = false
    private var pictures = 0
    private var pinRequested = false
    private var kioskEntered = false
    private var downX = 0f
    private var downY = 0f
    private var startedInUnlockBand = false

    private val chooseFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) {
            if (folderUri == null) status("Без выбранной папки съёмка недоступна")
            return@registerForActivityResult
        }
        try {
            contentResolver.takePersistableUriPermission(uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            prefs.edit().putString("folder", uri.toString()).apply()
            folderUri = uri
            status("Папка выбрана. Подключите USB-камеру.")
            showPreview()
        } catch (e: SecurityException) {
            status("Нет постоянного доступа к папке: ${e.localizedMessage}")
        }
    }

    private val cameraTick = object : Runnable {
        override fun run() {
            if (!takingSeries) return
            if (cameraIsOpen && writing.compareAndSet(false, true)) {
                captureIntoSelectedFolder()
            }
            if (takingSeries) uiHandler.postDelayed(this, intervalSeconds * 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        hideSystemBars()
        intervalSeconds = prefs.getInt("interval", 5).coerceIn(1, 300)
        pinRequested = prefs.getBoolean("pin", false)
        folderUri = prefs.getString("folder", null)?.let(Uri::parse)
        buildUi()
        if (folderUri != null) {
            showPreview()
        } else {
            root.post { chooseFolder.launch(null) }
        }
    }

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        cameraHolder = FrameLayout(this).apply { id = View.generateViewId() }
        root.addView(cameraHolder, FrameLayout.LayoutParams(-1, -1))

        chooseFolderButton = Button(this).apply {
            text = "Выбрать папку для снимков"
            setOnClickListener { if (!takingSeries) chooseFolder.launch(null) }
        }
        root.addView(chooseFolderButton, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        statusText = TextView(this).apply {
            text = "Ожидание выбора папки"
            textSize = 13f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(165, 0, 0, 0))
            setPadding(dp(14), dp(10), dp(14), dp(10))
            maxLines = 3
        }
        root.addView(statusText, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        settingsButton = Button(this).apply {
            text = "⚙"
            textSize = 27f
            contentDescription = "Настройки камеры и интервальной съёмки"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(180, 28, 34, 39))
            setOnClickListener { showSettings() }
        }
        root.addView(settingsButton, FrameLayout.LayoutParams(dp(58), dp(58), Gravity.TOP or Gravity.END).apply {
            setMargins(0, dp(16), dp(16), 0)
        })

        lockLayer = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(28, 0, 0, 0))
            visibility = View.GONE
            isClickable = true
            setOnTouchListener { _, event -> processUnlockGesture(event) }
        }
        lockCounter = TextView(this).apply {
            text = "● СЪЁМКА"
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setBackgroundColor(Color.argb(170, 0, 0, 0))
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        lockLayer.addView(lockCounter, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
                setMargins(dp(15), dp(22), 0, 0)
            })
        lockText = TextView(this).apply {
            text = "Чтобы разблокировать управление, проведите снизу СЛЕВА → НАПРАВО"
            gravity = Gravity.CENTER
            textSize = 15f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(220, 15, 20, 23))
            setPadding(dp(15), dp(12), dp(15), dp(12))
        }
        lockLayer.addView(lockText, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(105), Gravity.BOTTOM))
        root.addView(lockLayer, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        chooseFolderButton.visibility = if (folderUri == null) View.VISIBLE else View.GONE
    }

    private fun processUnlockGesture(event: MotionEvent): Boolean {
        val bandTop = root.height - dp(155)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                startedInUnlockBand = event.y >= bandTop && event.x <= root.width * 0.6f
                return true
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                if (startedInUnlockBand && event.x - downX >= dp(150) &&
                    abs(event.y - downY) <= dp(115)) {
                    setTouchLock(false)
                    Toast.makeText(this, "Управление разблокировано. Съёмка продолжается", Toast.LENGTH_SHORT).show()
                    startedInUnlockBand = false
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                startedInUnlockBand = false
                return true
            }
        }
        return true
    }

    private fun showPreview() {
        chooseFolderButton.visibility = View.GONE
        if (cameraFragment == null) {
            cameraFragment = UsbPreviewFragment()
            supportFragmentManager.beginTransaction()
                .replace(cameraHolder.id, cameraFragment!!, "camera")
                .commit()
        }
    }

    fun onUsbState(connected: Boolean, detail: String?) {
        runOnUiThread {
            cameraIsOpen = connected
            if (!connected && takingSeries) stopSeries()
            status(if (connected) "USB-камера подключена. Настройки — ⚙" else
                (detail ?: "Подключите USB-камеру через OTG"))
        }
    }

    private fun showSettings() {
        if (touchLocked) return
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(8))
        }
        fun heading(text: String): TextView = TextView(this).apply {
            this.text = text
            textSize = 16f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(15), 0, dp(7))
        }
        layout.addView(heading("Разрешение USB-камеры"))
        val sizes = cameraFragment?.availableSizes().orEmpty()
        val sizesText = sizes.map { "${it.first} × ${it.second}" }.ifEmpty {
            listOf("Подключите USB-камеру")
        }
        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, sizesText)
        val currentW = prefs.getInt("width", 1280)
        val currentH = prefs.getInt("height", 720)
        sizes.indexOf(currentW to currentH).takeIf { it >= 0 }?.let(spinner::setSelection)
        layout.addView(spinner)

        layout.addView(heading("Интервал между снимками (1–300 секунд)"))
        val field = EditText(this).apply {
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(intervalSeconds.toString())
            hint = "1–300"
            selectAll()
        }
        layout.addView(field)

        layout.addView(heading("Папка: ${folderUri?.lastPathSegment ?: "не выбрана"}"))
        val pickButton = Button(this).apply { text = "Изменить папку" }
        layout.addView(pickButton)

        val deviceOwner = isDeviceOwner()
        val pinCheck = CheckBox(this).apply {
            text = if (deviceOwner) "Строгий киоск включится автоматически (Device Owner)" else
                "Попробовать закрепление экрана при запуске (не строгий киоск)"
            isEnabled = !deviceOwner
            isChecked = deviceOwner || pinRequested
        }
        layout.addView(pinCheck)
        layout.addView(TextView(this).apply {
            text = if (deviceOwner)
                "В строгом режиме Home/Recents/шторка отключаются системной политикой." else
                "Без Device Owner системную шторку и кнопки нельзя полностью блокировать. " +
                    "Полный киоск настраивается отдельно."
            textSize = 12f
            setPadding(0, dp(8), 0, dp(8))
        })
        val manageButton = Button(this).apply { text = if (takingSeries) "Остановить съёмку" else "Начать интервальную съёмку" }
        layout.addView(manageButton)
        val kioskExit = Button(this).apply {
            text = "Выйти из киоск-режима"
            visibility = if (kioskEntered) View.VISIBLE else View.GONE
        }
        layout.addView(kioskExit)
        val scroll = ScrollView(this).apply { addView(layout) }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Настройки интервальной съёмки")
            .setView(scroll)
            .setNegativeButton("Закрыть", null)
            .create()
        pickButton.setOnClickListener {
            if (takingSeries) {
                Toast.makeText(this, "Сначала остановите съёмку", Toast.LENGTH_LONG).show()
            } else {
                dialog.dismiss()
                chooseFolder.launch(null)
            }
        }
        manageButton.setOnClickListener {
            if (takingSeries) {
                stopSeries()
                dialog.dismiss()
                return@setOnClickListener
            }
            val value = field.text.toString().toIntOrNull()
            if (value == null || value !in 1..300) {
                field.error = "Введите число от 1 до 300"
                return@setOnClickListener
            }
            if (folderUri == null || !cameraIsOpen || cameraFragment?.isReady() != true) {
                Toast.makeText(this, "Выберите папку и подключите USB-камеру", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            intervalSeconds = value
            pinRequested = pinCheck.isChecked
            prefs.edit().putInt("interval", value).putBoolean("pin", pinRequested).apply()
            if (sizes.isNotEmpty()) {
                val (w, h) = sizes[spinner.selectedItemPosition]
                prefs.edit().putInt("width", w).putInt("height", h).apply()
                // Allow the UVC stream to reconnect at the selected resolution before shooting.
                if (w != currentW || h != currentH) {
                    cameraFragment?.changeSize(w, h)
                    Toast.makeText(this, "Разрешение меняется. Повторите запуск после восстановления видео", Toast.LENGTH_LONG).show()
                    dialog.dismiss()
                    return@setOnClickListener
                }
            }
            dialog.dismiss()
            startSeries()
        }
        kioskExit.setOnClickListener {
            stopSeries()
            exitKiosk()
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun startSeries() {
        if (takingSeries) return
        takingSeries = true
        pictures = 0
        enterKioskIfRequested()
        setTouchLock(true)
        status("Съёмка: каждые $intervalSeconds с. Экран не гаснет")
        uiHandler.removeCallbacks(cameraTick)
        uiHandler.post(cameraTick) // First photo is taken immediately.
    }

    private fun stopSeries() {
        takingSeries = false
        uiHandler.removeCallbacks(cameraTick)
        setTouchLock(false)
        status("Остановлено. Сохранено: $pictures")
        exitKiosk()
    }

    private fun setTouchLock(locked: Boolean) {
        touchLocked = locked
        lockLayer.visibility = if (locked) View.VISIBLE else View.GONE
        lockCounter.text = "● СЪЁМКА  •  $pictures кадров"
        hideSystemBars()
    }

    private fun captureIntoSelectedFolder() {
        val uri = folderUri
        val camera = cameraFragment
        if (uri == null || camera == null) {
            writing.set(false)
            return
        }
        val filename = "OTG_${dateFormat.format(Date())}.jpg"
        val cacheFile = File(cacheDir, filename)
        camera.takeShot(cacheFile.absolutePath, object : ICaptureCallBack {
            override fun onBegin() = Unit
            override fun onError(error: String?) {
                cacheFile.delete()
                writing.set(false)
                runOnUiThread { status("Ошибка снимка: ${error ?: "неизвестна"}") }
            }
            override fun onComplete(path: String?) {
                val realPath = path?.takeIf { it.isNotBlank() } ?: cacheFile.absolutePath
                writer.execute {
                    try {
                        PhotoWriter.moveToChosenFolder(applicationContext, uri, realPath, filename)
                        runOnUiThread {
                            pictures++
                            lockCounter.text = "● СЪЁМКА  •  $pictures кадров"
                            if (takingSeries) status("Съёмка • каждые $intervalSeconds с • сохранено $pictures")
                        }
                    } catch (e: Exception) {
                        runOnUiThread { status("Ошибка записи: ${e.localizedMessage}") }
                    } finally {
                        writing.set(false)
                    }
                }
            }
        })
    }

    private fun enterKioskIfRequested() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        try {
            if (dpm.isDeviceOwnerApp(packageName)) {
                val admin = ComponentName(this, KioskAdminReceiver::class.java)
                dpm.setLockTaskPackages(admin, arrayOf(packageName))
                dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
                startLockTask()
                kioskEntered = true
            } else if (pinRequested) {
                startLockTask()  // The OS may require user consent; screen pinning != Device Owner kiosk.
                kioskEntered = true
            }
        } catch (e: Exception) {
            kioskEntered = false
            Toast.makeText(this, "Киоск недоступен: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    private fun exitKiosk() {
        if (!kioskEntered) return
        try { stopLockTask() } catch (_: Exception) { }
        kioskEntered = false
        hideSystemBars()
    }

    private fun isDeviceOwner(): Boolean =
        (getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager)
            .isDeviceOwnerApp(packageName)

    private fun status(text: String) { statusText.text = text }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (takingSeries) {
            Toast.makeText(this, "Проведите внизу вправо и остановите съёмку в настройках", Toast.LENGTH_SHORT).show()
        } else {
            exitKiosk()
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        takingSeries = false
        uiHandler.removeCallbacks(cameraTick)
        writer.shutdown()
        exitKiosk()
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (resources.displayMetrics.density * value + 0.5f).toInt()
}
