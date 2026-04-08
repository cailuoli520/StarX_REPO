package org.xiyu.starx

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedService.OnScopeEventListener
import org.json.JSONObject
import org.xiyu.starx.databinding.ActivityMainBinding
import org.xiyu.starx.license.LicenseManager
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@SuppressLint("SetTextI18n", "UseSwitchCompatOrMaterialCode")
class MainActivity : Activity(), App.ServiceStateListener {
    private var mService: XposedService? = null
    private lateinit var binding: ActivityMainBinding

    companion object {
        private const val PREFS_CONFIG = "config"
        private const val PREFS_SIGN = "sign_config"
        private const val RC_MAP_PICKER = 2001
        private const val TG_GROUP_URL = "https://t.me/+BUfEUGzViTg2YWU1"

        private val VERSION_CODE get() = BuildConfig.VERSION_CODE
        private val VERSION_NAME get() = BuildConfig.VERSION_NAME

        // 功能开关 key
        private const val KEY_DETECTION = "hook_detection_enabled"
        private const val KEY_ADS = "hook_ads_enabled"
        private const val KEY_SIGNIN = "hook_signin_enabled"
        private const val KEY_ANTICHEAT = "hook_anticheat_enabled"
        private const val KEY_WINDOW = "hook_window_enabled"
        private const val KEY_VIDEO = "hook_video_enabled"
        private const val KEY_VIDEO_TIME = "hook_video_time_enabled"
        private const val KEY_EXAM = "hook_exam_enabled"
    }

    private val mScopeCallback = object : OnScopeEventListener {
        override fun onScopeRequestApproved(approved: List<String>) {
            runOnUiThread {
                Toast.makeText(this@MainActivity, "作用域已批准", Toast.LENGTH_SHORT).show()
                updateScopeStatus()
            }
        }

        override fun onScopeRequestFailed(message: String) {
            runOnUiThread {
                Toast.makeText(this@MainActivity, "作用域请求失败: $message", Toast.LENGTH_SHORT).show()
                updateScopeStatus()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 沉浸式状态栏 — 必须在 setContentView 之后（DecorView 才存在）
        window.apply {
            addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            statusBarColor = android.graphics.Color.TRANSPARENT
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                @Suppress("DEPRECATION")
                setDecorFitsSystemWindows(false)
                insetsController?.setSystemBarsAppearance(
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                )
            } else {
                @Suppress("DEPRECATION")
                decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR)
            }
        }

        setupSwitchListeners()
        setupButtons()
    }

    override fun onStart() {
        super.onStart()
        App.addServiceStateListener(this, true)
    }

    override fun onStop() {
        App.removeServiceStateListener(this)
        super.onStop()
    }

    override fun onServiceStateChanged(service: XposedService?) {
        mService = service
        runOnUiThread {
            if (service == null) {
                binding.statusBadge.text = "未连接"
                binding.statusBadge.setBackgroundResource(R.drawable.status_inactive_bg)
                binding.statusBadge.setTextColor(0xFFFF3B30.toInt())
                binding.frameworkInfo.text = "等待框架连接..."
                binding.frameworkDetail.visibility = View.GONE
            } else {
                binding.statusBadge.text = "已激活"
                binding.statusBadge.setBackgroundResource(R.drawable.status_active_bg)
                binding.statusBadge.setTextColor(0xFF34C759.toInt())
                binding.frameworkInfo.text = "${service.frameworkName} 已连接"

                binding.frameworkDetail.visibility = View.VISIBLE
                binding.frameworkDetail.text = "${service.frameworkName} v${service.frameworkVersion} | API ${service.apiVersion}"

                updateScopeStatus()
                loadSwitchStates(service)
                loadLocationConfig(service)
                loadAiConfig(service)
                updateLicenseStatus(service)
                checkForUpdates()
                checkAnnouncement()
            }
        }
    }

    private fun setupSwitchListeners() {
        val switchMap = mapOf(
            binding.switchDetection to KEY_DETECTION,
            binding.switchAds to KEY_ADS,
            binding.switchSignin to KEY_SIGNIN,
            binding.switchAnticheat to KEY_ANTICHEAT,
            binding.switchWindow to KEY_WINDOW,
            binding.switchVideo to KEY_VIDEO,
            binding.switchVideoTime to KEY_VIDEO_TIME,
            binding.switchExam to KEY_EXAM
        )

        for ((switch, key) in switchMap) {
            switch.setOnCheckedChangeListener { _, isChecked ->
                val service = mService ?: return@setOnCheckedChangeListener
                try {
                    val prefs = service.getRemotePreferences(PREFS_CONFIG)
                    prefs.edit().putBoolean(key, isChecked).apply()
                } catch (e: Throwable) {
                    Toast.makeText(this, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun setupButtons() {
        binding.btnSaveLocation.setOnClickListener {
            saveLocationConfig()
        }

        binding.btnPickMap.setOnClickListener {
            val intent = Intent(this, MapPickerActivity::class.java)
            // 把当前已配置的坐标传过去, 让地图初始居中
            val latStr = binding.editLat.text.toString().trim()
            val lngStr = binding.editLng.text.toString().trim()
            val lat = latStr.toDoubleOrNull() ?: 0.0
            val lng = lngStr.toDoubleOrNull() ?: 0.0
            if (lat != 0.0 && lng != 0.0) {
                intent.putExtra(MapPickerActivity.EXTRA_INIT_LAT, lat)
                intent.putExtra(MapPickerActivity.EXTRA_INIT_LNG, lng)
            }
            @Suppress("DEPRECATION")
            startActivityForResult(intent, RC_MAP_PICKER)
        }

        binding.btnSaveAi.setOnClickListener {
            saveAiConfig()
        }

        binding.btnRequestScope.setOnClickListener {
            val service = mService
            if (service == null) {
                Toast.makeText(this, "框架未连接", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            service.requestScope(listOf("com.chaoxing.mobile"), mScopeCallback)
            Toast.makeText(this, "正在请求作用域...", Toast.LENGTH_SHORT).show()
        }

        binding.btnJoinTg.setOnClickListener {
            openTelegramGroup()
        }

        binding.btnActivate.setOnClickListener {
            val code = binding.editActivationCode.text.toString().trim()
            if (code.isEmpty()) {
                Toast.makeText(this, "请输入激活码", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            binding.btnActivate.isEnabled = false
            binding.btnActivate.text = "激活中..."
            Thread {
                val result = LicenseManager.activate(code, this@MainActivity)
                runOnUiThread {
                    binding.btnActivate.isEnabled = true
                    binding.btnActivate.text = "激活"
                    if (result.success) {
                        val service = mService
                        if (service != null) {
                            val prefs = service.getRemotePreferences(PREFS_CONFIG)
                            prefs.edit()
                                .putString("license_token", result.token)
                                .putLong("license_expires", result.expiresAt)
                                .putString("device_id", result.deviceId ?: "")
                                .apply()
                            updateLicenseStatus(service)
                        }
                        Toast.makeText(this, "激活成功！重启学习通生效", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                    }
                }
            }.start()
        }
    }

    private fun loadSwitchStates(service: XposedService) {
        try {
            val prefs = service.getRemotePreferences(PREFS_CONFIG)
            binding.switchDetection.isChecked = prefs.getBoolean(KEY_DETECTION, true)
            binding.switchAds.isChecked = prefs.getBoolean(KEY_ADS, true)
            binding.switchSignin.isChecked = prefs.getBoolean(KEY_SIGNIN, true)
            binding.switchAnticheat.isChecked = prefs.getBoolean(KEY_ANTICHEAT, true)
            binding.switchWindow.isChecked = prefs.getBoolean(KEY_WINDOW, true)
            binding.switchVideo.isChecked = prefs.getBoolean(KEY_VIDEO, true)
            binding.switchVideoTime.isChecked = prefs.getBoolean(KEY_VIDEO_TIME, false)
            binding.switchExam.isChecked = prefs.getBoolean(KEY_EXAM, true)
        } catch (_: Throwable) {
        }
    }

    private fun loadLocationConfig(service: XposedService) {
        try {
            val prefs = service.getRemotePreferences(PREFS_SIGN)
            val lat = Double.fromBits(prefs.getLong("fake_lat", 0L))
            val lng = Double.fromBits(prefs.getLong("fake_lng", 0L))
            val addr = prefs.getString("fake_addr", "") ?: ""
            if (lat != 0.0) binding.editLat.setText(lat.toString())
            if (lng != 0.0) binding.editLng.setText(lng.toString())
            if (addr.isNotEmpty()) binding.editAddr.setText(addr)
        } catch (_: Throwable) {
        }
    }

    private fun saveLocationConfig() {
        val service = mService
        if (service == null) {
            Toast.makeText(this, "框架未连接，无法保存", Toast.LENGTH_SHORT).show()
            return
        }

        val latStr = binding.editLat.text.toString().trim()
        val lngStr = binding.editLng.text.toString().trim()
        val addr = binding.editAddr.text.toString().trim()

        if (latStr.isEmpty() || lngStr.isEmpty()) {
            Toast.makeText(this, "请输入纬度和经度", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val lat = latStr.toDouble()
            val lng = lngStr.toDouble()

            if (lat < -90 || lat > 90 || lng < -180 || lng > 180) {
                Toast.makeText(this, "坐标范围无效 (纬度:-90~90, 经度:-180~180)", Toast.LENGTH_SHORT).show()
                return
            }

            val prefs = service.getRemotePreferences(PREFS_SIGN)
            prefs.edit()
                .putLong("fake_lat", lat.toBits())
                .putLong("fake_lng", lng.toBits())
                .putString("fake_addr", addr)
                .apply()

            Toast.makeText(this, "定位已保存: $lat, $lng", Toast.LENGTH_SHORT).show()
        } catch (e: NumberFormatException) {
            Toast.makeText(this, "坐标格式错误，请输入有效数字", Toast.LENGTH_SHORT).show()
        } catch (e: Throwable) {
            Toast.makeText(this, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadAiConfig(service: XposedService) {
        try {
            val prefs = service.getRemotePreferences(PREFS_CONFIG)
            binding.editOpenaiKey.setText(prefs.getString("ai_openai_key", "") ?: "")
            binding.editOpenaiUrl.setText(prefs.getString("ai_openai_url", "") ?: "")
            binding.editOpenaiModel.setText(prefs.getString("ai_openai_model", "") ?: "")
            binding.editGeminiKey.setText(prefs.getString("ai_gemini_key", "") ?: "")
            binding.editGeminiModel.setText(prefs.getString("ai_gemini_model", "") ?: "")
        } catch (_: Throwable) {
        }
    }

    private fun saveAiConfig() {
        val service = mService
        if (service == null) {
            Toast.makeText(this, "框架未连接，无法保存", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val prefs = service.getRemotePreferences(PREFS_CONFIG)
            prefs.edit()
                .putString("ai_openai_key", binding.editOpenaiKey.text.toString().trim())
                .putString("ai_openai_url", binding.editOpenaiUrl.text.toString().trim())
                .putString("ai_openai_model", binding.editOpenaiModel.text.toString().trim())
                .putString("ai_gemini_key", binding.editGeminiKey.text.toString().trim())
                .putString("ai_gemini_model", binding.editGeminiModel.text.toString().trim())
                .apply()
            Toast.makeText(this, "AI 配置已保存，重启学习通生效", Toast.LENGTH_SHORT).show()
        } catch (e: Throwable) {
            Toast.makeText(this, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openTelegramGroup() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(TG_GROUP_URL)))
        } catch (_: Throwable) {
            Toast.makeText(this, "无法打开 TG 链接", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateLicenseStatus(service: XposedService) {
        try {
            val prefs = service.getRemotePreferences(PREFS_CONFIG)
            val token = prefs.getString("license_token", "") ?: ""
            val expires = prefs.getLong("license_expires", 0L)
            if (token.isNotEmpty() && expires > 0) {
                val now = System.currentTimeMillis()
                if (now < expires) {
                    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                    binding.licenseStatus.text = "已激活 · 到期: ${sdf.format(Date(expires))}"
                    binding.licenseStatus.setTextColor(0xFF34C759.toInt())
                } else {
                    binding.licenseStatus.text = "许可已过期"
                    binding.licenseStatus.setTextColor(0xFFFF3B30.toInt())
                }
            } else {
                binding.licenseStatus.text = "未激活"
                binding.licenseStatus.setTextColor(0xFFFF3B30.toInt())
            }
        } catch (_: Throwable) {
            binding.licenseStatus.text = "未激活"
            binding.licenseStatus.setTextColor(0xFFFF3B30.toInt())
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == RC_MAP_PICKER && resultCode == RESULT_OK && data != null) {
            val lat = data.getDoubleExtra(MapPickerActivity.RESULT_LAT, 0.0)
            val lng = data.getDoubleExtra(MapPickerActivity.RESULT_LNG, 0.0)
            val addr = data.getStringExtra(MapPickerActivity.RESULT_ADDR) ?: ""
            binding.editLat.setText(lat.toString())
            binding.editLng.setText(lng.toString())
            if (addr.isNotEmpty()) binding.editAddr.setText(addr)
            // 自动保存
            saveLocationConfig()
        }
    }

    private fun updateScopeStatus() {
        val service = mService ?: return
        val scope = service.scope
        binding.scopeStatus.text = if (scope.isNullOrEmpty()) {
            "作用域: 未配置"
        } else {
            "作用域: $scope"
        }
    }

    private fun checkForUpdates() {
        Thread {
            try {
                val url = URL("https://api.github.com/repos/Mai-xiyu/StarX/releases/latest")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                val code = conn.responseCode
                if (code != 200) return@Thread
                val body = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                val json = JSONObject(body)

                val tagName = json.optString("tag_name", "") // e.g. "v1.1.0"
                val releaseName = json.optString("name", tagName)
                val releaseBody = json.optString("body", "")
                val htmlUrl = json.optString("html_url", "")

                // 从 assets 中找 APK 下载链接
                var apkUrl = ""
                val assets = json.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val name = asset.optString("name", "")
                        if (name.endsWith(".apk")) {
                            apkUrl = asset.optString("browser_download_url", "")
                            break
                        }
                    }
                }
                val downloadUrl = apkUrl.ifEmpty { htmlUrl }

                // 从 tag 解析版本号比较 (去掉前缀 v)
                val remoteVersion = tagName.removePrefix("v").removePrefix("V")
                if (remoteVersion.isNotEmpty() && isNewerVersion(remoteVersion, VERSION_NAME)) {
                    runOnUiThread {
                        showUpdateDialog(releaseName, releaseBody, downloadUrl)
                    }
                }
            } catch (_: Throwable) {
                // 更新检查失败静默忽略
            }
        }.start()
    }

    private fun isNewerVersion(remote: String, current: String): Boolean {
        val r = remote.split(".").map { it.toIntOrNull() ?: 0 }
        val c = current.split(".").map { it.toIntOrNull() ?: 0 }
        val len = maxOf(r.size, c.size)
        for (i in 0 until len) {
            val rv = r.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (rv > cv) return true
            if (rv < cv) return false
        }
        return false
    }

    private fun showUpdateDialog(version: String, releaseNotes: String, downloadUrl: String) {
        val msg = buildString {
            append("新版本 $version 可用，请更新后继续使用。")
            if (releaseNotes.isNotEmpty()) {
                append("\n\n更新内容:\n")
                append(releaseNotes)
            }
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("发现新版本")
            .setMessage(msg)
            .setPositiveButton("前往更新") { _, _ ->
                if (downloadUrl.isNotEmpty()) {
                    try {
                        startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(downloadUrl)))
                    } catch (_: Throwable) {
                        Toast.makeText(this, "无法打开下载链接", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setCancelable(false)
            .show()
    }

    private fun checkAnnouncement() {
        Thread {
            try {
                val url = URL("${LicenseManager.SERVER_URL}/api/meta")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                val code = conn.responseCode
                if (code != 200) return@Thread
                val body = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                val json = JSONObject(body)
                val announcement = json.optString("announcement", "")
                if (announcement.isNotEmpty()) {
                    runOnUiThread { showAnnouncement(announcement) }
                }
            } catch (_: Throwable) {
            }
        }.start()
    }

    private fun showAnnouncement(text: String) {
        val prefs = getSharedPreferences("starx_meta", MODE_PRIVATE)
        val lastHash = prefs.getString("last_announcement_hash", "") ?: ""
        val hash = text.hashCode().toString()
        if (hash == lastHash) return

        android.app.AlertDialog.Builder(this)
            .setTitle("公告")
            .setMessage(text)
            .setPositiveButton("知道了") { d, _ ->
                prefs.edit().putString("last_announcement_hash", hash).apply()
                d.dismiss()
            }
            .setCancelable(false)
            .show()
    }
}
