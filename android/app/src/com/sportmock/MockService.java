package com.sportmock;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class MockService extends Service {

    private static final String CH = "sportmock";
    private static final double STRAIGHT = 84.39;
    private static final double RADIUS = 36.80;
    private static final double JITTER = 2.5;
    private static final long INTERVAL = 1000L;
    // 配速模型常量：真人跑不会匀速，这里用「慢漂移 + 随机游走 + 每秒微抖 + 偶发冲刺/停步」
    // 拟合瞬时速度，距离按实际积分累加（不再是 s = elapsed * 固定速度）。
    private static final double WARMUP_SEC = 12.0;   // 起步加速
    private static final double COOLDOWN_M = 35.0;   // 收尾减速的距离
    private static final double SPEED_FLOOR = 0.15;  // 相对基准速度的下限
    private static final double SPEED_CEIL = 1.65;   // 上限，别跑出人类配速

    private double baseSpeed = 2.5;      // m/s
    private double variability = 0.35;   // 起伏强度 0~1
    private double driftPhase1, driftPhase2;
    private double driftPeriod1 = 210.0, driftPeriod2 = 97.0;
    private double walk = 0.0;           // 随机游走项
    private double burstUntil = -1, burstFactor = 1.0;
    private double restUntil = -1;
    private double nextEventAt = 45.0;

    private LocationManager lm;
    private volatile boolean running = false;
    private Thread worker;
    private final Random rnd = new Random();
    private final List<String> providers = new ArrayList<String>();

    // custom route, WGS-84
    private double[][] route;
    private double[] cum;
    private double routeLen;

    // GCJ-02 -> WGS-84
    private static final double A = 6378245.0;
    private static final double EE = 0.00669342162296594323;

    private static boolean outOfChina(double lat, double lon) {
        return !(lon > 73.66 && lon < 135.05 && lat > 3.86 && lat < 53.55);
    }

    private static double tfLat(double x, double y) {
        double ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y
                + 0.2 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * Math.PI)
                + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(y * Math.PI)
                + 40.0 * Math.sin(y / 3.0 * Math.PI)) * 2.0 / 3.0;
        ret += (160.0 * Math.sin(y / 12.0 * Math.PI)
                + 320 * Math.sin(y * Math.PI / 30.0)) * 2.0 / 3.0;
        return ret;
    }

    private static double tfLon(double x, double y) {
        double ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y
                + 0.1 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * Math.PI)
                + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(x * Math.PI)
                + 40.0 * Math.sin(x / 3.0 * Math.PI)) * 2.0 / 3.0;
        ret += (150.0 * Math.sin(x / 12.0 * Math.PI)
                + 300.0 * Math.sin(x / 30.0 * Math.PI)) * 2.0 / 3.0;
        return ret;
    }

    private static double[] wgs2gcj(double lat, double lon) {
        double dLat = tfLat(lon - 105.0, lat - 35.0);
        double dLon = tfLon(lon - 105.0, lat - 35.0);
        double radLat = lat / 180.0 * Math.PI;
        double magic = Math.sin(radLat);
        magic = 1 - EE * magic * magic;
        double sqrtMagic = Math.sqrt(magic);
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * Math.PI);
        dLon = (dLon * 180.0) / (A / sqrtMagic * Math.cos(radLat) * Math.PI);
        return new double[]{lat + dLat, lon + dLon};
    }

    private static double[] gcj2wgs(double lat, double lon) {
        if (outOfChina(lat, lon)) return new double[]{lat, lon};
        double wLat = lat, wLon = lon;
        for (int i = 0; i < 12; i++) {
            double[] g = wgs2gcj(wLat, wLon);
            double dLat = g[0] - lat;
            double dLon = g[1] - lon;
            wLat -= dLat;
            wLon -= dLon;
            if (Math.abs(dLat) < 1e-10 && Math.abs(dLon) < 1e-10) break;
        }
        return new double[]{wLat, wLon};
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        double rawLat = intent == null ? 31.0 : intent.getDoubleExtra("lat", 31.0);
        double rawLon = intent == null ? 121.0 : intent.getDoubleExtra("lon", 121.0);
        final double laps = intent == null ? 4.0 : intent.getDoubleExtra("laps", 4.0);
        final double pace = intent == null ? 390.0 : intent.getDoubleExtra("pace", 390.0);
        final double vari = intent == null ? 0.35 : intent.getDoubleExtra("variability", 0.35);
        String pathSpec = intent == null ? null : intent.getStringExtra("path");
        variability = Math.max(0.0, Math.min(1.0, vari));

        buildRoute(pathSpec);

        final double lat0;
        final double lon0;
        if (route != null) {
            lat0 = 0;
            lon0 = 0;
        } else {
            double[] wgs = gcj2wgs(rawLat, rawLon);
            lat0 = wgs[0];
            lon0 = wgs[1];
        }

        startForegroundCompat();

        lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        // 重入保护：上一轮还在跑就先停掉，否则两个 worker 会同时改 providers，
        // 旧 worker 的 finally 里 releaseProvider() 在遍历时被新 worker 的
        // prepareProvider() 清空，抛 ConcurrentModificationException 把进程带崩。
        if (running || worker != null) {
            running = false;
            if (worker != null) {
                worker.interrupt();
                try {
                    worker.join(1500);
                } catch (InterruptedException ignored) {
                }
                worker = null;
            }
            releaseProvider();
        }
        if (!prepareProvider()) {
            stopSelf();
            return START_NOT_STICKY;
        }

        running = true;
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                runTrack(lat0, lon0, laps, pace);
            }
        }, "mock-track");
        worker.start();
        return START_STICKY;
    }

    private void buildRoute(String spec) {
        route = null;
        cum = null;
        routeLen = 0;
        if (spec == null) return;
        String s = spec.replace("\n", ";").replace("\r", ";")
                .replace(" ", "").replace("；", ";").trim();
        if (s.length() == 0) return;
        List<double[]> list = new ArrayList<double[]>();
        for (String p : s.split(";")) {
            if (p.length() == 0) continue;
            String[] kv = p.split(",");
            if (kv.length < 2) continue;
            try {
                double la = Double.parseDouble(kv[0]);
                double lo = Double.parseDouble(kv[1]);
                list.add(gcj2wgs(la, lo));
            } catch (Exception ignored) {
            }
        }
        if (list.size() < 2) return;

        route = list.toArray(new double[0][]);
        cum = new double[route.length + 1];
        for (int i = 0; i < route.length; i++) {
            cum[i + 1] = cum[i] + dist(route[i], route[(i + 1) % route.length]);
        }
        routeLen = cum[route.length];
    }

    private double dist(double[] a, double[] b) {
        double R = 6371000.0;
        double p1 = Math.toRadians(a[0]), p2 = Math.toRadians(b[0]);
        double dp = p2 - p1;
        double dl = Math.toRadians(b[1] - a[1]);
        double x = Math.sin(dp / 2) * Math.sin(dp / 2)
                + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * R * Math.asin(Math.sqrt(x));
    }

    private double[] at(double s) {
        double x = s % routeLen;
        if (x < 0) x += routeLen;
        int i = 0;
        while (i < route.length && cum[i + 1] < x) i++;
        if (i >= route.length) i = route.length - 1;
        double seg = cum[i + 1] - cum[i];
        double f = seg <= 0 ? 0 : (x - cum[i]) / seg;
        double[] a = route[i];
        double[] b = route[(i + 1) % route.length];
        return new double[]{a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f};
    }

    private void startForegroundCompat() {
        NotificationManager nm =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(
                CH, "location mock", NotificationManager.IMPORTANCE_LOW));
        Notification n = new Notification.Builder(this, CH)
                .setContentTitle("SportMock running")
                .setContentText("Replaying track")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(1, n);
        }
    }

    private boolean prepareProvider() {
        String[] names = new String[]{
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                "fused",
                LocationManager.PASSIVE_PROVIDER
        };
        // providers 会被 mock-track 线程和 onStartCommand 线程同时读写，
        // 统一在 providers 对象上加锁，避免并发修改崩溃。
        synchronized (providers) {
            providers.clear();
            for (String name : names) {
                try {
                    try {
                        lm.removeTestProvider(name);
                    } catch (Exception ignored) {
                    }
                    lm.addTestProvider(name,
                            false, true, false, false, true, true, true, 1, 1);
                    lm.setTestProviderEnabled(name, true);
                    providers.add(name);
                } catch (Exception ignored) {
                }
            }
            return !providers.isEmpty();
        }
    }

    private void releaseProvider() {
        // 先取快照再逐个释放：不在遍历过程中让别的线程改这个 list
        java.util.List<String> snapshot;
        synchronized (providers) {
            snapshot = new java.util.ArrayList<String>(providers);
            providers.clear();
        }
        for (String name : snapshot) {
            try {
                lm.setTestProviderEnabled(name, false);
            } catch (Exception ignored) {
            }
            try {
                lm.removeTestProvider(name);
            } catch (Exception ignored) {
            }
        }
    }

    private void runTrack(double lat0, double lon0, double laps, double pace) {
        double mLat = 111320.0;
        baseSpeed = 1000.0 / pace;
        driftPhase1 = rnd.nextDouble() * Math.PI * 2;
        driftPhase2 = rnd.nextDouble() * Math.PI * 2;
        driftPeriod1 = 150.0 + rnd.nextDouble() * 180.0;
        driftPeriod2 = 45.0 + rnd.nextDouble() * 80.0;
        walk = 0.0;
        nextEventAt = 40.0 + rnd.nextDouble() * 50.0;

        double loop = route != null ? routeLen : 400.0;
        double total = laps * loop;
        double s = 0.0;
        long t0 = SystemClock.elapsedRealtime();
        long lastTick = t0;

        try {
            while (running) {
                long nowMs = SystemClock.elapsedRealtime();
                double t = (nowMs - t0) / 1000.0;
                double dt = Math.max(0.2, Math.min(3.0, (nowMs - lastTick) / 1000.0));
                lastTick = nowMs;

                double v = speedAt(s, total, t, dt);
                s += v * dt;
                if (s > total) break;

                double jLat, jLon, bx, by;

                if (route != null) {
                    double[] p = at(s);
                    double[] p2 = at(s + 2.0);
                    jLat = p[0] + gauss() / mLat;
                    jLon = p[1] + gauss() / (mLat * Math.cos(Math.toRadians(p[0])));
                    by = p2[0] - p[0];
                    bx = p2[1] - p[1];
                } else {
                    double[] off = ovalXY(s);
                    double[] off2 = ovalXY(s + 2.0);
                    jLat = lat0 + (off[1] + gauss()) / mLat;
                    jLon = lon0 + (off[0] + gauss()) / (mLat * Math.cos(Math.toRadians(lat0)));
                    by = off2[1] - off[1];
                    bx = off2[0] - off[0];
                }

                float bearing = (float) Math.toDegrees(Math.atan2(bx, by));
                if (bearing < 0f) bearing += 360f;

                long wall = System.currentTimeMillis();
                long nowNs = SystemClock.elapsedRealtimeNanos();
                // 精度随速度浮动：慢下来（走/停）时定位更飘，符合真实表现
                float acc = (float) (2.5 + rnd.nextDouble() * 2.0 + (v < baseSpeed * 0.5 ? 2.5 : 0.0));

                for (String name : providers) {
                    Location loc = new Location(name);
                    loc.setLatitude(jLat);
                    loc.setLongitude(jLon);
                    loc.setAccuracy(acc);
                    loc.setSpeed((float) v);
                    loc.setBearing(bearing);
                    loc.setAltitude(20.0 + (rnd.nextDouble() * 2.0 - 1.0));
                    loc.setTime(wall);
                    loc.setElapsedRealtimeNanos(nowNs);

                    // 真机 GPS 的 Location 一般带卫星信息（satellites / meanCn0 / maxCn0）。
                    // 这些字段缺失是"假定位"最便宜的一条识别特征，补上成本几乎为零。
                    Bundle ex = new Bundle();
                    ex.putInt("satellites", 8 + rnd.nextInt(5));
                    ex.putDouble("meanCn0", 30.0 + rnd.nextDouble() * 10.0);
                    ex.putDouble("maxCn0", 42.0);
                    loc.setExtras(ex);

                    lm.setTestProviderLocation(name, loc);
                }
                // 更新间隔抖动 ±80ms：真实 GPS 回调不是精确 1s 一次
                long sleep = INTERVAL + (long) (rnd.nextInt(160) - 80);
                Thread.sleep(Math.max(700L, sleep));
            }
        } catch (InterruptedException ignored) {
        } catch (SecurityException ignored) {
        } finally {
            releaseProvider();
        }
    }

    /**
     * 瞬时速度（m/s）：基准速度 × 起伏系数。
     * 系数 = 1 + variability × (慢漂移 + 随机游走 + 微抖)，再乘起步/收尾/冲刺/停步修正。
     */
    private double speedAt(double s, double total, double t, double dt) {
        // 随机游走：OU 风格，缓慢回归 0，避免长时间越飘越远
        walk += rnd.nextGaussian() * 0.02;
        walk *= 0.985;
        walk = Math.max(-0.45, Math.min(0.45, walk));

        double drift = 0.11 * Math.sin(2 * Math.PI * t / driftPeriod1 + driftPhase1)
                + 0.07 * Math.sin(2 * Math.PI * t / driftPeriod2 + driftPhase2);
        double factor = 1.0 + variability * (drift + walk)
                + variability * 0.012 * rnd.nextGaussian();

        if (t < WARMUP_SEC) {
            double k = t / WARMUP_SEC;
            factor *= 0.35 + 0.65 * k;          // 起步：从走路速度提到目标配速
        }
        double remain = total - s;
        if (remain < COOLDOWN_M && total > COOLDOWN_M * 2) {
            factor *= 0.55 + 0.45 * (remain / COOLDOWN_M);   // 收尾：逐渐慢下来
        }

        // 偶发事件：冲刺一段 / 短暂停步（等灯、喘口气）
        if (t >= nextEventAt) {
            double roll = rnd.nextDouble();
            if (roll < 0.30) {
                burstUntil = t + 8.0 + rnd.nextDouble() * 12.0;
                burstFactor = 1.08 + rnd.nextDouble() * 0.16;
                nextEventAt = t + 60.0 + rnd.nextDouble() * 120.0;
            } else if (roll < 0.55) {
                restUntil = t + 1.5 + rnd.nextDouble() * 2.5;
                nextEventAt = t + 60.0 + rnd.nextDouble() * 120.0;
            } else {
                nextEventAt = t + 30.0 + rnd.nextDouble() * 60.0;
            }
        }
        if (t < burstUntil) factor *= burstFactor;
        if (t < restUntil) factor *= 0.22;

        return Math.max(baseSpeed * SPEED_FLOOR, Math.min(baseSpeed * SPEED_CEIL, baseSpeed * factor));
    }

    private double gauss() {
        return rnd.nextGaussian() * JITTER;
    }

    private double[] ovalXY(double sMeters) {
        double lap = sMeters % 400.0;
        if (lap < 0) lap += 400.0;

        if (lap < STRAIGHT) {
            return new double[]{-STRAIGHT / 2 + lap, -RADIUS};
        }
        lap -= STRAIGHT;
        if (lap < Math.PI * RADIUS) {
            double a = -Math.PI / 2 + lap / RADIUS;
            return new double[]{STRAIGHT / 2 + RADIUS * Math.cos(a),
                    RADIUS * Math.sin(a)};
        }
        lap -= Math.PI * RADIUS;
        if (lap < STRAIGHT) {
            return new double[]{STRAIGHT / 2 - lap, RADIUS};
        }
        lap -= STRAIGHT;
        double a = Math.PI / 2 + lap / RADIUS;
        return new double[]{-STRAIGHT / 2 + RADIUS * Math.cos(a),
                RADIUS * Math.sin(a)};
    }

    @Override
    public void onDestroy() {
        running = false;
        if (worker != null) worker.interrupt();
        releaseProvider();
        super.onDestroy();
    }
}
