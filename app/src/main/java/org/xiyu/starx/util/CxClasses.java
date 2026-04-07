package org.xiyu.starx.util;

import java.util.Map;

public final class CxClasses {
    private CxClasses() {}

    public static final String TARGET_PACKAGE = "com.chaoxing.mobile";

    // === 服务端动态下发 — 未激活时全部为空，Class.forName("") 直接抛 ClassNotFoundException ===
    public static String SPLASH_ACTIVITY = "";
    public static String MAIN_TAB_ACTIVITY = "";

    public static String LOCATION_UTILS = "";
    public static String STUDENT_MISSION_FRAGMENT = "";
    public static String URL_FACTORY = "";
    public static String URL_BASE = "";

    public static String FACE_COLLECT_MANAGER = "";
    public static String EXAM_CAMERA_VIEW = "";
    public static String EXAM_LIVE_FLOAT_VIEW = "";
    public static String EXAM_FACE_WINDOW = "";
    /** ExamBehaviorCollector — 考试行为上报收集器 (com.chaoxing.mobile.exam.collect.b) */
    public static String EXAM_BEHAVIOR_REPORTER = "";
    public static String ACTIVITY_QUESTION = "";
    public static String COURSE_AUTHORITY = "";

    public static String ABS_VIDEO_VIEW = "";
    public static String STANDARD_VIDEO_PLAYER = "";
    public static String CX_SPEED_VIEW = "";
    public static String SPEED_ITEM = "";
    public static String DOT_VIEW_MODEL = "";

    public static String VIDEO_TEST_VIEW = "";
    public static String TEST_ITEM = "";
    public static String TEST_OPTION_ITEM = "";

    public static String WEBAPP_VIEWER_FRAGMENT = "";
    public static String WEBAPP_VIEWER_ACTIVITY = "";

    public static String BD_LOCATION = "";
    public static String BD_LOCATION_LISTENER = "";

    // === 反检测 — 混淆类 (每版本可能变化, 服务端下发 + 本地fallback) ===
    public static String EVERISK_RISK_DISPATCHER = "";
    public static String XPOSED_HOOK_DETECTOR = "";
    public static String ROOT_DETECTION_UTIL = "";
    public static String DEVICE_INFO_UTIL = "";
    public static String MAGISK_MOUNT_DETECTOR = "";
    public static String ENVIRONMENT_DETECTOR = "";

    // === 视频 — 混淆类 ===
    public static String HTTP_CLIENT_MANAGER = "";
    public static String RETROFIT_CLIENT_HOLDER = "";
    public static String COURSE_VIDEO_EVENT = "";
    public static String ACCOUNT_GET_INSTANCE_METHOD = "";
    public static String ACCOUNT_GET_ACCOUNT_METHOD = "";

    // === 考试监控 ===
    public static String SCREEN_MONITOR_DISPATCHER = "";
    public static String EXAM_EVERISK_PROTOCOL = "";

    private static boolean ready = false;

    public static void init(Map<String, String> classes) {
        if (classes == null) return;
        SPLASH_ACTIVITY = cls(classes, "SPLASH_ACTIVITY");
        MAIN_TAB_ACTIVITY = cls(classes, "MAIN_TAB_ACTIVITY");
        LOCATION_UTILS = cls(classes, "LOCATION_UTILS");
        STUDENT_MISSION_FRAGMENT = cls(classes, "STUDENT_MISSION_FRAGMENT");
        URL_FACTORY = cls(classes, "URL_FACTORY");
        URL_BASE = cls(classes, "URL_BASE");
        FACE_COLLECT_MANAGER = cls(classes, "FACE_COLLECT_MANAGER");
        EXAM_CAMERA_VIEW = cls(classes, "EXAM_CAMERA_VIEW");
        EXAM_LIVE_FLOAT_VIEW = cls(classes, "EXAM_LIVE_FLOAT_VIEW");
        EXAM_FACE_WINDOW = cls(classes, "EXAM_FACE_WINDOW");
        EXAM_BEHAVIOR_REPORTER = cls(classes, "EXAM_BEHAVIOR_REPORTER");
        ACTIVITY_QUESTION = cls(classes, "ACTIVITY_QUESTION");
        COURSE_AUTHORITY = cls(classes, "COURSE_AUTHORITY");
        ABS_VIDEO_VIEW = cls(classes, "ABS_VIDEO_VIEW");
        STANDARD_VIDEO_PLAYER = cls(classes, "STANDARD_VIDEO_PLAYER");
        CX_SPEED_VIEW = cls(classes, "CX_SPEED_VIEW");
        SPEED_ITEM = cls(classes, "SPEED_ITEM");
        DOT_VIEW_MODEL = cls(classes, "DOT_VIEW_MODEL");
        VIDEO_TEST_VIEW = cls(classes, "VIDEO_TEST_VIEW");
        TEST_ITEM = cls(classes, "TEST_ITEM");
        TEST_OPTION_ITEM = cls(classes, "TEST_OPTION_ITEM");
        WEBAPP_VIEWER_FRAGMENT = cls(classes, "WEBAPP_VIEWER_FRAGMENT");
        WEBAPP_VIEWER_ACTIVITY = cls(classes, "WEBAPP_VIEWER_ACTIVITY");
        BD_LOCATION = cls(classes, "BD_LOCATION");
        BD_LOCATION_LISTENER = cls(classes, "BD_LOCATION_LISTENER");

        EVERISK_RISK_DISPATCHER = cls(classes, "EVERISK_RISK_DISPATCHER");
        XPOSED_HOOK_DETECTOR = cls(classes, "XPOSED_HOOK_DETECTOR");
        ROOT_DETECTION_UTIL = cls(classes, "ROOT_DETECTION_UTIL");
        DEVICE_INFO_UTIL = cls(classes, "DEVICE_INFO_UTIL");
        MAGISK_MOUNT_DETECTOR = cls(classes, "MAGISK_MOUNT_DETECTOR");
        ENVIRONMENT_DETECTOR = cls(classes, "ENVIRONMENT_DETECTOR");

        HTTP_CLIENT_MANAGER = cls(classes, "HTTP_CLIENT_MANAGER");
        RETROFIT_CLIENT_HOLDER = cls(classes, "RETROFIT_CLIENT_HOLDER");
        COURSE_VIDEO_EVENT = cls(classes, "COURSE_VIDEO_EVENT");
        ACCOUNT_GET_INSTANCE_METHOD = cls(classes, "ACCOUNT_GET_INSTANCE_METHOD");
        ACCOUNT_GET_ACCOUNT_METHOD = cls(classes, "ACCOUNT_GET_ACCOUNT_METHOD");

        SCREEN_MONITOR_DISPATCHER = cls(classes, "SCREEN_MONITOR_DISPATCHER");
        EXAM_EVERISK_PROTOCOL = cls(classes, "EXAM_EVERISK_PROTOCOL");

        ready = true;
    }

    public static boolean isReady() { return ready; }

    private static String cls(Map<String, String> m, String key) {
        String v = m.get(key);
        return v != null ? v : "";
    }
}
