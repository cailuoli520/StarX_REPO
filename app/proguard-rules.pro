# Xposed
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keepattributes RuntimeVisibleAnnotations
-keep,allowoptimization public class * extends io.github.libxposed.api.XposedModule {
    public <init>(...);
    public void onModuleLoaded(...);
    public void onPackageLoaded(...);
    public void onPackageReady(...);
    public void onSystemServerStarting(...);
}
-keep,allowoptimization @io.github.libxposed.api.annotations.* class * {
    @io.github.libxposed.api.annotations.BeforeInvocation <methods>;
    @io.github.libxposed.api.annotations.AfterInvocation <methods>;
}
# Keep hooker lambda intercept methods (called by framework via reflection)
-keepclassmembers,allowoptimization class ** implements io.github.libxposed.api.XposedInterface$MethodHooker {
    java.lang.Object intercept(io.github.libxposed.api.XposedInterface$MethodChain);
}
-keepclassmembers,allowoptimization class ** implements io.github.libxposed.api.XposedInterface$VoidMethodHooker {
    void intercept(io.github.libxposed.api.XposedInterface$MethodChain);
}
-keepclassmembers,allowoptimization class ** implements io.github.libxposed.api.XposedInterface$CtorHooker {
    void intercept(io.github.libxposed.api.XposedInterface$CtorChain);
}

# Kotlin
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
	public static void check*(...);
	public static void throw*(...);
}
-assumenosideeffects class java.util.Objects {
    public static ** requireNonNull(...);
}

# Strip debug log
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}

# Obfuscation
-repackageclasses
-allowaccessmodification
