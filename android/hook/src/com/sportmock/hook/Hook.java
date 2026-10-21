package com.sportmock.hook;

import android.content.ContentResolver;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * 1) 隐藏"模拟位置"痕迹，让腾讯定位 SDK 接受注入的坐标。
 *    - Location.isFromMockProvider() -> false   （Android 10 上真正起作用的是这一条）
 *    - Location.isMock()             -> false   （API 31+ 才有，老系统上找不到，忽略）
 *    - Settings.Secure.getString(cr, "mock_location") -> null
 *
 * 2) 合成传感器数据：位置是假的，但人是真静止的，计步器恒为 0。
 *    加速度计/陀螺仪是连续流，改写事件即可；
 *    **计步器是事件驱动的，静止时根本不会回调**，所以必须自己按步频主动灌事件。
 *
 * 1.2 -> 1.3 的三处修正：
 *    - 计步器改为主动注入（原来等系统回调，静止时永远等不到）
 *    - mock_location 返回 null 而不是 "0"（干净设备上这个 key 本来就不存在）
 *    - 补上 registerListener 的 5 参重载，并在 unregisterListener 时停掉注入线程
 */
public class Hook implements IXposedHookLoadPackage {

    private static final String TAG = "SportMockHook";
    /** 跑步步频（步/分）。走路约 110，跑步 160~180。 */
    private static final double CADENCE_SPM = 168.0;
    /** 一步的间隔：60000 / 168 ≈ 357 ms */
    private static final double STEP_INTERVAL_MS = 60000.0 / CADENCE_SPM;

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
            // Android 12 以下没有这个方法，正常
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
                                // 干净设备上这个 key 不存在，getString 返回 null。
                                // 返回 "0" 反而会让「非空即异常」的判断命中。
                                param.setResult(null);
                            }
                        }
                    });
            XposedBridge.log(TAG + ": mock_location(getString) hooked");
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
                        param.args[0] = SyntheticListener.wrap((SensorEventListener) listener, (Sensor) sensor);
                    }
                } catch (Throwable t) {
                    XposedBridge.log(TAG + ": wrap listener fail " + t);
                }
            }
        };

        int ok = 0;
        // registerListener(SensorEventListener, Sensor, int)
        try {
            XposedHelpers.findAndHookMethod(SensorManager.class, "registerListener",
                    SensorEventListener.class, Sensor.class, int.class, wrap);
            ok++;
        } catch (Throwable ignored) {
        }
        // registerListener(SensorEventListener, Sensor, int, Handler)
        try {
            XposedHelpers.findAndHookMethod(SensorManager.class, "registerListener",
                    SensorEventListener.class, Sensor.class, int.class, Handler.class, wrap);
            ok++;
        } catch (Throwable ignored) {
        }
        // registerListener(SensorEventListener, Sensor, int, int)
        try {
            XposedHelpers.findAndHookMethod(SensorManager.class, "registerListener",
                    SensorEventListener.class, Sensor.class, int.class, int.class, wrap);
            ok++;
        } catch (Throwable ignored) {
        }
        // registerListener(SensorEventListener, Sensor, int, int, Handler)  ← 1.3 补上
        try {
            XposedHelpers.findAndHookMethod(SensorManager.class, "registerListener",
                    SensorEventListener.class, Sensor.class, int.class, int.class, Handler.class, wrap);
            ok++;
        } catch (Throwable ignored) {
        }

        // 注销时把注入线程停掉，别让事件继续往一个已经注销的监听器里灌
        XC_MethodHook unwrap = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    Object listener = param.args[0];
                    if (listener instanceof SensorEventListener) {
                        SyntheticListener.release((SensorEventListener) listener);
                    }
                } catch (Throwable ignored) {
                }
            }
        };
        try {
            XposedHelpers.findAndHookMethod(SensorManager.class, "unregisterListener",
                    SensorEventListener.class, unwrap);
        } catch (Throwable ignored) {
        }
        try {
            XposedHelpers.findAndHookMethod(SensorManager.class, "unregisterListener",
                    SensorEventListener.class, Sensor.class, unwrap);
        } catch (Throwable ignored) {
        }

        XposedBridge.log(TAG + ": sensor hooks installed = " + ok);
    }

    private static final class SyntheticListener implements SensorEventListener {

        private static final List<SyntheticListener> ACTIVE = new ArrayList<SyntheticListener>();

        private final SensorEventListener target;
        private final Sensor sensor;
        private final int type;
        private final Handler pusher = new Handler(Looper.getMainLooper());

        private double stepBase = -1;     // 真实计步器的基线，收到第一帧真事件后接住
        private long syntheticSteps = 0;  // 我们自己累加的步数
        private long lastStepNs = 0;
        private boolean started = false;
        private boolean stopped = false;

        private SyntheticListener(SensorEventListener target, Sensor sensor) {
            this.target = target;
            this.sensor = sensor;
            this.type = sensor == null ? -1 : sensor.getType();
        }

        static SyntheticListener wrap(SensorEventListener target, Sensor sensor) {
            SyntheticListener w = new SyntheticListener(target, sensor);
            synchronized (ACTIVE) {
                ACTIVE.add(w);
            }
            w.start();
            return w;
        }

        static void release(SensorEventListener original) {
            List<SyntheticListener> dead = new ArrayList<SyntheticListener>();
            synchronized (ACTIVE) {
                for (int i = ACTIVE.size() - 1; i >= 0; i--) {
                    SyntheticListener w = ACTIVE.get(i);
                    if (w.target == original) {
                        dead.add(w);
                        ACTIVE.remove(i);
                    }
                }
            }
            for (SyntheticListener w : dead) {
                w.stop();
            }
        }

        private void start() {
            if (started) {
                return;
            }
            started = true;
            if (type == Sensor.TYPE_STEP_COUNTER || type == Sensor.TYPE_STEP_DETECTOR) {
                // 关键：计步器不会因为"静止"而回调，必须自己按节拍灌
                pusher.postDelayed(pump, 400);
            }
        }

        private void stop() {
            stopped = true;
            pusher.removeCallbacksAndMessages(null);
        }

        private final Runnable pump = new Runnable() {
            @Override
            public void run() {
                if (stopped) {
                    return;
                }
                try {
                    emitStep();
                } catch (Throwable t) {
                    XposedBridge.log(TAG + ": pump fail " + t);
                }
                if (!stopped) {
                    pusher.postDelayed(this, 100);
                }
            }
        };

        private void emitStep() {
            long now = SystemClock.elapsedRealtimeNanos();
            if (lastStepNs == 0) {
                lastStepNs = now;
                return;
            }
            double dtMs = (now - lastStepNs) / 1e6;
            if (dtMs < STEP_INTERVAL_MS) {
                return;
            }
            lastStepNs = now;
            syntheticSteps++;
            if (type == Sensor.TYPE_STEP_DETECTOR) {
                pushEvent(now, 1f);
            } else {
                double value = stepBase < 0 ? syntheticSteps : stepBase + syntheticSteps;
                pushEvent(now, (float) value);
            }
        }

        /** 自己造一个 SensorEvent 直接塞给目标监听器 */
        private void pushEvent(long timestampNs, float value) {
            SensorEvent ev = (SensorEvent) XposedHelpers.newInstance(SensorEvent.class, 1);
            ev.sensor = sensor;
            ev.timestamp = timestampNs;
            ev.accuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH;
            ev.values[0] = value;
            target.onSensorChanged(ev);
        }

        @Override
        public void onSensorChanged(SensorEvent event) {
            try {
                if (type == Sensor.TYPE_STEP_COUNTER && stepBase < 0
                        && event.values != null && event.values.length > 0) {
                    // 接住系统基线，之后在它上面累加，读数不会倒退
                    stepBase = event.values[0];
                }
                if (type == Sensor.TYPE_STEP_COUNTER || type == Sensor.TYPE_STEP_DETECTOR) {
                    // 计步器的真实事件直接吞掉：数值由 pump 统一给出，避免两套计数打架
                    return;
                }
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
            if (v == null || v.length == 0) {
                return;
            }
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
                default:
                    break;
            }
        }
    }
}
