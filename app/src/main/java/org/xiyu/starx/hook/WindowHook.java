package org.xiyu.starx.hook;

import android.app.Activity;
import android.content.res.Configuration;
import android.view.View;
import android.webkit.WebView;

import org.xiyu.starx.util.Logx;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

public class WindowHook {
    private final XposedModule module;
    private final ClassLoader cl;

    public WindowHook(XposedModule module, ClassLoader cl) {
        this.module = module;
        this.cl = cl;
    }

    public void hook() throws Throwable {
        hookMultiWindowMode();
        hookPipMode();
        hookWebViewVisibility();
        hookWebViewFocus();
        Logx.i("WindowHook: initialized");
    }

    /**
     * Hook Activity.isInMultiWindowMode() → false
     * Hook Activity.onMultiWindowModeChanged(boolean, Configuration) → 强制 false
     */
    private void hookMultiWindowMode() {
        try {
            Method isInMultiWindow = Activity.class.getDeclaredMethod("isInMultiWindowMode");
            module.hook(isInMultiWindow).intercept(chain -> false);
            Logx.i("WindowHook: hooked isInMultiWindowMode() -> false");
        } catch (Throwable t) {
            Logx.w("WindowHook: isInMultiWindowMode hook failed: " + t.getMessage());
        }

        try {
            Method onMultiWindowChanged = Activity.class.getDeclaredMethod(
                    "onMultiWindowModeChanged", boolean.class, Configuration.class);
            module.hook(onMultiWindowChanged).intercept(chain -> {
                return chain.proceed(new Object[]{false, chain.getArg(1)});
            });
            Logx.i("WindowHook: hooked onMultiWindowModeChanged -> false");
        } catch (Throwable t) {
            Logx.w("WindowHook: onMultiWindowModeChanged hook failed: " + t.getMessage());
        }
    }

    /**
     * Hook Activity.isInPictureInPictureMode() → false
     */
    private void hookPipMode() {
        try {
            Method isInPip = Activity.class.getDeclaredMethod("isInPictureInPictureMode");
            module.hook(isInPip).intercept(chain -> false);
            Logx.i("WindowHook: hooked isInPictureInPictureMode() -> false");
        } catch (Throwable t) {
            Logx.w("WindowHook: isInPictureInPictureMode hook failed: " + t.getMessage());
        }
    }

    /**
     * 【全版本通杀】Hook WebView.onWindowVisibilityChanged → 始终 VISIBLE
     *
     * 当应用切到后台/分屏时, Android 会对 View 树下发 visibility=GONE/INVISIBLE,
     * WebView 内部收到后触发 JavaScript visibilitychange 事件 (document.hidden=true)。
     * 拦截此回调, 始终传入 VISIBLE, 防止 JS 层面的切屏检测。
     */
    private void hookWebViewVisibility() {
        try {
            Method onWindowVis = WebView.class.getDeclaredMethod(
                    "onWindowVisibilityChanged", int.class);
            onWindowVis.setAccessible(true);
            module.hook(onWindowVis).intercept(chain -> {
                // 始终告诉 WebView 窗口可见
                return chain.proceed(new Object[]{View.VISIBLE});
            });
            Logx.i("WindowHook: hooked WebView.onWindowVisibilityChanged → VISIBLE");
        } catch (Throwable t) {
            Logx.w("WindowHook: WebView visibility hook failed: " + t.getMessage());
        }

        // 同时 hook dispatchWindowVisibilityChanged 作为补充
        try {
            Method dispatch = WebView.class.getDeclaredMethod(
                    "dispatchWindowVisibilityChanged", int.class);
            dispatch.setAccessible(true);
            module.hook(dispatch).intercept(chain -> {
                return chain.proceed(new Object[]{View.VISIBLE});
            });
            Logx.i("WindowHook: hooked WebView.dispatchWindowVisibilityChanged → VISIBLE");
        } catch (Throwable t) {
            // dispatchWindowVisibilityChanged 可能未被 WebView 覆写, 忽略
        }
    }

    /**
     * 【全版本通杀】Hook WebView.onWindowFocusChanged → 始终 true
     *
     * WebView 内部收到 onWindowFocusChanged(false) 后会通知 web engine,
     * JavaScript 可通过 window.onfocus/onblur 事件检测焦点丢失。
     * 拦截此回调, 始终传入 true, 阻止 JS 层面的焦点检测。
     */
    private void hookWebViewFocus() {
        try {
            Method onFocus = WebView.class.getDeclaredMethod(
                    "onWindowFocusChanged", boolean.class);
            onFocus.setAccessible(true);
            module.hook(onFocus).intercept(chain -> {
                return chain.proceed(new Object[]{true});
            });
            Logx.i("WindowHook: hooked WebView.onWindowFocusChanged → true");
        } catch (Throwable t) {
            Logx.w("WindowHook: WebView focus hook failed: " + t.getMessage());
        }
    }
}
