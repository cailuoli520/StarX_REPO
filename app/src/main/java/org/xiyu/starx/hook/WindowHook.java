package org.xiyu.starx.hook;

import android.app.Activity;
import android.content.res.Configuration;

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
}
