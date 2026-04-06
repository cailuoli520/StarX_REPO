package org.xiyu.starx

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.location.Geocoder
import android.location.LocationManager
import android.os.Bundle
import android.preference.PreferenceManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.util.Locale

/**
 * 全屏地图选点 — 基于 OSMDroid (OpenStreetMap)
 */
class MapPickerActivity : Activity() {

    private lateinit var mapView: MapView
    private lateinit var tvCoordInfo: TextView
    private var pickedMarker: Marker? = null
    private var myLocationOverlay: MyLocationNewOverlay? = null

    private var pickedLat: Double = 0.0
    private var pickedLng: Double = 0.0
    private var pickedAddr: String = ""

    companion object {
        const val EXTRA_INIT_LAT = "init_lat"
        const val EXTRA_INIT_LNG = "init_lng"
        const val RESULT_LAT = "result_lat"
        const val RESULT_LNG = "result_lng"
        const val RESULT_ADDR = "result_addr"
        private const val REQ_LOCATION_PERM = 1001
    }

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ★ 关键: 必须在 setContentView / MapView 初始化之前加载 OSMDroid 配置
        // 这会初始化瓦片下载器、缓存路径、User-Agent 等核心参数
        val osmPrefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        Configuration.getInstance().load(applicationContext, osmPrefs)
        Configuration.getInstance().userAgentValue = packageName

        setContentView(R.layout.activity_map_picker)

        mapView = findViewById(R.id.map_view)
        tvCoordInfo = findViewById(R.id.tv_coord_info)
        val btnConfirm = findViewById<Button>(R.id.btn_confirm)
        val btnMyLocation = findViewById<Button>(R.id.btn_my_location)
        val editSearch = findViewById<EditText>(R.id.edit_search)
        val btnSearch = findViewById<Button>(R.id.btn_search)

        // 地图基本设置
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.setUseDataConnection(true) // 明确启用网络瓦片加载
        mapView.controller.setZoom(16.0)

        // 初始化位置: 优先用传入的已配置坐标, 否则用中国中心
        val initLat = intent.getDoubleExtra(EXTRA_INIT_LAT, 0.0)
        val initLng = intent.getDoubleExtra(EXTRA_INIT_LNG, 0.0)
        if (initLat != 0.0 && initLng != 0.0) {
            val initPoint = GeoPoint(initLat, initLng)
            mapView.controller.setCenter(initPoint)
            placePickedMarker(initPoint)
        } else {
            mapView.controller.setCenter(GeoPoint(35.86, 104.19))
            mapView.controller.setZoom(5.0)
        }

        // 请求定位权限
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ), REQ_LOCATION_PERM
            )
        } else {
            enableMyLocation()
        }

        // ★ 使用 MapEventsOverlay 处理地图点击 (兼容性更好)
        val tapReceiver = object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                placePickedMarker(p)
                return true
            }

            override fun longPressHelper(p: GeoPoint): Boolean {
                return false
            }
        }
        mapView.overlays.add(MapEventsOverlay(tapReceiver))

        // 确认按钮
        btnConfirm.setOnClickListener {
            if (pickedLat == 0.0 && pickedLng == 0.0) {
                Toast.makeText(this, "请先在地图上选择一个位置", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val result = Intent().apply {
                putExtra(RESULT_LAT, pickedLat)
                putExtra(RESULT_LNG, pickedLng)
                putExtra(RESULT_ADDR, pickedAddr)
            }
            setResult(RESULT_OK, result)
            finish()
        }

        // 回到我的定位
        btnMyLocation.setOnClickListener {
            val loc = myLocationOverlay?.myLocation
            if (loc != null) {
                mapView.controller.animateTo(loc)
                mapView.controller.setZoom(17.0)
            } else {
                Toast.makeText(this, "正在获取定位…", Toast.LENGTH_SHORT).show()
                tryAnimateToLastKnown()
            }
        }

        // 搜索
        val doSearch = {
            val query = editSearch.text.toString().trim()
            if (query.isNotEmpty()) {
                hideKeyboard()
                searchLocation(query)
            }
        }
        btnSearch.setOnClickListener { doSearch() }
        editSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doSearch()
                true
            } else false
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableMyLocation() {
        val provider = GpsMyLocationProvider(this).apply {
            addLocationSource(LocationManager.NETWORK_PROVIDER)
            addLocationSource(LocationManager.GPS_PROVIDER)
        }
        val overlay = MyLocationNewOverlay(provider, mapView).apply {
            enableMyLocation()
            enableFollowLocation()
        }
        myLocationOverlay = overlay
        mapView.overlays.add(0, overlay)

        // 如果还没有选过点, 尝试定位到当前位置
        if (pickedLat == 0.0 && pickedLng == 0.0) {
            overlay.runOnFirstFix {
                val loc = overlay.myLocation ?: return@runOnFirstFix
                runOnUiThread {
                    overlay.disableFollowLocation()
                    mapView.controller.animateTo(loc)
                    mapView.controller.setZoom(17.0)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun tryAnimateToLastKnown() {
        try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (loc != null) {
                val gp = GeoPoint(loc.latitude, loc.longitude)
                mapView.controller.animateTo(gp)
                mapView.controller.setZoom(17.0)
            } else {
                Toast.makeText(this, "无法获取当前位置", Toast.LENGTH_SHORT).show()
            }
        } catch (_: SecurityException) {
            Toast.makeText(this, "缺少定位权限", Toast.LENGTH_SHORT).show()
        }
    }

    private fun placePickedMarker(point: GeoPoint) {
        pickedLat = point.latitude
        pickedLng = point.longitude

        if (pickedMarker == null) {
            pickedMarker = Marker(mapView).apply {
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                icon = createPinDrawable()
                title = "模拟定位"
            }
            mapView.overlays.add(pickedMarker)
        }
        pickedMarker!!.position = point

        tvCoordInfo.text = "纬度: %.6f  经度: %.6f".format(pickedLat, pickedLng)
        mapView.invalidate()

        // 后台反地理编码获取地址
        Thread {
            val addr = reverseGeocode(pickedLat, pickedLng)
            runOnUiThread {
                pickedAddr = addr
                if (addr.isNotEmpty()) {
                    tvCoordInfo.text = "%.6f, %.6f · %s".format(pickedLat, pickedLng, addr)
                }
            }
        }.start()
    }

    private fun createPinDrawable(): android.graphics.drawable.Drawable {
        val density = resources.displayMetrics.density
        return object : android.graphics.drawable.Drawable() {
            private val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#FF3B30")
                style = Paint.Style.FILL
            }
            private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = 3f * density
            }
            private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(60, 0, 0, 0)
                style = Paint.Style.FILL
            }
            private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.FILL
            }

            override fun draw(canvas: Canvas) {
                val cx = bounds.width() / 2f
                val bottom = bounds.height().toFloat()
                val r = 12f * density

                // 针杆
                val path = android.graphics.Path().apply {
                    moveTo(cx, bottom - 2f * density)
                    lineTo(cx - r * 0.4f, cx + r * 0.3f)
                    quadTo(cx - r * 1.2f, cx - r * 0.3f, cx, cx - r)
                    quadTo(cx + r * 1.2f, cx - r * 0.3f, cx + r * 0.4f, cx + r * 0.3f)
                    close()
                }
                // 阴影
                canvas.drawOval(cx - 6f * density, bottom - 4f * density, cx + 6f * density, bottom, shadowPaint)
                // 图钉主体
                canvas.drawPath(path, pinPaint)
                canvas.drawPath(path, borderPaint)
                // 中心白点
                canvas.drawCircle(cx, cx - r * 0.15f, 4f * density, centerPaint)
            }

            override fun getIntrinsicWidth() = (32 * density).toInt()
            override fun getIntrinsicHeight() = (48 * density).toInt()
            override fun setAlpha(alpha: Int) {}
            override fun setColorFilter(p0: android.graphics.ColorFilter?) {}
            @Suppress("DEPRECATION")
            override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
        }
    }

    private fun searchLocation(query: String) {
        // 先尝试解析为坐标 (格式: "lat,lng" 或 "lat lng")
        val coordPattern = Regex("""(-?\d+\.?\d*)\s*[,，\s]\s*(-?\d+\.?\d*)""")
        val match = coordPattern.find(query)
        if (match != null) {
            val lat = match.groupValues[1].toDoubleOrNull()
            val lng = match.groupValues[2].toDoubleOrNull()
            if (lat != null && lng != null && lat in -90.0..90.0 && lng in -180.0..180.0) {
                val gp = GeoPoint(lat, lng)
                mapView.controller.animateTo(gp)
                mapView.controller.setZoom(17.0)
                placePickedMarker(gp)
                return
            }
        }

        // 地址搜索 (使用 Geocoder)
        Thread {
            try {
                @Suppress("DEPRECATION")
                val results = Geocoder(this, Locale.getDefault()).getFromLocationName(query, 5)
                if (results != null && results.isNotEmpty()) {
                    val first = results[0]
                    val gp = GeoPoint(first.latitude, first.longitude)
                    runOnUiThread {
                        mapView.controller.animateTo(gp)
                        mapView.controller.setZoom(17.0)
                        placePickedMarker(gp)
                    }
                } else {
                    searchNominatim(query)
                }
            } catch (e: Exception) {
                searchNominatim(query)
            }
        }.start()
    }

    private fun searchNominatim(query: String) {
        try {
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val url = java.net.URL(
                "https://nominatim.openstreetmap.org/search?q=$encoded&format=json&limit=1"
            )
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "StarX-MapPicker/1.0")
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                val arr = org.json.JSONArray(body)
                if (arr.length() > 0) {
                    val obj = arr.getJSONObject(0)
                    val lat = obj.getString("lat").toDouble()
                    val lng = obj.getString("lon").toDouble()
                    val gp = GeoPoint(lat, lng)
                    runOnUiThread {
                        mapView.controller.animateTo(gp)
                        mapView.controller.setZoom(17.0)
                        placePickedMarker(gp)
                    }
                    return
                }
            }
            conn.disconnect()
            runOnUiThread {
                Toast.makeText(this, "未找到 \"$query\"", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            runOnUiThread {
                Toast.makeText(this, "搜索失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun reverseGeocode(lat: Double, lng: Double): String {
        // 优先用 Nominatim (更可靠, 不依赖 Google 服务)
        try {
            val url = java.net.URL(
                "https://nominatim.openstreetmap.org/reverse?lat=$lat&lon=$lng&format=json&accept-language=zh"
            )
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "StarX-MapPicker/1.0")
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                val json = org.json.JSONObject(body)
                val displayName = json.optString("display_name", "")
                if (displayName.isNotEmpty()) return displayName
            }
            conn.disconnect()
        } catch (_: Exception) {}

        // 回退: Android Geocoder
        return try {
            @Suppress("DEPRECATION")
            val results = Geocoder(this, Locale.CHINA).getFromLocation(lat, lng, 1)
            if (results != null && results.isNotEmpty()) {
                val a = results[0]
                buildString {
                    a.adminArea?.let { append(it) }
                    a.locality?.let { if (!contains(it)) append(it) }
                    a.subLocality?.let { append(it) }
                    a.thoroughfare?.let { append(it) }
                    a.subThoroughfare?.let { append(it) }
                    if (isEmpty()) a.getAddressLine(0)?.let { append(it) }
                }
            } else ""
        } catch (_: Exception) { "" }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        currentFocus?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION_PERM && grantResults.isNotEmpty()
            && grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            enableMyLocation()
        }
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        mapView.onDetach()
    }
}
