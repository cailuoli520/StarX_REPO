# StarX — 超星学习通 LibXposed Module 开发计划

> 项目包名: `org.xiyu.starx`
> 目标应用: `com.chaoxing.mobile` (超星学习通)
> 基于: LibXposed API 101 + Example-main 模板
> 开发语言: Java (主逻辑) + Kotlin (UI 层)

---

## 一、项目概述

StarX 是一个基于 LibXposed API 的学习通增强模块，通过 Hook 技术实现签到辅助、考试防检测、视频倍速、广告跳过等功能。

### 目标应用分析

| 项目 | 详情 |
|------|------|
| 包名 | `com.chaoxing.mobile` |
| 壳保护 | 梆梆加固 (`com.secneo.apkwrapper.AP`) |
| 架构 | Retrofit2 + OkHttp3 + RxJava |
| API 域名 | `mobilelearn.chaoxing.com` |
| 定位 SDK | 百度定位 (`com.baidu.location.LocationClient`) |
| 混淆 | ProGuard/R8，`com.chaoxing.*` 业务包保留 |

---

## 二、项目结构

```
Example-main/
├── build.gradle.kts                    (根构建)
├── settings.gradle.kts                 (rootProject.name = "StarX")
├── gradle/libs.versions.toml           (依赖版本)
├── plan.md                             (本文件)
└── app/
    ├── build.gradle.kts                (namespace = org.xiyu.starx)
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── resources/META-INF/xposed/
        │   ├── java_init.list          → org.xiyu.starx.MainModule
        │   ├── module.prop
        │   └── scope.list              → com.chaoxing.mobile
        ├── res/
        │   ├── layout/activity_main.xml
        │   └── values/strings.xml
        └── java/org/xiyu/starx/
            ├── MainModule.java         ★ 模块入口 (extends XposedModule)
            ├── App.kt                  (Application, XposedService)
            ├── MainActivity.kt         (模块设置界面)
            │
            ├── hook/                   ★ 所有 Hook 逻辑
            │   ├── AntiCheatHook.java  — 考试防切屏/防检测
            │   ├── SignInHook.java     — 签到定位/手势绕过
            │   ├── ExamHook.java       — 考试辅助
            │   ├── VideoHook.java      — 视频倍速解锁
            │   ├── AdsHook.java        — 广告/开屏跳过
            │   └── WindowHook.java     — 小窗/多窗口伪装
            │
            └── util/
                ├── CxClasses.java      — 学习通类名常量表
                └── Logx.java           — 日志工具
```

---

## 三、功能模块详细设计

### 模块 1: 广告跳过 (AdsHook) — P2，最简单，优先实现验证框架

**目标**: 跳过开屏广告，直接进入主页。

**Hook 点**:

| # | 类 | 方法 | 动作 |
|---|---|------|------|
| 1 | `com.chaoxing.mobile.activity.SplashActivity` | `G5()` | afterHook → 直接调用 `H5()` 跳转主页 |
| 2 | `com.chaoxing.mobile.activity.SplashActivity` | `F5(Ad)` | beforeHook → `param.setResult(null)` 阻止广告展示 |

**实现思路**:
```java
// Hook SplashActivity.onPackageReady
Class<?> splashClass = Class.forName("com.chaoxing.mobile.activity.SplashActivity", true, cl);
Method f5 = splashClass.getDeclaredMethod("F5", Class.forName("...Ad类"));
hook(f5).intercept(chain -> {
    // 直接跳过广告
    return null;
});
```

---

### 模块 2: 签到辅助 (SignInHook) — P0

**目标**: 位置签到伪造 GPS、手势签到自动绕过。

**2.1 定位伪造**

**关键类**: `com.chaoxing.mobile.sign.util.LocationUtils`
- 单例实例: `LocationUtils.f74175i`
- 定位客户端: `LocationClient f74178c` (百度定位 SDK)
- 位置回调: 内部类 `b extends BDAbstractLocationListener` → `onReceiveLocation(BDLocation)`
- SP 缓存: `j0.j(context, "sp_my_location_data", LatLng序列化)`

**Hook 点**:

| # | 类 | 方法 | 动作 |
|---|---|----|------|
| 1 | `com.chaoxing.mobile.sign.util.LocationUtils$b` | `onReceiveLocation(BDLocation)` | beforeHook → 替换 BDLocation 中的经纬度为目标坐标 |
| 2 | `com.baidu.location.BDLocation` | `getLatitude()` | afterHook → 返回伪造纬度 |
| 3 | `com.baidu.location.BDLocation` | `getLongitude()` | afterHook → 返回伪造经度 |
| 4 | `com.baidu.location.BDLocation` | `getAddrStr()` | afterHook → 返回伪造地址字符串 |

**实现要点**:
- 目标坐标从模块配置（RemotePreferences）读取
- 同时 Hook `android.location.LocationManager` 的 `getLastKnownLocation` 作为保底
- 需要同时伪造 `BDLocation.getLocType()` 返回 `161` (网络定位成功)

**2.2 签到类型分发**

**关键类**: `com.chaoxing.fanya.aphone.ui.course.StudentMissionFragment`
- 方法: `na(StudentMission studentMission)`
- `activeType` 枚举:
  - `2` = 普通签到
  - `17` = 特殊签到 → `G9()`
  - `44` = 投票 → `qa()`
  - `5` = 话题签到

**2.3 签到 API**

**关键类**: `ub.a` (混淆后的 URL 工厂)
- `q1()` → `pptSign/gotasksign?courseId=%s&classId=%s&puid=%s&source=%s&showSaveBtn=%d`
- `r1()` → `pptSign/preSign?activePrimaryId=%s&sys=1&chatId=%s&general=%s&ls=1&appType=15&classId=%s&uid=%s`
- Base URL: `tb.b.f133020k` (`mobilelearn.chaoxing.com`)

**2.4 手势签到绕过**

手势签到通过 WebView 中的 JS 交互实现，需 Hook WebView 层：

| # | 类 | 方法 | 动作 |
|---|---|----|------|
| 1 | WebView 容器类 | `shouldOverrideUrlLoading` | 检测签到完成 scheme 直接触发 |
| 2 | 签到结果回调 | 待具体分析 | 伪造手势验证成功结果 |

---

### 模块 3: 考试防检测 (AntiCheatHook) — P0

**目标**: 隐藏/绕过考试中的所有监控检测。

**3.1 焦点/切屏检测绕过**

学习通的反作弊**不直接使用** `isInMultiWindowMode()`，而是依赖：
- Activity 生命周期 (`onPause`/`onResume`) 上报行为
- `onTopResumedActivityChanged` 延迟检测
- `FaceCollectManager` 内部生命周期监听器

**Hook 点**:

| # | 类 | 方法 | 动作 |
|---|---|----|------|
| 1 | `android.app.Activity` | `onWindowFocusChanged(boolean)` | beforeHook → 强制 `args[0] = true` |
| 2 | `android.app.Activity` | `hasWindowFocus()` | afterHook → 返回 `true` |
| 3 | `android.app.Activity` | `onTopResumedActivityChanged(boolean)` | beforeHook → 强制 `args[0] = true` |

**3.2 生命周期伪装**

`FaceCollectManager` 注册了 `ActivityLifecycleCallbacks`（内部类 `5`）：
- `onPause()` → 暂停人脸抓拍 + 上报 `"on_pause"`
- `onResume()` → 延迟 1 秒恢复 + 上报 `"on_resume"`
- 行为上报: `com.chaoxing.mobile.exam.collect.b.d().a(examId, eventName)`

**Hook 点**:

| # | 类 | 方法 | 动作 |
|---|---|----|------|
| 1 | `com.chaoxing.mobile.exam.collect.b` | `a(String, String)` | beforeHook → 过滤 `"on_pause"` 事件，不上报 |
| 2 | `FaceCollectManager` 内部类 `5` | `onActivityPaused` | beforeHook → `param.setResult(null)` 不执行 |

**3.3 截屏检测绕过**

`FaceCollectManager` 使用 `MediaProjectionManager.createScreenCaptureIntent()` 做截屏监控。
- 截屏临时文件: `"screen_capture_temp"`, `"screen_capture_"`

| # | 类 | 方法 | 动作 |
|---|---|----|------|
| 1 | `FaceCollectManager` | `V(int, String, int, AppCompatActivity, String)` | beforeHook → 直接返回，不启动人脸采集 (可选，激进模式) |
| 2 | `FaceCollectManager` | `j0()` | afterHook → 返回 `false` (不活跃) |
| 3 | `FaceCollectManager` | `c0()` | afterHook → 返回 `0` (未启用) |

---

### 模块 4: 小窗/多窗口伪装 (WindowHook) — P1

**目标**: 允许在小窗/分屏模式下使用学习通而不被检测（借鉴 HideZoomWindow 思路）。

**原理**: HideZoomWindow 在 system_server 中 hook `WindowState.updateSurfacePosition()` 调用 `SurfaceControl.Transaction.setSkipScreenshot()`，并 hook `handleTapOutsideFocusInsideSelf()` 防止焦点切换。我们在应用层实现等效效果。

**Hook 点**:

| # | 类 | 方法 | 动作 |
|---|---|----|------|
| 1 | `android.app.Activity` | `isInMultiWindowMode()` | afterHook → 返回 `false` |
| 2 | `android.app.Activity` | `isInPictureInPictureMode()` | afterHook → 返回 `false` |
| 3 | `android.app.Activity` | `onMultiWindowModeChanged(boolean, Configuration)` | beforeHook → 强制 `args[0] = false` |
| 4 | `android.content.res.Configuration` | `windowConfiguration` 相关 | 伪造窗口模式为全屏 |

**进阶** (需 system scope，可选):

| # | 作用域 | 类 | 方法 | 动作 |
|---|-------|---|------|------|
| 1 | `android` | `com.android.server.wm.WindowState` | `updateSurfacePosition(Transaction)` | afterHook → 对学习通窗口 `setSkipScreenshot(true)` |
| 2 | `android` | `com.android.server.wm.WindowState` | `handleTapOutsideFocusInsideSelf()` | beforeHook → 阻止焦点切换 |

---

### 模块 5: 考试辅助 (ExamHook) — P1

**目标**: 题目信息提取，辅助答题。

**5.1 题目数据提取**

**关键类**: `com.chaoxing.study.course.scanner.activity.ActivityQuestion`
- 字段: `title`, `rightAnswer`, `activeId`, `questionId`, `courseId`, `classId`
- 考试入口: `com.chaoxing.mobile.fanya.CourseAuthority`
  - `getPhoneExam()` → 返回 `1` 表示开启手机考试
  - `getPhoneWork()` → 返回 `1` 表示开启手机作业

**5.2 WebView 答题注入**

**JS 接口**:
- `com.chaoxing.mobile.webapp.ui.WebAppViewerFragment` (line 1508-1509)
  - 接口 1: `addJavascriptInterface(n0Var, "javaJs")`
  - 接口 2: `addJavascriptInterface(new JsInterfaceBridge(), "androidjsbridge")`
- `JsInterfaceBridge.postNotification(String str, String str2)` — JS 协议分发核心

**Hook 点**:

| # | 类 | 方法 | 动作 |
|---|---|----|------|
| 1 | `android.webkit.WebView` | `loadUrl(String)` | afterHook → 注入答题辅助 JS |
| 2 | `android.webkit.WebView` | `evaluateJavascript(String, ValueCallback)` | 监听 → 拦截题目数据 |
| 3 | `ActivityQuestion` | 构造器或 setter | afterHook → 记录题目+正确答案到日志 |

**5.3 人脸检测绕过**

见模块 3 (AntiCheatHook) 中 3.3 截屏检测绕过部分。

---

### 模块 6: 视频倍速解锁 (VideoHook) — P2

**目标**: 解锁视频播放速度限制，支持任意倍速。

**关键类**:
- `com.chaoxing.videoplayer.base.ABSVideoView` → `setSpeed(float)` → 内部调 `d0(float, boolean)`
- `com.chaoxing.videoplayer.player.AttachmentVideoPlayer` (extends ABSVideoView)
- `com.chaoxing.videoplayer.playermanager.c` → 底层 `ijkMediaPlayer.setSpeed(float)`
- `com.chaoxing.videoplayer.view.CXSpeedView` → `setSpeedList(List<SpeedItem>)`
- `com.chaoxing.videoplayer.player.StandardVideoPlayer` → `setSpeedData(List<SpeedItem>)`

**Hook 点**:

| # | 类 | 方法 | 动作 |
|---|---|----|------|
| 1 | `StandardVideoPlayer` 或 `CXSpeedView` | `setSpeedData(List)` / `setSpeedList(List)` | afterHook → 注入额外速度选项 (3x, 4x, 5x, 8x, 16x) |
| 2 | `ABSVideoView` | `d0(float, boolean)` | beforeHook → 移除速度上限校验 (如有) |

**速度选项扩展**:
```java
// 在 setSpeedList 的 afterHook 中追加:
List<SpeedItem> list = (List<SpeedItem>) chain.getArg(0);
list.add(new SpeedItem(3.0f));
list.add(new SpeedItem(5.0f));
list.add(new SpeedItem(8.0f));
list.add(new SpeedItem(16.0f));
```

**视频进度上报拦截** (可选):
- API: `log/setlog?uid=&courseId=&classId=&chapterId=&encode=&view=json`
- 可 Hook OkHttp Interceptor 修改上报参数，伪造观看时长

---

## 四、开发顺序

```
Phase 1: 基础框架搭建
  ├── [1.1] 修改项目配置 → org.xiyu.starx
  ├── [1.2] 创建 MainModule.java 入口
  ├── [1.3] 创建 CxClasses.java 类名常量表
  └── [1.4] scope.list → com.chaoxing.mobile

Phase 2: 广告跳过 (验证 Hook 框架是否工作)
  └── [2.1] AdsHook.java — Hook SplashActivity

Phase 3: 签到辅助 (核心功能)
  ├── [3.1] SignInHook.java — 百度定位伪造
  └── [3.2] 手势签到绕过

Phase 4: 考试防检测 (核心功能)
  ├── [4.1] AntiCheatHook.java — 焦点/生命周期伪装
  ├── [4.2] FaceCollectManager 拦截
  └── [4.3] 行为上报过滤

Phase 5: 小窗伪装
  └── [5.1] WindowHook.java — 多窗口 API 伪造

Phase 6: 考试辅助
  └── [6.1] ExamHook.java — 题目提取 + WebView 注入

Phase 7: 视频倍速
  └── [7.1] VideoHook.java — 速度列表扩展

Phase 8: UI 配置界面
  └── [8.1] MainActivity.kt — 功能开关 + 坐标配置
```

---

## 五、需要修改的现有文件

| 文件 | 修改内容 |
|------|---------|
| `settings.gradle.kts` | `rootProject.name = "StarX"` |
| `app/build.gradle.kts` | `namespace = "org.xiyu.starx"` |
| `AndroidManifest.xml` | 更新 `android:label`，移除旧 activity 引用 |
| `resources/META-INF/xposed/java_init.list` | → `org.xiyu.starx.MainModule` |
| `resources/META-INF/xposed/scope.list` | → `com.chaoxing.mobile` |
| `resources/META-INF/xposed/module.prop` | 更新模块元信息 |
| `proguard-rules.pro` | 更新 keep 规则中的包名 |

---

## 六、需要新建的文件

| 文件 | 说明 |
|------|------|
| `java/org/xiyu/starx/MainModule.java` | 模块入口，分发 Hook |
| `java/org/xiyu/starx/hook/AdsHook.java` | 广告跳过 |
| `java/org/xiyu/starx/hook/SignInHook.java` | 签到辅助 |
| `java/org/xiyu/starx/hook/AntiCheatHook.java` | 考试防切屏 |
| `java/org/xiyu/starx/hook/WindowHook.java` | 小窗伪装 |
| `java/org/xiyu/starx/hook/ExamHook.java` | 考试辅助 |
| `java/org/xiyu/starx/hook/VideoHook.java` | 视频倍速 |
| `java/org/xiyu/starx/util/CxClasses.java` | 类名常量 |
| `java/org/xiyu/starx/util/Logx.java` | 日志工具 |
| `java/org/xiyu/starx/App.kt` | Application (从 Example 改包名) |
| `java/org/xiyu/starx/MainActivity.kt` | 设置界面 (从 Example 改包名+扩展) |

---

## 七、风险与注意事项

1. **梆梆加固**: 运行时 DEX 脱壳后类名可能变化，需 Hook 时机在壳解密后 (onPackageReady 时 classLoader 已包含解密后的类)
2. **混淆类名**: `ub.a`、`tb.b` 等混淆名在版本更新后可能变化，需维护映射表
3. **百度定位 SDK**: `BDLocation` 的方法签名可能因 SDK 版本不同有差异
4. **LibXposed API 101**: 使用 `hook().intercept(chain -> ...)` 模式，非传统 `XC_MethodHook`
5. **scope 声明**: `scope.list` 中 `com.chaoxing.mobile`，`module.prop` 设 `staticScope=true`
6. **Hook 时机**: 加固应用必须在 `onPackageReady` 而非 `onPackageLoaded` 中 Hook，此时 ClassLoader 已完成脱壳

---

## 八、类名常量表 (CxClasses.java 预览)

```java
public final class CxClasses {
    // 启动页
    public static final String SPLASH_ACTIVITY = "com.chaoxing.mobile.activity.SplashActivity";

    // 签到
    public static final String LOCATION_UTILS = "com.chaoxing.mobile.sign.util.LocationUtils";
    public static final String STUDENT_MISSION_FRAGMENT = "com.chaoxing.fanya.aphone.ui.course.StudentMissionFragment";
    public static final String URL_FACTORY = "ub.a";
    public static final String URL_BASE = "tb.b";

    // 考试
    public static final String FACE_COLLECT_MANAGER = "com.chaoxing.mobile.exam.collect.FaceCollectManager";
    public static final String EXAM_CAMERA_VIEW = "com.chaoxing.mobile.exam.view.ExamCameraView";
    public static final String EXAM_LIVE_FLOAT_VIEW = "com.chaoxing.mobile.exam.view.ExamLiveFloatView";
    public static final String EXAM_FACE_WINDOW = "com.chaoxing.mobile.exam.face.window.ExamFaceWindow";
    public static final String EXAM_BEHAVIOR_REPORTER = "com.chaoxing.mobile.exam.collect.b";
    public static final String ACTIVITY_QUESTION = "com.chaoxing.study.course.scanner.activity.ActivityQuestion";
    public static final String COURSE_AUTHORITY = "com.chaoxing.mobile.fanya.CourseAuthority";

    // 视频
    public static final String ABS_VIDEO_VIEW = "com.chaoxing.videoplayer.base.ABSVideoView";
    public static final String STANDARD_VIDEO_PLAYER = "com.chaoxing.videoplayer.player.StandardVideoPlayer";
    public static final String CX_SPEED_VIEW = "com.chaoxing.videoplayer.view.CXSpeedView";
    public static final String SPEED_ITEM = "com.chaoxing.videoplayer.model.SpeedItem";

    // WebView
    public static final String WEBAPP_VIEWER_FRAGMENT = "com.chaoxing.mobile.webapp.ui.WebAppViewerFragment";
    public static final String WEBAPP_VIEWER_ACTIVITY = "com.chaoxing.mobile.webapp.ui.WebAppViewerActivity";
}
```
