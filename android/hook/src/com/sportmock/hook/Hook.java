package com.sportmock.hook;

import android.content.ContentResolver;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * 1) 隐藏"模拟位置"痕迹，让腾讯定位 SDK 接受注入的坐标。
 *    - Location.isFromMockProvider() -> false
 *    - Location.isMock()             -> false  (Android 12+ 隐藏 API)
 *    - Settings.Secure.getString(cr, "mock_location") -> "0"
 *
 * 2) 合成传感器数据（1.2 起）：位置是真的，但手机人是静止的，
 *    计步器读数恒为 0、加速度计只有重力。这里包一层 SensorEventListener，
 *    把发给目标应用的 onSensorChanged 事件改写成跑步波形。
 *    - 加速度计 / 线性加速度：1.8 Hz 步频振荡（加速度计带 9.81 重力）
 *    - 计步器：按 168 步/分 累计，接住原来的基线
 *    - 计步事件：每次置 1
 *    - 陀螺仪：小幅摆动噪声
 *
 * 只 hook 应用自己的 SensorManager，不需要 root 之外的东西；
 * 未注册传感器的应用不受影响（没有事件经过）。
 */
public class Hook implements IXposedHookLoadPackage {

    private static final String TAG = "SportMockHook";
    /** 跑步步频（步/分）。走路约 110，跑步 160~180。 */
    private static final double CADENCE_SPM = 168.0;

    @Override
    public void handleLoadPackage(LoadPackageParam lpp) {
        hookIsFromMockProvider(lpp);
        hookIsMock(lpp);
        hookMockLocationSetting(lpp);
        hookSensors(lpp);
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

    /** 把注册的监听器包一层，事件到达应用前改写数据。 */
    private void hookSensors(LoadPackageParam lpp) {
        XC_MethodHook wrap = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    Object listener = param.args[0];
                    Object sensor = param.args[1];
                    if (listener instanceof SensorEventListener && sensor instanceof Sensor) {
                        param.args[0] = new SyntheticListener((SensorEventListener) listener, (Sensor) sensor);
                    }
                } catch (Throwable t) {
                    XposedBridge.log(TAG + ": wrap listener fail " + t);
                }
            }
        };

        int ok = 0;
        try {
            XposedHelpers.findAndHookMethod(SensorManager.class, "registerListener",
                    SensorEventListener.class, Sensor.class, int.class, wrap);
            ok++;
        } catch (Throwable ignored) {
        }
        try {
            XposedHelpers.findAndHookMethod(SensorManager.class, "registerListener",
                    SensorEventListener.class, Sensor.class, int.class, Handler.class, wrap);
            ok++;
        } catch (Throwable ignored) {
        }
        try {
            XposedHelpers.findAndHookMethod(SensorManager.class, "registerListener",
                    SensorEventListener.class, Sensor.class, int.class, int.class, wrap);
            ok++;
        } catch (Throwable ignored) {
        }

        XposedBridge.log(TAG + ": sensor hooks installed = " + ok);
    }

    private static final class SyntheticListener implements SensorEventListener {

        private final SensorEventListener target;
        private final int type;
        private double stepBase = -1;
        private double stepOffset = 0;
        private long lastTs = -1;

        SyntheticListener(SensorEventListener target, Sensor sensor) {
            this.target = target;
            this.type = sensor == null ? -1 : sensor.getType();
        }

        @Override
        public void onSensorChanged(SensorEvent event) {
            try {
                rewrite(event);
            } catch (Throwable ignored) {
                // 合成失败就原样透传，绝不让目标应用因为 hook 崩掉
            }
            try {
                target.onSensorChanged(event);
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": target onSensorChanged threw " + t);
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
            try {
                target.onAccuracyChanged(sensor, accuracy);
            } catch (Throwable ignored) {
            }
        }

        private void rewrite(SensorEvent e) {
            float[] v = e.values;
            if (v == null || v.length == 0) return;
            double tSec = e.timestamp / 1e9;
            double omega = 2 * Math.PI * (CADENCE_SPM / 60.0);

            switch (type) {
                case Sensor.TYPE_ACCELEROMETER:
                    if (v.length >= 3) {
                        v[0] = (float) (1.15 * Math.sin(omega * tSec));
                        v[1] = (float) (0.85 * Math.sin(omega * tSec + 1.1));
                        v[2] = (float) (9.81 + 1.45 * Math.sin(2 * omega * tSec));
                    }
                    break;
                case Sensor.TYPE_LINEAR_ACCELERATION:
                    if (v.length >= 3) {
                        v[0] = (float) (1.15 * Math.sin(omega * tSec));
                        v[1] = (float) (0.85 * Math.sin(omega * tSec + 1.1));
                        v[2] = (float) (1.45 * Math.sin(2 * omega * tSec));
                    }
                    break;
                case Sensor.TYPE_GYROSCOPE:
                case Sensor.TYPE_GYROSCOPE_UNCALIBRATED:
                    if (v.length >= 3) {
                        v[0] = (float) (0.18 * Math.sin(2 * Math.PI * 0.7 * tSec));
                        v[1] = (float) (0.12 * Math.sin(2 * Math.PI * 0.9 * tSec + 0.7));
                        v[2] = (float) (0.10 * Math.sin(2 * Math.PI * 1.3 * tSec + 1.9));
                    }
                    break;
                case Sensor.TYPE_STEP_COUNTER:
                    // 接住系统给的累计基线，之后按步频往上加，读起来像真在跑
                    if (stepBase < 0) stepBase = v[0];
                    if (lastTs > 0) {
                        double dt = Math.min(5.0, Math.max(0.0, (e.timestamp - lastTs) / 1e9));
                        stepOffset += dt * (CADENCE_SPM / 60.0);
                    }
                    lastTs = e.timestamp;
                    v[0] = (float) (stepBase + stepOffset);
                    break;
                case Sensor.TYPE_STEP_DETECTOR:
                    v[0] = 1f;
                    break;
                default:
                    break;
            }
        }
    }
}
