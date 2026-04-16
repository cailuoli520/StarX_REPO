package org.xiyu.starx

import android.annotation.SuppressLint
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedService.OnScopeEventListener
import org.json.JSONArray
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
    private enum class MainTab {
        FUNCTIONS,
        BANKS,
        HOME,
        AI,
        PROFILE
    }

    private data class TikuTemplateParamConfig(
        val key: String,
        val value: String,
        val placement: String = TEMPLATE_PARAM_PLACEMENT_QUERY
    )

    private data class TikuTemplateParamViews(
        val rootView: View,
        val queryButton: Button,
        val headerButton: Button,
        val bodyButton: Button,
        val keyEdit: EditText,
        val valueEdit: EditText,
        val deleteButton: Button
    )

    private data class TikuSourceConfig(
        val name: String,
        val baseUrl: String,
        val token: String,
        val enabled: Boolean,
        val mode: String = SOURCE_MODE_AUTO,
        val answerPath: String = "",
        val templateParams: List<TikuTemplateParamConfig> = emptyList()
    )

    private data class TikuSourceViews(
        val rootView: View,
        val titleView: TextView,
        val modeHintView: TextView,
        val enabledSwitch: Switch,
        val nameEdit: EditText,
        val autoModeButton: Button,
        val lemtkModeButton: Button,
        val adapterModeButton: Button,
        val zxseekModeButton: Button,
        val customGetModeButton: Button,
        val customPostModeButton: Button,
        val urlEdit: EditText,
        val tokenEdit: EditText,
        val customSection: ViewGroup,
        val answerPathEdit: EditText,
        val templateParamsContainer: ViewGroup,
        val addTemplateParamButton: Button,
        val quickFillButton: Button,
        val deleteButton: Button,
        val templateParamEditors: MutableList<TikuTemplateParamViews> = mutableListOf()
    )

    companion object {
        private const val PREFS_CONFIG = "config"
        private const val PREFS_SIGN = "sign_config"
        private const val RC_MAP_PICKER = 2001
        private const val TG_GROUP_URL = "https://t.me/+BUfEUGzViTg2YWU1"
        private const val PUBLIC_FEEDBACK_URL = "https://github.com/Mai-xiyu/StarX/issues/new/choose"
        private const val KEY_TIKU_SOURCES_JSON = "tiku_sources_json"
        private const val KEY_TIKU_SOURCE_SCHEMA_VERSION = "tiku_source_schema_version"
        private const val KEY_CACHE_ENABLED = "cache_enabled"
        private const val KEY_AUTO_TARGET_ENABLED = "auto_target_enabled"
        private const val KEY_AUTO_TARGET_LAT = "auto_target_lat"
        private const val KEY_AUTO_TARGET_LNG = "auto_target_lng"
        private const val KEY_AUTO_TARGET_ADDR = "auto_target_addr"
        private const val KEY_SIGN_CODE = "assist_sign_code"
        private const val KEY_QR_PAYLOAD = "assist_qr_payload"
        private const val KEY_H5_QR_PAYLOAD = "assist_qr_h5_payload"
        private const val KEY_NATIVE_QR_PAYLOAD = "assist_qr_native_payload"
        private const val KEY_PHOTO_URI = "assist_photo_uri"
        private const val KEY_SIGN_LAST_MODE = "assist_last_mode"
        private const val KEY_SIGN_LAST_ACTION = "assist_last_action"
        private const val KEY_SIGN_LAST_URL = "assist_last_url"
        private const val KEY_SIGN_LAST_AT = "assist_last_at"
        private const val ZXSEEK_PRESET_SCHEMA_VERSION = 2
        private const val TIKU_SOURCE_SCHEMA_VERSION = 4
        private const val PRESET_TIKU_SOURCE_COUNT = 3
        private const val LEMTK_RECOMMENDED_URL = "https://api.vanse.top"
        private const val LEMTK_OFFICIAL_URL = "https://api.lemtk.xyz"
        private const val ZXSEEK_DEFAULT_URL = "https://api.wkexam.com/api/"
        private const val SOURCE_MODE_AUTO = "auto"
        private const val SOURCE_MODE_LEMTK = "lemtk"
        private const val SOURCE_MODE_ADAPTER = "adapter"
        private const val SOURCE_MODE_ZXSEEK = "zxseek"
        private const val SOURCE_MODE_CUSTOM_GET = "custom_get"
        private const val SOURCE_MODE_CUSTOM_POST_JSON = "custom_post_json"
        private const val TEMPLATE_PARAM_PLACEMENT_QUERY = "query"
        private const val TEMPLATE_PARAM_PLACEMENT_HEADER = "header"
        private const val TEMPLATE_PARAM_PLACEMENT_BODY = "body"

        private val VERSION_NAME get() = BuildConfig.VERSION_NAME

        private const val KEY_DETECTION = "hook_detection_enabled"
        private const val KEY_ADS = "hook_ads_enabled"
        private const val KEY_SIGNIN = "hook_signin_enabled"
        private const val KEY_ANTICHEAT = "hook_anticheat_enabled"
        private const val KEY_WINDOW = "hook_window_enabled"
        private const val KEY_VIDEO = "hook_video_enabled"
        private const val KEY_VIDEO_TIME = "hook_video_time_enabled"
        private const val KEY_EXAM = "hook_exam_enabled"
        private const val KEY_EXAM_TRIGGER = "hook_exam_trigger"
        private const val EXAM_TRIGGER_VOLUME_DOWN = "volume_down"
        private const val EXAM_TRIGGER_VOLUME_UP = "volume_up"
        private const val EXAM_TRIGGER_VOLUME_UP_DOWN = "volume_up_down"
    }

    private var mService: XposedService? = null
    private lateinit var binding: ActivityMainBinding
    private var switchesLoading = false
    private var locationLoading = false
    private var currentTab: MainTab? = null
    private var latestAnnouncementText = "连接框架后显示最新公告"
    private val pageEnterInterpolator = DecelerateInterpolator(1.6f)
    private val navBounceInterpolator = OvershootInterpolator(0.7f)
    private val ambientAnimators = mutableListOf<ObjectAnimator>()
    private val tikuSourceEditors = mutableListOf<TikuSourceViews>()

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

        applySystemBars()
        setupNavigation()
        setupSwitchListeners()
        setupButtons()
        setStaticUiDefaults()
        startAmbientAnimations()
        showTab(MainTab.HOME)
    }

    private fun setStaticUiDefaults() {
        binding.versionBadge.text = "v$VERSION_NAME"
        binding.homeUpdateValue.text = "当前版本 v$VERSION_NAME"
        binding.homeAnnouncement.text = latestAnnouncementText
        binding.homeBankSummary.text = "默认支持内置公共题库、LemTk 兼容节点、tikuAdapter 标准接口与 zxseek/wkexam 简易接口，也支持自定义 GET / POST JSON 模板。"
        binding.homeBankBadge.text = "题库 3源"
        binding.homeFeatureSummary.text = "已启用 0/8 项功能"
        binding.homeAiBadge.text = "AI 未配置"
        binding.homeLocationBadge.text = "定位 待命"
        binding.homeAiSummary.text = "题库未命中时，可配置 OpenAI 兼容或 Gemini 作为兜底。"
        binding.homeLocationSummary.text = "支持定位、手势、二维码与拍照签到辅助。"
        binding.homeLicenseSummary.text = "许可状态待连接框架后读取。"
        binding.profileExpireInfo.text = "到期信息待获取"
        binding.profileDeviceInfo.text = "设备信息待获取"
        binding.switchAutoSignTarget.isChecked = true
        binding.textAutoLocationStatus.text = "自动抓取已开启，进入定位签到页后会自动更新目标区域。"
        binding.textSignAssistStatus.text = "可分别预填手势口令、H5 rcode、原生 signId/time 载荷与拍照签到图片 URI；页面若暴露签到码，也会自动尝试提取并提交。"
        updateDisconnectedUi()
        val defaults = defaultTikuSources()
        bindTikuSources(defaults)
        updateBankSummary(defaults, true)
        updateAiSummary(false, false)
        updateHomeLocationSummary(true, 0.0, 0.0, 0.0, 0.0, "")
        updateHomeLicenseSummary(false, 0L)
    }

    private fun applySystemBars() {
        val isNight = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

        window.apply {
            addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            statusBarColor = Color.TRANSPARENT
            navigationBarColor = Color.TRANSPARENT
            if (Build.VERSION.SDK_INT >= 30) {
                setDecorFitsSystemWindows(false)
                val mask = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                val appearance = if (isNight) 0 else mask
                insetsController?.setSystemBarsAppearance(appearance, mask)
            } else {
                @Suppress("DEPRECATION")
                var flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                @Suppress("DEPRECATION")
                if (!isNight) {
                    flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                }
                @Suppress("DEPRECATION")
                decorView.systemUiVisibility = flags
            }
        }

        val horizontal = (20 * resources.displayMetrics.density).toInt()
        val contentTopExtra = (8 * resources.displayMetrics.density).toInt()
        val navBottomExtra = (16 * resources.displayMetrics.density).toInt()

        binding.root.setOnApplyWindowInsetsListener { _, insets ->
            val topInset: Int
            val bottomInset: Int
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                topInset = bars.top
                bottomInset = bars.bottom
            } else {
                @Suppress("DEPRECATION")
                topInset = insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                bottomInset = insets.systemWindowInsetBottom
            }

            binding.contentContainer.setPadding(horizontal, topInset + contentTopExtra, horizontal, 0)
            val navLp = binding.bottomNav.layoutParams as ViewGroup.MarginLayoutParams
            navLp.leftMargin = horizontal
            navLp.rightMargin = horizontal
            navLp.bottomMargin = bottomInset + navBottomExtra
            binding.bottomNav.layoutParams = navLp
            insets
        }
        binding.root.requestApplyInsets()
    }

    override fun onStart() {
        super.onStart()
        App.addServiceStateListener(this, true)
    }

    override fun onStop() {
        App.removeServiceStateListener(this)
        super.onStop()
    }

    override fun onDestroy() {
        ambientAnimators.forEach { it.cancel() }
        ambientAnimators.clear()
        super.onDestroy()
    }

    override fun onServiceStateChanged(service: XposedService?) {
        mService = service
        runOnUiThread {
            if (service == null) {
                updateDisconnectedUi()
            } else {
                updateConnectedUi(service)
                updateScopeStatus()
                loadSwitchStates(service)
                loadExamTriggerConfig(service)
                loadLocationConfig(service)
                loadSignAssistConfig(service)
                loadAiConfig(service)
                loadTikuConfig(service)
                updateLicenseStatus(service)
                checkForUpdates()
                checkAnnouncement()
            }
        }
    }

    private fun updateDisconnectedUi() {
        binding.statusBadge.text = "未连接"
        binding.statusBadge.setBackgroundResource(R.drawable.status_inactive_bg)
        binding.statusBadge.setTextColor(getColor(R.color.ios_red))
        binding.frameworkInfo.text = "等待框架连接..."
        binding.frameworkDetail.visibility = View.GONE
        binding.scopeStatus.text = "作用域: 等待框架连接"
        binding.homeLicenseSummary.text = "许可状态待连接框架后读取。"
        binding.textSignAssistStatus.text = "等待框架连接后加载签到辅助配置。"
        applyExamTriggerMode(EXAM_TRIGGER_VOLUME_DOWN)
    }

    private fun updateConnectedUi(service: XposedService) {
        binding.statusBadge.text = "已激活"
        binding.statusBadge.setBackgroundResource(R.drawable.status_active_bg)
        binding.statusBadge.setTextColor(getColor(R.color.ios_green))
        binding.frameworkInfo.text = "${service.frameworkName} 已连接"
        binding.frameworkDetail.visibility = View.VISIBLE
        binding.frameworkDetail.text = "${service.frameworkName} v${service.frameworkVersion} | API ${service.apiVersion}"
    }

    private fun setupNavigation() {
        binding.navFunctions.setOnClickListener { showTab(MainTab.FUNCTIONS) }
        binding.navBanks.setOnClickListener { showTab(MainTab.BANKS) }
        binding.navHome.setOnClickListener { showTab(MainTab.HOME) }
        binding.navAi.setOnClickListener { showTab(MainTab.AI) }
        binding.navProfile.setOnClickListener { showTab(MainTab.PROFILE) }
    }

    private fun showTab(tab: MainTab) {
        val changed = currentTab != tab
        currentTab = tab
        binding.pageFunctions.visibility = if (tab == MainTab.FUNCTIONS) View.VISIBLE else View.GONE
        binding.pageBanks.visibility = if (tab == MainTab.BANKS) View.VISIBLE else View.GONE
        binding.pageHome.visibility = if (tab == MainTab.HOME) View.VISIBLE else View.GONE
        binding.pageAi.visibility = if (tab == MainTab.AI) View.VISIBLE else View.GONE
        binding.pageProfile.visibility = if (tab == MainTab.PROFILE) View.VISIBLE else View.GONE

        styleNav(binding.navFunctions, tab == MainTab.FUNCTIONS)
        styleNav(binding.navBanks, tab == MainTab.BANKS)
        styleNav(binding.navHome, tab == MainTab.HOME)
        styleNav(binding.navAi, tab == MainTab.AI)
        styleNav(binding.navProfile, tab == MainTab.PROFILE)

        if (changed) {
            animatePageEnter(pageForTab(tab))
            animateActiveNav(navForTab(tab))
        }
    }

    private fun pageForTab(tab: MainTab): View {
        return when (tab) {
            MainTab.FUNCTIONS -> binding.pageFunctions
            MainTab.BANKS -> binding.pageBanks
            MainTab.HOME -> binding.pageHome
            MainTab.AI -> binding.pageAi
            MainTab.PROFILE -> binding.pageProfile
        }
    }

    private fun navForTab(tab: MainTab): TextView {
        return when (tab) {
            MainTab.FUNCTIONS -> binding.navFunctions
            MainTab.BANKS -> binding.navBanks
            MainTab.HOME -> binding.navHome
            MainTab.AI -> binding.navAi
            MainTab.PROFILE -> binding.navProfile
        }
    }

    private fun animatePageEnter(page: View) {
        val content = (page as? ViewGroup)?.getChildAt(0) as? ViewGroup
        val targets = mutableListOf<View>()
        if (content != null) {
            val maxCount = minOf(content.childCount, 6)
            for (index in 0 until maxCount) {
                targets += content.getChildAt(index)
            }
        } else {
            targets += page
        }
        targets.forEachIndexed { index, child ->
            child.animate().cancel()
            child.alpha = 0f
            child.translationY = dp(18f)
            child.scaleX = 0.985f
            child.scaleY = 0.985f
            child.animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setStartDelay((index * 45L).coerceAtMost(180L))
                .setDuration(300L)
                .setInterpolator(pageEnterInterpolator)
                .start()
        }
    }

    private fun animateActiveNav(view: TextView) {
        view.animate().cancel()
        view.scaleX = 0.94f
        view.scaleY = 0.94f
        view.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(260L)
            .setInterpolator(navBounceInterpolator)
            .start()
    }

    private fun startAmbientAnimations() {
        ambientAnimators.forEach { it.cancel() }
        ambientAnimators.clear()

        val badgeViews = listOf(
            binding.versionBadge,
            binding.statusBadge,
            binding.homeBankBadge,
            binding.homeAiBadge,
            binding.homeLocationBadge
        )
        badgeViews.forEachIndexed { index, view ->
            val animator = ObjectAnimator.ofPropertyValuesHolder(
                view,
                PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, 0f, -dp(3f), 0f),
                PropertyValuesHolder.ofFloat(View.ALPHA, 0.94f, 1f, 0.94f)
            ).apply {
                duration = 2500L + index * 140L
                startDelay = index * 110L
                repeatCount = ObjectAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
            }
            ambientAnimators += animator
            animator.start()
        }
    }

    private fun dp(value: Float): Float {
        return value * resources.displayMetrics.density
    }

    private fun styleNav(view: TextView, active: Boolean) {
        view.setTextColor(getColor(if (active) R.color.ios_blue else R.color.ios_secondary_label))
        view.setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
        if (active) {
            view.setBackgroundResource(R.drawable.nav_item_active_bg)
        } else {
            view.setBackgroundColor(Color.TRANSPARENT)
        }
        view.alpha = if (active) 1f else 0.88f
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
                if (switchesLoading) return@setOnCheckedChangeListener
                val service = mService ?: return@setOnCheckedChangeListener
                try {
                    service.getRemotePreferences(PREFS_CONFIG)
                        .edit()
                        .putBoolean(key, isChecked)
                        .apply()
                    updateFeatureSummary()
                    Toast.makeText(this, "已保存，重启学习通后生效", Toast.LENGTH_SHORT).show()
                } catch (e: Throwable) {
                    Toast.makeText(this, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun setupButtons() {
        binding.btnSaveLocation.setOnClickListener { saveLocationConfig() }
        binding.btnPickMap.setOnClickListener { openMapPicker() }
        binding.btnSaveSignAssist.setOnClickListener { saveSignAssistConfig() }
        binding.btnSaveAi.setOnClickListener { saveAiConfig() }
        binding.btnAddTikuSource.setOnClickListener { addTikuSourceEditor(newCustomTikuSource()) }
        binding.btnSaveTiku.setOnClickListener { saveTikuConfig() }
        binding.btnHomeQuickSign.setOnClickListener { showTab(MainTab.FUNCTIONS) }
        binding.btnHomeQuickBanks.setOnClickListener { showTab(MainTab.BANKS) }
        binding.btnHomeQuickProfile.setOnClickListener { showTab(MainTab.PROFILE) }
        binding.btnExamTriggerVolumeDown.setOnClickListener { saveExamTriggerMode(EXAM_TRIGGER_VOLUME_DOWN) }
        binding.btnExamTriggerVolumeUp.setOnClickListener { saveExamTriggerMode(EXAM_TRIGGER_VOLUME_UP) }
        binding.btnExamTriggerVolumeCombo.setOnClickListener { saveExamTriggerMode(EXAM_TRIGGER_VOLUME_UP_DOWN) }

        binding.switchAutoSignTarget.setOnCheckedChangeListener { _, isChecked ->
            if (locationLoading) return@setOnCheckedChangeListener
            val service = mService ?: return@setOnCheckedChangeListener
            try {
                service.getRemotePreferences(PREFS_SIGN)
                    .edit()
                    .putBoolean(KEY_AUTO_TARGET_ENABLED, isChecked)
                    .apply()
                loadLocationConfig(service)
                Toast.makeText(
                    this,
                    if (isChecked) "已开启自动抓取签到目标" else "已关闭自动抓取签到目标",
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: Throwable) {
                Toast.makeText(this, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
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

        binding.btnJoinTg.setOnClickListener { openTelegramGroup() }
        binding.btnRenew.setOnClickListener { openTelegramGroup() }
        binding.btnFeedback.setOnClickListener { openFeedbackCenter() }

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
                postIfAlive {
                    binding.btnActivate.isEnabled = true
                    binding.btnActivate.text = "激活"
                    if (result.success) {
                        val service = mService
                        if (service != null) {
                            service.getRemotePreferences(PREFS_CONFIG)
                                .edit()
                                .putString("license_token", result.token)
                                .putLong("license_expires", result.expiresAt)
                                .putString("device_id", result.deviceId ?: "")
                                .apply()
                            updateLicenseStatus(service)
                        }
                        Toast.makeText(this@MainActivity, "激活成功！重启学习通生效", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this@MainActivity, result.message, Toast.LENGTH_LONG).show()
                    }
                }
            }.start()
        }
    }

    private fun openMapPicker() {
        val intent = Intent(this, MapPickerActivity::class.java)
        val lat = binding.editLat.text.toString().trim().toDoubleOrNull() ?: 0.0
        val lng = binding.editLng.text.toString().trim().toDoubleOrNull() ?: 0.0
        if (lat != 0.0 && lng != 0.0) {
            intent.putExtra(MapPickerActivity.EXTRA_INIT_LAT, lat)
            intent.putExtra(MapPickerActivity.EXTRA_INIT_LNG, lng)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, RC_MAP_PICKER)
    }

    private fun loadSwitchStates(service: XposedService) {
        try {
            switchesLoading = true
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
        } finally {
            switchesLoading = false
            updateFeatureSummary()
        }
    }

    private fun updateFeatureSummary() {
        val enabledCount = listOf(
            binding.switchDetection.isChecked,
            binding.switchAds.isChecked,
            binding.switchSignin.isChecked,
            binding.switchAnticheat.isChecked,
            binding.switchWindow.isChecked,
            binding.switchVideo.isChecked,
            binding.switchVideoTime.isChecked,
            binding.switchExam.isChecked
        ).count { it }
        binding.homeFeatureSummary.text = "已启用 $enabledCount/8 项功能"
    }

    private fun loadExamTriggerConfig(service: XposedService) {
        try {
            val prefs = service.getRemotePreferences(PREFS_CONFIG)
            applyExamTriggerMode(prefs.getString(KEY_EXAM_TRIGGER, EXAM_TRIGGER_VOLUME_DOWN))
        } catch (_: Throwable) {
            applyExamTriggerMode(EXAM_TRIGGER_VOLUME_DOWN)
        }
    }

    private fun saveExamTriggerMode(mode: String) {
        val service = mService
        if (service == null) {
            Toast.makeText(this, "框架未连接，无法保存", Toast.LENGTH_SHORT).show()
            return
        }
        val normalizedMode = normalizeExamTriggerMode(mode)
        try {
            service.getRemotePreferences(PREFS_CONFIG)
                .edit()
                .putString(KEY_EXAM_TRIGGER, normalizedMode)
                .apply()
            applyExamTriggerMode(normalizedMode)
            Toast.makeText(this, "搜题实体键已更新，重启学习通后生效", Toast.LENGTH_SHORT).show()
        } catch (e: Throwable) {
            Toast.makeText(this, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun applyExamTriggerMode(rawMode: String?) {
        val mode = normalizeExamTriggerMode(rawMode)
        setExamTriggerButtonState(binding.btnExamTriggerVolumeDown, mode == EXAM_TRIGGER_VOLUME_DOWN)
        setExamTriggerButtonState(binding.btnExamTriggerVolumeUp, mode == EXAM_TRIGGER_VOLUME_UP)
        setExamTriggerButtonState(binding.btnExamTriggerVolumeCombo, mode == EXAM_TRIGGER_VOLUME_UP_DOWN)
        binding.textExamTriggerStatus.text = when (mode) {
            EXAM_TRIGGER_VOLUME_UP -> "实体键触发：音量 +。仅在题目页注入判定通过后拦截，不再注入悬浮按钮。"
            EXAM_TRIGGER_VOLUME_UP_DOWN -> "实体键触发：先按音量 +，再按音量 -。仅在题目页注入判定通过后拦截，不再注入悬浮按钮。"
            else -> "实体键触发：音量 -。仅在题目页注入判定通过后拦截，不再注入悬浮按钮。"
        }
    }

    private fun normalizeExamTriggerMode(rawMode: String?): String {
        return when (rawMode) {
            EXAM_TRIGGER_VOLUME_UP -> EXAM_TRIGGER_VOLUME_UP
            EXAM_TRIGGER_VOLUME_UP_DOWN -> EXAM_TRIGGER_VOLUME_UP_DOWN
            else -> EXAM_TRIGGER_VOLUME_DOWN
        }
    }

    private fun setExamTriggerButtonState(button: Button, active: Boolean) {
        button.setBackgroundResource(
            if (active) R.drawable.tiku_mode_chip_active else R.drawable.tiku_mode_chip_inactive
        )
        button.setTextColor(if (active) Color.WHITE else getColor(R.color.ios_label))
    }

    private fun loadLocationConfig(service: XposedService) {
        try {
            locationLoading = true
            val prefs = service.getRemotePreferences(PREFS_SIGN)
            val manualLat = Double.fromBits(prefs.getLong("fake_lat", 0L))
            val manualLng = Double.fromBits(prefs.getLong("fake_lng", 0L))
            val manualAddr = prefs.getString("fake_addr", "") ?: ""
            val autoEnabled = prefs.getBoolean(KEY_AUTO_TARGET_ENABLED, true)
            val autoLat = Double.fromBits(prefs.getLong(KEY_AUTO_TARGET_LAT, 0L))
            val autoLng = Double.fromBits(prefs.getLong(KEY_AUTO_TARGET_LNG, 0L))
            val autoAddr = prefs.getString(KEY_AUTO_TARGET_ADDR, "") ?: ""

            binding.switchAutoSignTarget.isChecked = autoEnabled

            val lat = if (manualLat != 0.0) manualLat else if (autoEnabled) autoLat else 0.0
            val lng = if (manualLng != 0.0) manualLng else if (autoEnabled) autoLng else 0.0
            val addr = manualAddr.ifBlank { if (autoEnabled) autoAddr else "" }

            binding.editLat.setText(if (lat != 0.0) formatCoordinate(lat) else "")
            binding.editLng.setText(if (lng != 0.0) formatCoordinate(lng) else "")
            binding.editAddr.setText(addr)
            updateAutoLocationStatus(autoEnabled, manualLat, manualLng, autoLat, autoLng, autoAddr)
            updateHomeLocationSummary(autoEnabled, manualLat, manualLng, autoLat, autoLng, autoAddr)
        } catch (_: Throwable) {
        } finally {
            locationLoading = false
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
        if (latStr.isEmpty() && lngStr.isEmpty() && addr.isEmpty()) {
            try {
                service.getRemotePreferences(PREFS_SIGN)
                    .edit()
                    .remove("fake_lat")
                    .remove("fake_lng")
                    .remove("fake_addr")
                    .apply()
                loadLocationConfig(service)
                Toast.makeText(this, "已清除手动定位，后续使用自动抓取或实时定位", Toast.LENGTH_SHORT).show()
            } catch (e: Throwable) {
                Toast.makeText(this, "清除失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
            return
        }
        if (latStr.isEmpty() || lngStr.isEmpty()) {
            Toast.makeText(this, "请输入纬度和经度", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val lat = latStr.toDouble()
            val lng = lngStr.toDouble()
            if (lat !in -90.0..90.0 || lng !in -180.0..180.0) {
                Toast.makeText(this, "坐标范围无效 (纬度:-90~90, 经度:-180~180)", Toast.LENGTH_SHORT).show()
                return
            }
            service.getRemotePreferences(PREFS_SIGN)
                .edit()
                .putLong("fake_lat", lat.toBits())
                .putLong("fake_lng", lng.toBits())
                .putString("fake_addr", addr)
                .apply()
            loadLocationConfig(service)
            Toast.makeText(this, "定位已保存: $lat, $lng", Toast.LENGTH_SHORT).show()
        } catch (e: NumberFormatException) {
            Toast.makeText(this, "坐标格式错误，请输入有效数字", Toast.LENGTH_SHORT).show()
        } catch (e: Throwable) {
            Toast.makeText(this, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateAutoLocationStatus(
        autoEnabled: Boolean,
        manualLat: Double,
        manualLng: Double,
        autoLat: Double,
        autoLng: Double,
        autoAddr: String
    ) {
        binding.textAutoLocationStatus.text = when {
            !autoEnabled -> "自动抓取已关闭，仅使用手动定位。"
            manualLat != 0.0 && manualLng != 0.0 -> "当前已保存手动定位，自动抓取继续作为备用来源。"
            autoLat != 0.0 && autoLng != 0.0 -> {
                val coord = "${formatCoordinate(autoLat)}, ${formatCoordinate(autoLng)}"
                if (autoAddr.isNotBlank()) {
                    "已自动捕获签到目标: $autoAddr ($coord)"
                } else {
                    "已自动捕获签到目标坐标: $coord"
                }
            }
            else -> "自动抓取已开启，进入定位签到页后会自动更新目标区域。"
        }
    }

    private fun updateHomeLocationSummary(
        autoEnabled: Boolean,
        manualLat: Double,
        manualLng: Double,
        autoLat: Double,
        autoLng: Double,
        autoAddr: String
    ) {
        val hasManual = manualLat != 0.0 && manualLng != 0.0
        val hasAuto = autoLat != 0.0 && autoLng != 0.0
        binding.homeLocationBadge.text = when {
            hasManual -> "定位 手动"
            hasAuto -> "定位 自动"
            autoEnabled -> "定位 待抓取"
            else -> "定位 已关闭"
        }
        binding.homeLocationSummary.text = when {
            hasManual -> "当前优先使用手动定位 ${formatCoordinate(manualLat)}, ${formatCoordinate(manualLng)}。"
            hasAuto && autoAddr.isNotBlank() -> "已自动捕获签到目标 $autoAddr，可直接用于定位签到。"
            hasAuto -> "已自动捕获签到目标坐标 ${formatCoordinate(autoLat)}, ${formatCoordinate(autoLng)}。"
            autoEnabled -> "进入定位签到页面后会自动抓取目标位置并写回本地。"
            else -> "自动抓取已关闭，需要手动填写定位信息。"
        }
    }

    private fun loadSignAssistConfig(service: XposedService) {
        try {
            val prefs = service.getRemotePreferences(PREFS_SIGN)
            val signCode = prefs.getString(KEY_SIGN_CODE, "") ?: ""
            val legacyQrPayload = prefs.getString(KEY_QR_PAYLOAD, "") ?: ""
            var h5QrPayload = prefs.getString(KEY_H5_QR_PAYLOAD, "") ?: ""
            var nativeQrPayload = prefs.getString(KEY_NATIVE_QR_PAYLOAD, "") ?: ""
            if (h5QrPayload.isBlank() && nativeQrPayload.isBlank() && legacyQrPayload.isNotBlank()) {
                val (legacyH5, legacyNative) = splitLegacyQrPayload(legacyQrPayload)
                h5QrPayload = legacyH5
                nativeQrPayload = legacyNative
            }
            val photoUri = prefs.getString(KEY_PHOTO_URI, "") ?: ""
            val lastMode = prefs.getString(KEY_SIGN_LAST_MODE, "") ?: ""
            val lastAction = prefs.getString(KEY_SIGN_LAST_ACTION, "") ?: ""
            val lastUrl = prefs.getString(KEY_SIGN_LAST_URL, "") ?: ""
            val lastAt = prefs.getLong(KEY_SIGN_LAST_AT, 0L)

            binding.editSignCode.setText(signCode)
            binding.editH5QrPayload.setText(h5QrPayload)
            binding.editNativeQrPayload.setText(nativeQrPayload)
            binding.editPhotoUri.setText(photoUri)
            updateSignAssistStatus(lastMode, lastAction, lastUrl, lastAt, signCode, h5QrPayload, nativeQrPayload, photoUri)
        } catch (_: Throwable) {
        }
    }

    private fun saveSignAssistConfig() {
        val service = mService
        if (service == null) {
            Toast.makeText(this, "框架未连接，无法保存", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val signCode = binding.editSignCode.text.toString().trim()
            val h5QrPayload = binding.editH5QrPayload.text.toString().trim()
            val nativeQrPayload = binding.editNativeQrPayload.text.toString().trim()
            val photoUri = binding.editPhotoUri.text.toString().trim()
            service.getRemotePreferences(PREFS_SIGN)
                .edit()
                .putString(KEY_SIGN_CODE, signCode)
                .putString(KEY_H5_QR_PAYLOAD, h5QrPayload)
                .putString(KEY_NATIVE_QR_PAYLOAD, nativeQrPayload)
                .remove(KEY_QR_PAYLOAD)
                .putString(KEY_PHOTO_URI, photoUri)
                .apply()
            loadSignAssistConfig(service)
            Toast.makeText(this, "签到辅助配置已保存", Toast.LENGTH_SHORT).show()
        } catch (e: Throwable) {
            Toast.makeText(this, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateSignAssistStatus(
        lastMode: String,
        lastAction: String,
        lastUrl: String,
        lastAt: Long,
        signCode: String,
        h5QrPayload: String,
        nativeQrPayload: String,
        photoUri: String
    ) {
        val configuredItems = mutableListOf<String>()
        if (signCode.isNotBlank()) configuredItems += "签到口令"
        if (h5QrPayload.isNotBlank()) configuredItems += "H5 二维码"
        if (nativeQrPayload.isNotBlank()) configuredItems += "原生扫码"
        if (photoUri.isNotBlank()) configuredItems += "拍照 URI"
        val configured = configuredItems.size
        val configuredSummary = if (configuredItems.isEmpty()) "未配置" else configuredItems.joinToString("、")
        binding.textSignAssistStatus.text = when {
            lastMode.isNotBlank() -> buildString {
                append("已配置 $configured/4 项（")
                append(configuredSummary)
                append("）。最近识别到")
                append(lastMode)
                if (lastAction.isNotBlank()) {
                    append("，")
                    append(lastAction)
                }
                if (lastAt > 0L) {
                    append("（")
                    append(formatTime(lastAt))
                    append("）")
                }
                if (lastUrl.isNotBlank()) {
                    append("。")
                    append(lastUrl)
                }
            }
            configured > 0 -> "已配置 $configured/4 项辅助参数（$configuredSummary）；H5 rcode 与原生 signId/time 会分开使用，互不串线。"
            else -> "可分别预填签到口令、H5 rcode、原生 signId/time 载荷与拍照签到图片 URI；页面若暴露口令，也会自动尝试提取并提交。"
        }
    }

    private fun splitLegacyQrPayload(rawPayload: String): Pair<String, String> {
        val trimmed = rawPayload.trim()
        if (trimmed.isEmpty()) return "" to ""
        return if (normalizeNativeQrPayload(trimmed).isNotBlank()) {
            "" to trimmed
        } else {
            trimmed to ""
        }
    }

    private fun normalizeNativeQrPayload(rawPayload: String): String {
        val trimmed = rawPayload.trim()
        if (trimmed.isEmpty()) return ""
        return try {
            val json = JSONObject(trimmed)
            if (json.optString("signId", "").isNotEmpty() && json.optString("time", "").isNotEmpty()) {
                json.toString()
            } else {
                ""
            }
        } catch (_: Throwable) {
            try {
                val uri = Uri.parse(trimmed)
                val signId = uri.getQueryParameter("signId")
                val time = uri.getQueryParameter("time")
                if (!signId.isNullOrEmpty() && !time.isNullOrEmpty()) {
                    JSONObject().apply {
                        put("signId", signId)
                        put("time", time)
                    }.toString()
                } else {
                    ""
                }
            } catch (_: Throwable) {
                ""
            }
        }
    }

    private fun formatCoordinate(value: Double): String {
        return String.format(Locale.US, "%.6f", value)
    }

    private fun loadAiConfig(service: XposedService) {
        try {
            val prefs = service.getRemotePreferences(PREFS_CONFIG)
            val openAiKey = prefs.getString("ai_openai_key", "") ?: ""
            val openAiUrl = prefs.getString("ai_openai_url", "") ?: ""
            val openAiModel = prefs.getString("ai_openai_model", "") ?: ""
            val geminiKey = prefs.getString("ai_gemini_key", "") ?: ""
            val geminiModel = prefs.getString("ai_gemini_model", "") ?: ""
            binding.editOpenaiKey.setText(openAiKey)
            binding.editOpenaiUrl.setText(openAiUrl)
            binding.editOpenaiModel.setText(openAiModel)
            binding.editGeminiKey.setText(geminiKey)
            binding.editGeminiModel.setText(geminiModel)
            updateAiSummary(openAiKey.isNotBlank(), geminiKey.isNotBlank())
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
            val openAiKey = binding.editOpenaiKey.text.toString().trim()
            val openAiUrl = normalizeHttpUrl(binding.editOpenaiUrl.text.toString().trim(), keepPath = true)
            val openAiModel = binding.editOpenaiModel.text.toString().trim()
            val geminiKey = binding.editGeminiKey.text.toString().trim()
            val geminiModel = binding.editGeminiModel.text.toString().trim()
            service.getRemotePreferences(PREFS_CONFIG)
                .edit()
                .putString("ai_openai_key", openAiKey)
                .putString("ai_openai_url", openAiUrl)
                .putString("ai_openai_model", openAiModel)
                .putString("ai_gemini_key", geminiKey)
                .putString("ai_gemini_model", geminiModel)
                .apply()
            binding.editOpenaiUrl.setText(openAiUrl)
            updateAiSummary(openAiKey.isNotBlank(), geminiKey.isNotBlank())
            Toast.makeText(this, "AI 配置已保存，重启学习通生效", Toast.LENGTH_SHORT).show()
        } catch (e: Throwable) {
            Toast.makeText(this, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadTikuConfig(service: XposedService) {
        try {
            val prefs = service.getRemotePreferences(PREFS_CONFIG)
            val rawJson = prefs.getString(KEY_TIKU_SOURCES_JSON, "") ?: ""
            val legacyUrl = prefs.getString("lemtk_url", "") ?: ""
            val legacyToken = prefs.getString("lemtk_token", "") ?: ""
            val schemaVersion = prefs.getInt(KEY_TIKU_SOURCE_SCHEMA_VERSION, 0)
            val sources = parseTikuSources(rawJson, legacyUrl, legacyToken, schemaVersion)
            bindTikuSources(sources)
            val cacheEnabled = prefs.getBoolean(KEY_CACHE_ENABLED, true)
            binding.switchCache.isChecked = cacheEnabled
            updateBankSummary(sources, cacheEnabled)
        } catch (_: Throwable) {
            val defaults = defaultTikuSources()
            bindTikuSources(defaults)
            updateBankSummary(defaults, true)
        }
    }

    private fun saveTikuConfig() {
        val service = mService
        if (service == null) {
            Toast.makeText(this, "框架未连接，无法保存", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val sources = collectTikuSources()
            bindTikuSources(sources)
            val arr = JSONArray()
            sources.forEach { source ->
                if (source.name.isBlank() && source.baseUrl.isBlank() && source.token.isBlank()) {
                    return@forEach
                }
                arr.put(JSONObject().apply {
                    put("name", source.name)
                    put("baseUrl", source.baseUrl)
                    put("token", source.token)
                    put("enabled", source.enabled)
                    put("mode", source.mode)
                    if (source.answerPath.isNotBlank()) {
                        put("answerPath", source.answerPath)
                    }
                    if (source.templateParams.isNotEmpty()) {
                        put("templateParams", JSONArray().apply {
                            source.templateParams.forEach { param ->
                                put(JSONObject().apply {
                                    put("key", param.key)
                                    put("value", param.value)
                                    put("placement", param.placement)
                                })
                            }
                        })
                    }
                })
            }

            val primary = sources.firstOrNull { it.enabled && it.baseUrl.isNotBlank() }
                ?: sources.firstOrNull { it.baseUrl.isNotBlank() }
            val cacheEnabled = binding.switchCache.isChecked

            service.getRemotePreferences(PREFS_CONFIG)
                .edit()
                .putString(KEY_TIKU_SOURCES_JSON, arr.toString())
                .putInt(KEY_TIKU_SOURCE_SCHEMA_VERSION, TIKU_SOURCE_SCHEMA_VERSION)
                .putBoolean(KEY_CACHE_ENABLED, cacheEnabled)
                .putString("lemtk_url", primary?.baseUrl ?: "")
                .putString("lemtk_token", primary?.token ?: "")
                .apply()

            updateBankSummary(sources, cacheEnabled)
            Toast.makeText(this, "题库配置已保存，重启学习通生效", Toast.LENGTH_SHORT).show()
        } catch (e: Throwable) {
            Toast.makeText(this, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun collectTikuSources(): List<TikuSourceConfig> {
        return tikuSourceEditors.mapIndexed { index, views ->
            val baseUrl = normalizeHttpUrl(views.urlEdit.text.toString().trim())
            val nameInput = views.nameEdit.text.toString().trim()
            val fallbackName = when (index) {
                0 -> "柠檬推荐节点"
                1 -> "柠檬官方节点"
                2 -> "ZXSeek / Wkexam"
                else -> "自定义节点 ${index - 2}"
            }
            val mode = inferTikuSourceMode(
                nameInput.ifBlank { fallbackName },
                baseUrl,
                selectedTikuSourceMode(views)
            )
            val isCustomTemplate = isCustomTikuSourceMode(mode)
            TikuSourceConfig(
                name = if (nameInput.isNotBlank()) nameInput else if (baseUrl.isNotBlank()) fallbackName else "",
                baseUrl = baseUrl,
                token = if (isCustomTemplate) "" else views.tokenEdit.text.toString().trim(),
                enabled = views.enabledSwitch.isChecked && baseUrl.isNotBlank(),
                mode = mode,
                answerPath = if (isCustomTemplate) views.answerPathEdit.text.toString().trim() else "",
                templateParams = if (isCustomTemplate) collectTemplateParams(views) else emptyList()
            )
        }
    }

    private fun bindTikuSources(sources: List<TikuSourceConfig>) {
        binding.tikuSourcesContainer.removeAllViews()
        tikuSourceEditors.clear()
        val initial = if (sources.isEmpty()) defaultTikuSources() else sources
        initial.forEach { source -> addTikuSourceEditor(source, refreshTitles = false) }
        refreshTikuSourceEditorMetadata()
    }

    private fun defaultTikuSources(legacyUrl: String = "", legacyToken: String = ""): MutableList<TikuSourceConfig> {
        return mutableListOf(
            TikuSourceConfig(
                name = "柠檬推荐节点",
                baseUrl = legacyUrl.ifBlank { LEMTK_RECOMMENDED_URL },
                token = legacyToken,
                enabled = true,
                mode = SOURCE_MODE_LEMTK
            ),
            TikuSourceConfig(
                name = "柠檬官方节点",
                baseUrl = LEMTK_OFFICIAL_URL,
                token = "",
                enabled = false,
                mode = SOURCE_MODE_LEMTK
            ),
            TikuSourceConfig("ZXSeek / Wkexam", ZXSEEK_DEFAULT_URL, "", false, SOURCE_MODE_ZXSEEK),
            TikuSourceConfig("自定义节点 1", "", "", false, SOURCE_MODE_AUTO)
        )
    }

    private fun parseTikuSources(
        rawJson: String,
        legacyUrl: String,
        legacyToken: String,
        schemaVersion: Int = 0
    ): List<TikuSourceConfig> {
        val defaults = defaultTikuSources(legacyUrl, legacyToken)
        if (rawJson.isBlank()) return defaults
        return try {
            val arr = JSONArray(rawJson)
            val parsed = mutableListOf<TikuSourceConfig>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val name = obj.optString("name", "").trim()
                val baseUrl = obj.optString("baseUrl", "").trim()
                val token = obj.optString("token", "").trim()
                val answerPath = obj.optString("answerPath", "").trim()
                val templateParams = parseTemplateParams(obj.optJSONArray("templateParams"))
                val mode = inferTikuSourceMode(name, baseUrl, obj.optString("mode", ""))
                val enabled = obj.optBoolean("enabled", false) && baseUrl.isNotBlank()
                if (name.isBlank() && baseUrl.isBlank() && token.isBlank() && answerPath.isBlank() && templateParams.isEmpty()) {
                    continue
                }
                parsed.add(
                    TikuSourceConfig(
                        name = name,
                        baseUrl = baseUrl,
                        token = token,
                        enabled = enabled,
                        mode = mode,
                        answerPath = answerPath,
                        templateParams = templateParams
                    )
                )
            }
            if (parsed.isEmpty()) {
                defaults
            } else {
                if (schemaVersion < ZXSEEK_PRESET_SCHEMA_VERSION && parsed.none { isZxSeekSource(it) }) {
                    parsed.add(minOf(2, parsed.size), TikuSourceConfig("ZXSeek / Wkexam", ZXSEEK_DEFAULT_URL, "", false, SOURCE_MODE_ZXSEEK))
                }
                parsed
            }
        } catch (_: Throwable) {
            defaults
        }
    }

    private fun addTikuSourceEditor(source: TikuSourceConfig, refreshTitles: Boolean = true) {
        val itemView = layoutInflater.inflate(R.layout.item_tiku_source, binding.tikuSourcesContainer, false)
        val views = TikuSourceViews(
            rootView = itemView,
            titleView = itemView.findViewById(R.id.text_source_title),
            modeHintView = itemView.findViewById(R.id.text_source_mode_hint),
            enabledSwitch = itemView.findViewById(R.id.switch_source_enabled),
            nameEdit = itemView.findViewById(R.id.edit_source_name),
            autoModeButton = itemView.findViewById(R.id.btn_source_mode_auto),
            lemtkModeButton = itemView.findViewById(R.id.btn_source_mode_lemtk),
            adapterModeButton = itemView.findViewById(R.id.btn_source_mode_adapter),
            zxseekModeButton = itemView.findViewById(R.id.btn_source_mode_zxseek),
            customGetModeButton = itemView.findViewById(R.id.btn_source_mode_custom_get),
            customPostModeButton = itemView.findViewById(R.id.btn_source_mode_custom_post),
            urlEdit = itemView.findViewById(R.id.edit_source_url),
            tokenEdit = itemView.findViewById(R.id.edit_source_token),
            customSection = itemView.findViewById(R.id.layout_source_custom_template),
            answerPathEdit = itemView.findViewById(R.id.edit_source_answer_path),
            templateParamsContainer = itemView.findViewById(R.id.container_source_template_params),
            addTemplateParamButton = itemView.findViewById(R.id.btn_add_source_template_param),
            quickFillButton = itemView.findViewById(R.id.btn_source_quick_fill),
            deleteButton = itemView.findViewById(R.id.btn_source_delete)
        )
        views.enabledSwitch.isChecked = source.enabled
        views.nameEdit.setText(source.name)
        views.urlEdit.setText(source.baseUrl)
        views.tokenEdit.setText(source.token)
        views.answerPathEdit.setText(source.answerPath)
        source.templateParams.forEach { addTemplateParamEditor(views, it) }
        applyTikuSourceMode(views, source.mode)
        modeButtonPairs(views).forEach { (button, mode) ->
            button.setOnClickListener { applyTikuSourceMode(views, mode) }
        }
        views.addTemplateParamButton.setOnClickListener {
            addTemplateParamEditor(views, newBlankTemplateParamForMode(selectedTikuSourceMode(views)))
        }
        views.quickFillButton.setOnClickListener {
            applyTikuSourceMode(views, SOURCE_MODE_ZXSEEK)
            views.urlEdit.setText(ZXSEEK_DEFAULT_URL)
            if (views.nameEdit.text.toString().trim().isEmpty()) {
                views.nameEdit.setText("ZXSeek / Wkexam")
            }
            views.tokenEdit.requestFocus()
            Toast.makeText(this, "已填入 ZXSeek 官方接口地址", Toast.LENGTH_SHORT).show()
        }
        views.deleteButton.setOnClickListener {
            val index = tikuSourceEditors.indexOf(views)
            if (index < PRESET_TIKU_SOURCE_COUNT) {
                return@setOnClickListener
            }
            tikuSourceEditors.remove(views)
            binding.tikuSourcesContainer.removeView(views.rootView)
            refreshTikuSourceEditorMetadata()
            Toast.makeText(this, "已删除该自定义题库源", Toast.LENGTH_SHORT).show()
        }
        tikuSourceEditors.add(views)
        binding.tikuSourcesContainer.addView(itemView)
        if (refreshTitles) {
            refreshTikuSourceEditorMetadata()
        }
    }

    private fun refreshTikuSourceEditorMetadata() {
        tikuSourceEditors.forEachIndexed { index, views ->
            views.titleView.text = when (index) {
                0 -> "题库源 1 · 柠檬推荐"
                1 -> "题库源 2 · 柠檬官方"
                2 -> "题库源 3 · ZXSeek 预置"
                else -> "题库源 ${index + 1}"
            }
            views.nameEdit.hint = defaultSourceNameForIndex(index)
            views.urlEdit.hint = defaultSourceUrlHint(index)
            views.quickFillButton.visibility = if (index == 2) View.VISIBLE else View.GONE
            views.deleteButton.visibility = if (index >= PRESET_TIKU_SOURCE_COUNT) View.VISIBLE else View.GONE
        }
    }

    private fun defaultSourceNameForIndex(index: Int): String {
        return when (index) {
            0 -> "柠檬推荐节点"
            1 -> "柠檬官方节点"
            2 -> "ZXSeek / Wkexam"
            else -> "自定义节点 ${index - 2}"
        }
    }

    private fun defaultSourceUrlHint(index: Int): String {
        return when (index) {
            0 -> LEMTK_RECOMMENDED_URL
            1 -> LEMTK_OFFICIAL_URL
            2 -> ZXSEEK_DEFAULT_URL
            else -> "自定义兼容节点 URL"
        }
    }

    private fun newCustomTikuSource(): TikuSourceConfig {
        val customIndex = (tikuSourceEditors.size - PRESET_TIKU_SOURCE_COUNT + 1).coerceAtLeast(1)
        return TikuSourceConfig("自定义节点 $customIndex", "", "", false, SOURCE_MODE_AUTO)
    }

    private fun isZxSeekSource(source: TikuSourceConfig): Boolean {
        if (normalizeTikuSourceMode(source.mode) == SOURCE_MODE_ZXSEEK) {
            return true
        }
        val name = source.name.lowercase(Locale.ROOT)
        val baseUrl = source.baseUrl.lowercase(Locale.ROOT)
        return baseUrl.contains("api.wkexam.com")
            || baseUrl.contains("wkexam.com/api")
            || baseUrl.contains("zxseek.com")
            || name.contains("zxseek")
            || name.contains("wkexam")
    }

    private fun modeButtonPairs(views: TikuSourceViews): List<Pair<Button, String>> {
        return listOf(
            views.autoModeButton to SOURCE_MODE_AUTO,
            views.lemtkModeButton to SOURCE_MODE_LEMTK,
            views.adapterModeButton to SOURCE_MODE_ADAPTER,
            views.zxseekModeButton to SOURCE_MODE_ZXSEEK,
            views.customGetModeButton to SOURCE_MODE_CUSTOM_GET,
            views.customPostModeButton to SOURCE_MODE_CUSTOM_POST_JSON
        )
    }

    private fun selectedTikuSourceMode(views: TikuSourceViews): String {
        return modeButtonPairs(views)
            .firstOrNull { it.first.isSelected }
            ?.second
            ?: SOURCE_MODE_AUTO
    }

    private fun applyTikuSourceMode(views: TikuSourceViews, mode: String?) {
        val normalized = normalizeTikuSourceMode(mode)
        modeButtonPairs(views).forEach { (button, value) ->
            val active = value == normalized
            button.isSelected = active
            button.setBackgroundResource(if (active) R.drawable.tiku_mode_chip_active else R.drawable.tiku_mode_chip_inactive)
            button.setTextColor(if (active) Color.WHITE else getColor(R.color.ios_label))
        }
        val isCustomTemplate = isCustomTikuSourceMode(normalized)
        views.tokenEdit.visibility = if (isCustomTemplate) View.GONE else View.VISIBLE
        views.customSection.visibility = if (isCustomTemplate) View.VISIBLE else View.GONE
        if (isCustomTemplate && views.templateParamEditors.isEmpty()) {
            addTemplateParamEditor(views, starterTemplateParamForMode(normalized))
        }
        views.modeHintView.text = when (normalized) {
            SOURCE_MODE_LEMTK -> "按 LemTk 兼容协议请求，根地址即可；Token 可留空。"
            SOURCE_MODE_ADAPTER -> "按 tikuAdapter 兼容接口请求，Token 栏可填写完整查询参数。"
            SOURCE_MODE_ZXSEEK -> "按 ZXSeek / wkexam 接口请求，建议直接填写 API 地址并在下方填 token。"
            SOURCE_MODE_CUSTOM_GET -> "自定义 GET 模板：在下方新增 Query / Header 参数行，自行决定参数名和排布。"
            SOURCE_MODE_CUSTOM_POST_JSON -> "自定义 POST JSON 模板：Body 参数会拼成 JSON，请在答案路径中写出响应取值位置。"
            else -> "根据名称和 URL 自动识别协议，适合混合兼容节点。"
        }
        views.urlEdit.hint = when (normalized) {
            SOURCE_MODE_CUSTOM_GET -> "请求地址，如 https://api.example.com/search"
            SOURCE_MODE_CUSTOM_POST_JSON -> "请求地址，如 https://api.example.com/search"
            else -> views.urlEdit.hint
        }
        views.tokenEdit.hint = when (normalized) {
            SOURCE_MODE_LEMTK -> "Token（LemTk 可留空）"
            SOURCE_MODE_ADAPTER -> "查询参数（如 token=xxx&v=1）"
            SOURCE_MODE_ZXSEEK -> "ZXSeek Token"
            else -> "Token（或 adapter 查询参数）"
        }
    }

    private fun normalizeTikuSourceMode(rawMode: String?): String {
        val lower = rawMode?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return when (lower) {
            SOURCE_MODE_LEMTK, "lemtk-compatible" -> SOURCE_MODE_LEMTK
            SOURCE_MODE_ADAPTER, "tikuadapter" -> SOURCE_MODE_ADAPTER
            SOURCE_MODE_ZXSEEK, "wkexam", "eduquest" -> SOURCE_MODE_ZXSEEK
            SOURCE_MODE_CUSTOM_GET, "custom-get", "template_get" -> SOURCE_MODE_CUSTOM_GET
            SOURCE_MODE_CUSTOM_POST_JSON, "custom-post-json", "template_post_json", "template_post" -> SOURCE_MODE_CUSTOM_POST_JSON
            else -> SOURCE_MODE_AUTO
        }
    }

    private fun inferTikuSourceMode(name: String, baseUrl: String, rawMode: String? = null): String {
        val normalizedMode = normalizeTikuSourceMode(rawMode)
        if (normalizedMode != SOURCE_MODE_AUTO) {
            return normalizedMode
        }
        val lowerName = name.trim().lowercase(Locale.ROOT)
        val lowerUrl = baseUrl.trim().lowercase(Locale.ROOT)
        if (lowerUrl.contains("api.wkexam.com")
            || lowerUrl.contains("wkexam.com/api")
            || lowerUrl.contains("zxseek.com")
            || lowerName.contains("zxseek")
            || lowerName.contains("wkexam")
            || lowerName.contains("eduquest")
        ) {
            return SOURCE_MODE_ZXSEEK
        }
        if (lowerUrl.contains("/adapter-service") || lowerName.contains("adapter")) {
            return SOURCE_MODE_ADAPTER
        }
        if (lowerUrl.contains("api.vanse.top")
            || lowerUrl.contains("api.lemtk.xyz")
            || lowerName.contains("lemtk")
            || lowerName.contains("柠檬")
        ) {
            return SOURCE_MODE_LEMTK
        }
        return SOURCE_MODE_AUTO
    }

    private fun tikuSourceModeLabel(mode: String): String {
        return when (normalizeTikuSourceMode(mode)) {
            SOURCE_MODE_LEMTK -> "LemTk 兼容"
            SOURCE_MODE_ADAPTER -> "tikuAdapter"
            SOURCE_MODE_ZXSEEK -> "ZXSeek / Wkexam"
            SOURCE_MODE_CUSTOM_GET -> "自定义 GET"
            SOURCE_MODE_CUSTOM_POST_JSON -> "自定义 POST JSON"
            else -> "自动识别"
        }
    }

    private fun isCustomTikuSourceMode(mode: String): Boolean {
        return when (normalizeTikuSourceMode(mode)) {
            SOURCE_MODE_CUSTOM_GET, SOURCE_MODE_CUSTOM_POST_JSON -> true
            else -> false
        }
    }

    private fun parseTemplateParams(rawArray: JSONArray?): List<TikuTemplateParamConfig> {
        if (rawArray == null) return emptyList()
        val result = mutableListOf<TikuTemplateParamConfig>()
        for (index in 0 until rawArray.length()) {
            val obj = rawArray.optJSONObject(index) ?: continue
            val key = obj.optString("key", "").trim()
            val value = obj.optString("value", "").trim()
            val placement = normalizeTemplateParamPlacement(obj.optString("placement", TEMPLATE_PARAM_PLACEMENT_QUERY))
            if (key.isBlank() && value.isBlank()) continue
            result += TikuTemplateParamConfig(key, value, placement)
        }
        return result
    }

    private fun collectTemplateParams(views: TikuSourceViews): List<TikuTemplateParamConfig> {
        return views.templateParamEditors.mapNotNull { paramViews ->
            val key = paramViews.keyEdit.text.toString().trim()
            val value = paramViews.valueEdit.text.toString().trim()
            if (key.isBlank() && value.isBlank()) {
                null
            } else {
                TikuTemplateParamConfig(
                    key = key,
                    value = value,
                    placement = selectedTemplateParamPlacement(paramViews)
                )
            }
        }
    }

    private fun addTemplateParamEditor(
        sourceViews: TikuSourceViews,
        config: TikuTemplateParamConfig = newBlankTemplateParamForMode(selectedTikuSourceMode(sourceViews))
    ) {
        val itemView = layoutInflater.inflate(R.layout.item_tiku_template_param, sourceViews.templateParamsContainer, false)
        val paramViews = TikuTemplateParamViews(
            rootView = itemView,
            queryButton = itemView.findViewById(R.id.btn_template_param_query),
            headerButton = itemView.findViewById(R.id.btn_template_param_header),
            bodyButton = itemView.findViewById(R.id.btn_template_param_body),
            keyEdit = itemView.findViewById(R.id.edit_template_param_key),
            valueEdit = itemView.findViewById(R.id.edit_template_param_value),
            deleteButton = itemView.findViewById(R.id.btn_delete_template_param)
        )
        paramViews.keyEdit.setText(config.key)
        paramViews.valueEdit.setText(config.value)
        applyTemplateParamPlacement(paramViews, config.placement)
        templateParamPlacementButtonPairs(paramViews).forEach { (button, placement) ->
            button.setOnClickListener { applyTemplateParamPlacement(paramViews, placement) }
        }
        paramViews.deleteButton.setOnClickListener {
            sourceViews.templateParamEditors.remove(paramViews)
            sourceViews.templateParamsContainer.removeView(paramViews.rootView)
        }
        sourceViews.templateParamEditors += paramViews
        sourceViews.templateParamsContainer.addView(itemView)
    }

    private fun templateParamPlacementButtonPairs(views: TikuTemplateParamViews): List<Pair<Button, String>> {
        return listOf(
            views.queryButton to TEMPLATE_PARAM_PLACEMENT_QUERY,
            views.headerButton to TEMPLATE_PARAM_PLACEMENT_HEADER,
            views.bodyButton to TEMPLATE_PARAM_PLACEMENT_BODY
        )
    }

    private fun applyTemplateParamPlacement(views: TikuTemplateParamViews, placement: String?) {
        val normalized = normalizeTemplateParamPlacement(placement)
        templateParamPlacementButtonPairs(views).forEach { (button, value) ->
            val active = value == normalized
            button.isSelected = active
            button.setBackgroundResource(if (active) R.drawable.tiku_mode_chip_active else R.drawable.tiku_mode_chip_inactive)
            button.setTextColor(if (active) Color.WHITE else getColor(R.color.ios_label))
        }
    }

    private fun selectedTemplateParamPlacement(views: TikuTemplateParamViews): String {
        return templateParamPlacementButtonPairs(views)
            .firstOrNull { it.first.isSelected }
            ?.second
            ?: TEMPLATE_PARAM_PLACEMENT_QUERY
    }

    private fun normalizeTemplateParamPlacement(rawPlacement: String?): String {
        return when (rawPlacement?.trim()?.lowercase(Locale.ROOT)) {
            TEMPLATE_PARAM_PLACEMENT_HEADER -> TEMPLATE_PARAM_PLACEMENT_HEADER
            TEMPLATE_PARAM_PLACEMENT_BODY -> TEMPLATE_PARAM_PLACEMENT_BODY
            else -> TEMPLATE_PARAM_PLACEMENT_QUERY
        }
    }

    private fun starterTemplateParamForMode(mode: String): TikuTemplateParamConfig {
        return when (normalizeTikuSourceMode(mode)) {
            SOURCE_MODE_CUSTOM_POST_JSON -> TikuTemplateParamConfig(
                key = "question",
                value = "{{question}}",
                placement = TEMPLATE_PARAM_PLACEMENT_BODY
            )
            else -> TikuTemplateParamConfig(
                key = "q",
                value = "{{question}}",
                placement = TEMPLATE_PARAM_PLACEMENT_QUERY
            )
        }
    }

    private fun newBlankTemplateParamForMode(mode: String): TikuTemplateParamConfig {
        val placement = if (normalizeTikuSourceMode(mode) == SOURCE_MODE_CUSTOM_POST_JSON) {
            TEMPLATE_PARAM_PLACEMENT_BODY
        } else {
            TEMPLATE_PARAM_PLACEMENT_QUERY
        }
        return TikuTemplateParamConfig(key = "", value = "", placement = placement)
    }

    private fun updateBankSummary(sources: List<TikuSourceConfig>, cacheEnabled: Boolean) {
        val enabledCount = sources.count { it.enabled && it.baseUrl.isNotBlank() }
        val customCount = sources.count { it.baseUrl.isNotBlank() }
        binding.homeBankBadge.text = when {
            enabledCount > 0 -> "题库 $enabledCount 已启用"
            customCount > 0 -> "题库 已配置"
            else -> "题库 默认兜底"
        }
        binding.homeBankSummary.text = "已配置 $enabledCount 个启用题库，$customCount 个兼容节点可用；协议可在卡片内直接切换，${if (cacheEnabled) "缓存已开启" else "缓存已关闭"}。"
    }

    private fun updateAiSummary(hasOpenAi: Boolean, hasGemini: Boolean) {
        binding.homeAiBadge.text = when {
            hasOpenAi && hasGemini -> "AI 双引擎"
            hasOpenAi -> "AI OpenAI"
            hasGemini -> "AI Gemini"
            else -> "AI 未配置"
        }
        binding.homeAiSummary.text = when {
            hasOpenAi && hasGemini -> "OpenAI 兼容与 Gemini 都已配置，题库未命中时会按顺序兜底。"
            hasOpenAi -> "已配置 OpenAI 兼容接口，题库未命中时会优先请求该通道。"
            hasGemini -> "已配置 Gemini，题库未命中时会自动请求 Gemini 兜底。"
            else -> "当前未配置 AI Key，题库未命中时不会再继续请求 AI。"
        }
    }

    private fun openTelegramGroup() {
        openExternalUrl(TG_GROUP_URL, "无法打开 Telegram 链接")
    }

    private fun openFeedbackCenter() {
        openExternalUrl(PUBLIC_FEEDBACK_URL, "无法打开反馈页面")
    }

    private fun openExternalUrl(url: String, errorMessage: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Throwable) {
            Toast.makeText(this, errorMessage, Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateLicenseStatus(service: XposedService) {
        try {
            val prefs = service.getRemotePreferences(PREFS_CONFIG)
            val token = prefs.getString("license_token", "") ?: ""
            val expires = prefs.getLong("license_expires", 0L)
            val deviceId = prefs.getString("device_id", "") ?: ""
            if (token.isNotEmpty() && expires > 0L) {
                val now = System.currentTimeMillis()
                if (now < expires) {
                    binding.licenseStatus.text = "已激活"
                    binding.licenseStatus.setBackgroundResource(R.drawable.status_active_bg)
                    binding.licenseStatus.setTextColor(getColor(R.color.ios_green))
                    binding.profileExpireInfo.text = "到期时间: ${formatTime(expires)}"
                    updateHomeLicenseSummary(true, expires)
                } else {
                    binding.licenseStatus.text = "许可已过期"
                    binding.licenseStatus.setBackgroundResource(R.drawable.status_inactive_bg)
                    binding.licenseStatus.setTextColor(getColor(R.color.ios_red))
                    binding.profileExpireInfo.text = "已于 ${formatTime(expires)} 到期"
                    updateHomeLicenseSummary(false, expires)
                }
            } else {
                binding.licenseStatus.text = "未激活"
                binding.licenseStatus.setBackgroundResource(R.drawable.status_inactive_bg)
                binding.licenseStatus.setTextColor(getColor(R.color.ios_red))
                binding.profileExpireInfo.text = "尚未绑定有效许可"
                updateHomeLicenseSummary(false, 0L)
            }
            binding.profileDeviceInfo.text = if (deviceId.isNotEmpty()) {
                "设备标识: $deviceId"
            } else {
                "设备标识: 未下发"
            }
        } catch (_: Throwable) {
            binding.licenseStatus.text = "未激活"
            binding.licenseStatus.setBackgroundResource(R.drawable.status_inactive_bg)
            binding.licenseStatus.setTextColor(getColor(R.color.ios_red))
            binding.profileExpireInfo.text = "尚未绑定有效许可"
            binding.profileDeviceInfo.text = "设备标识: 未下发"
            updateHomeLicenseSummary(false, 0L)
        }
    }

    private fun updateHomeLicenseSummary(active: Boolean, expiresAt: Long) {
        binding.homeLicenseSummary.text = when {
            active && expiresAt > 0L -> "许可状态: 已激活，有效至 ${formatTime(expiresAt)}。"
            expiresAt > 0L -> "许可状态: 已过期，请续期后继续使用全部功能。"
            else -> "许可状态: 未激活，部分高级能力可能不可用。"
        }
    }

    private fun normalizeHttpUrl(raw: String, keepPath: Boolean = false): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "https://$trimmed"
        }
        return if (keepPath) withScheme.trimEnd('/') else withScheme.trimEnd('/')
    }

    private fun postIfAlive(block: () -> Unit) {
        runOnUiThread {
            if (!isFinishing && !isDestroyed) block()
        }
    }

    private fun formatTime(timestamp: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == RC_MAP_PICKER && resultCode == RESULT_OK && data != null) {
            val lat = data.getDoubleExtra(MapPickerActivity.RESULT_LAT, 0.0)
            val lng = data.getDoubleExtra(MapPickerActivity.RESULT_LNG, 0.0)
            val addr = data.getStringExtra(MapPickerActivity.RESULT_ADDR) ?: ""
            binding.editLat.setText(formatCoordinate(lat))
            binding.editLng.setText(formatCoordinate(lng))
            if (addr.isNotEmpty()) binding.editAddr.setText(addr)
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
        binding.homeUpdateValue.text = "正在检查更新..."
        Thread {
            try {
                val url = URL("https://api.github.com/repos/Mai-xiyu/StarX/releases/latest")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                val code = conn.responseCode
                if (code != 200) {
                    postIfAlive { binding.homeUpdateValue.text = "更新检查失败，请稍后重试" }
                    return@Thread
                }
                val body = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                val json = JSONObject(body)
                val tagName = json.optString("tag_name", "")
                val releaseName = json.optString("name", tagName)
                val releaseBody = json.optString("body", "")
                val htmlUrl = json.optString("html_url", "")

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
                val remoteVersion = tagName.removePrefix("v").removePrefix("V")
                val hasUpdate = remoteVersion.isNotEmpty() && isNewerVersion(remoteVersion, VERSION_NAME)
                postIfAlive {
                    binding.homeUpdateValue.text = if (hasUpdate) {
                        "发现新版本: $releaseName"
                    } else {
                        "当前已是最新版本 v$VERSION_NAME"
                    }
                    if (hasUpdate) {
                        showUpdateDialog(releaseName, releaseBody, downloadUrl)
                    }
                }
            } catch (_: Throwable) {
                postIfAlive { binding.homeUpdateValue.text = "更新检查失败，请稍后重试" }
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
                    openExternalUrl(downloadUrl, "无法打开下载链接")
                }
            }
            .setCancelable(false)
            .show()
    }

    private fun checkAnnouncement() {
        binding.homeAnnouncement.text = "正在获取公告..."
        Thread {
            try {
                val url = URL("${LicenseManager.SERVER_URL}/api/meta")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                val code = conn.responseCode
                if (code != 200) {
                    postIfAlive { binding.homeAnnouncement.text = latestAnnouncementText }
                    return@Thread
                }
                val body = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                val json = JSONObject(body)
                val announcement = json.optString("announcement", "")
                latestAnnouncementText = if (announcement.isNotEmpty()) announcement else "暂无公告"
                postIfAlive {
                    binding.homeAnnouncement.text = latestAnnouncementText
                    if (announcement.isNotEmpty()) {
                        showAnnouncement(announcement)
                    }
                }
            } catch (_: Throwable) {
                postIfAlive { binding.homeAnnouncement.text = latestAnnouncementText }
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
