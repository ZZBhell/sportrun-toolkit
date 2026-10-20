package com.sportmock.hook;

import android.content.ContentResolver;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * 隐藏"模拟位置"痕迹，让腾讯定位 SDK 接受注入的坐标。
 *
 * 1. Location.isFromMockProvider() -> false
 * 2. Location.isMock()             -> false  (Android 12+ 隐藏 API)
 * 3. Settings.Secure.getString(cr, "mock_location") -> "0"
 */
public class Hook implements IXposedHookLoadPackage {

    private static final String TAG = "SportMockHook";

    @Override
    public void handleLoadPackage(LoadPackageParam lpp) {
        hookIsFromMockProvider(lpp);
        hookIsMock(lpp);
        hookMockLocationSetting(lpp);
    }

    private void hookIsFromMockProvider(LoadPackageParam lpp) {
        try {
            XposedHelpers.findAndHookMethod(
                    "android.location.Location", lpp.classLoader,
                    "isFromMockProvider",
                    new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) {
                            return Boolean.FALSE;
                        }
                    });
            XposedBridge.log(TAG + ": isFromMockProvider hooked");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": isFromMockProvider fail " + t);
        }
    }

    private void hookIsMock(LoadPackageParam lpp) {
        try {
            XposedHelpers.findAndHookMethod(
                    "android.location.Location", lpp.classLoader,
                    "isMock",
                    new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) {
                            return Boolean.FALSE;
                        }
                    });
            XposedBridge.log(TAG + ": isMock hooked");
        } catch (Throwable ignored) {
            // 老版本没有这个方法，正常
        }
    }

    private void hookMockLocationSetting(LoadPackageParam lpp) {
        try {
            XposedHelpers.findAndHookMethod(
                    "android.provider.Settings$Secure", lpp.classLoader,
                    "getString", ContentResolver.class, String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object name = param.args[1];
                            if ("mock_location".equals(name)) {
                                param.setResult("0");
                            }
                        }
                    });
            XposedBridge.log(TAG + ": mock_location hooked");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": mock_location fail " + t);
        }

        try {
            XposedHelpers.findAndHookMethod(
                    "android.provider.Settings$Secure", lpp.classLoader,
                    "getInt", ContentResolver.class, String.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object name = param.args[1];
                            if ("mock_location".equals(name)) {
                                param.setResult(0);
                            }
                        }
                    });
        } catch (Throwable ignored) {
        }
    }
}
