package com.sportmock;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private EditText etLat, etLon, etLaps, etPace, etVar, etPath;
    private TextView tvState;
    private Button btnStart, btnStop;
    private float dp;

    private static final String DEMO_PATH =
            "28.490785,113.160746;28.490827,113.161532;"
                    + "28.489799,113.161682;28.489772,113.160942";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        dp = getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(px(18), px(28), px(18), px(18));

        etLat = numEdit("28.490785");
        etLon = numEdit("113.160746");
        etLaps = numEdit("3");
        etPace = numEdit("360");
        etVar = numEdit("0.35");

        label(root, "自定义路线（推荐）：lat,lon;lat,lon;…  留空则跑椭圆");
        etPath = new EditText(this);
        etPath.setText(DEMO_PATH);
        etPath.setTextSize(12f);
        etPath.setMinLines(3);
        etPath.setMaxLines(8);
        etPath.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        root.addView(etPath);

        label(root, "田径场中心 · 纬度（路线为空时才用）");
        root.addView(etLat);
        label(root, "田径场中心 · 经度");
        root.addView(etLon);
        label(root, "圈数（路线模式 = 跑几圈）");
        root.addView(etLaps);
        label(root, "配速 秒每公里（6'00\" = 360）");
        root.addView(etPace);
        label(root, "配速起伏 0~1（0.35 推荐；0 = 全程匀速，容易被判机器人）");
        root.addView(etVar);

        btnStart = new Button(this);
        btnStart.setText("开始模拟");
        btnStop = new Button(this);
        btnStop.setText("停止");
        btnStop.setEnabled(false);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = px(20);
        root.addView(btnStart, lp);
        root.addView(btnStop, new LinearLayout.LayoutParams(-1, -2));

        tvState = new TextView(this);
        tvState.setTextSize(13f);
        tvState.setGravity(Gravity.START);
        tvState.setPadding(0, px(16), 0, 0);
        tvState.setText("未开始\n\n前置：开发者选项 → 选择模拟位置信息应用 → 选本应用\n"
                + "坐标填腾讯/高德地图上的值，会自动换算");
        root.addView(tvState);

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.POST_NOTIFICATIONS}, 1);
        }

        btnStart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startMock();
            }
        });
        btnStop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopService(new Intent(MainActivity.this, MockService.class));
                btnStart.setEnabled(true);
                btnStop.setEnabled(false);
                tvState.setText("已停止");
            }
        });
    }

    private void startMock() {
        Double lat = parse(etLat);
        Double lon = parse(etLon);
        Double laps = parse(etLaps);
        Double pace = parse(etPace);
        Double variRaw = parse(etVar);
        if (lat == null || lon == null || laps == null || pace == null) {
            Toast.makeText(this, "有参数格式不对", Toast.LENGTH_SHORT).show();
            return;
        }
        double vari = variRaw == null ? 0.35 : Math.max(0.0, Math.min(1.0, variRaw.doubleValue()));
        if (pace < 120) {
            Toast.makeText(this, "配速太小，会被判超速", Toast.LENGTH_SHORT).show();
            return;
        }

        String path = etPath.getText().toString().trim();

        Intent i = new Intent(this, MockService.class);
        i.putExtra("lat", lat.doubleValue());
        i.putExtra("lon", lon.doubleValue());
        i.putExtra("laps", laps.doubleValue());
        i.putExtra("pace", pace.doubleValue());
        i.putExtra("variability", vari);
        i.putExtra("path", path);
        startForegroundService(i);

        btnStart.setEnabled(false);
        btnStop.setEnabled(true);
        double loop = path.length() > 0 ? 0 : 400.0;
        if (path.length() > 0) {
            tvState.setText(String.format("模拟中：自定义路线 %.0f 圈 / 配速 %d'%02d\"",
                    laps.doubleValue(), (int) (pace / 60), (int) (pace % 60)));
        } else {
            tvState.setText(String.format("模拟中：椭圆 %.2f 圈 / %.2f km / 配速 %d'%02d\"",
                    laps.doubleValue(), laps.doubleValue() * loop / 1000.0,
                    (int) (pace / 60), (int) (pace % 60)));
        }
    }

    private EditText numEdit(String v) {
        EditText e = new EditText(this);
        e.setText(v);
        e.setTextSize(16f);
        e.setInputType(InputType.TYPE_CLASS_NUMBER
                | InputType.TYPE_NUMBER_FLAG_DECIMAL
                | InputType.TYPE_NUMBER_FLAG_SIGNED);
        return e;
    }

    private static Double parse(EditText e) {
        try {
            return Double.valueOf(e.getText().toString().trim());
        } catch (Exception ex) {
            return null;
        }
    }

    private void label(LinearLayout parent, String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(13f);
        v.setPadding(0, px(12), 0, px(2));
        parent.addView(v);
    }

    private int px(int v) {
        return (int) (v * dp);
    }
}
