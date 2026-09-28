package com.jiemo.a11ykeeper;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 作用域：系统框架（system_server）。
 * Hook AccessibilityManagerService 构造，拿到实例后交给 Keeper 守护。
 */
public class XposedInit implements IXposedHookLoadPackage {

    private static final String AMS_CLASS =
            "com.android.server.accessibility.AccessibilityManagerService";

    private static boolean hooked;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!"android".equals(lpparam.packageName) || hooked) {
            return;
        }
        hooked = true;
        try {
            Class<?> amsClass = XposedHelpers.findClass(AMS_CLASS, lpparam.classLoader);
            XposedBridge.hookAllConstructors(amsClass, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Keeper.start(param.thisObject);
                    } catch (Throwable t) {
                        XposedBridge.log(Constants.TAG + ": start failed: " + t);
                    }
                }
            });
            XposedBridge.log(Constants.TAG + ": hooked " + AMS_CLASS);
        } catch (Throwable t) {
            XposedBridge.log(Constants.TAG + ": hook failed: " + t);
        }
    }
}
