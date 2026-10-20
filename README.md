# SportRun Toolkit

针对微信小程序 **SportRun**（校园健康跑）的轨迹注入工具集。

包含一个免 root 的定位模拟 App，和一个需要 root 的 LSPosed 隐藏模块，以及一个田径场/自定义路线的轨迹生成器。

> ⚠️ 仅供安全研究与个人学习。使用前请自行确认所在学校的相关规定与后果。

---

## 组件

| 组件 | 路径 | 需要 root | 说明 |
|---|---|---|---|
| **SportMock** | `dist/SportMock.apk` | 否 | 定位模拟 App，支持自定义路线、配速、抖动 |
| **SportMock Hook** | `dist/SportMockHook.apk` | 是 | LSPosed 模块，隐藏模拟位置标记 |
| **轨迹生成器** | `tools/sportrun_track.py` | 否（PC 用） | 生成 GPX / CSV / GeoJSON 轨迹 |

---

## 快速开始

### 1. 装 SportMock

```bash
adb install -r dist/SportMock.apk
```

手机上：

```
设置 → 关于手机 → 连点「版本号」7 次
→ 开发者选项 → 选择模拟位置信息应用 → 选 SportMock
```

### 2. 填参数

| 字段 | 说明 |
|---|---|
| 自定义路线 | `lat,lon;lat,lon;...`，留空则跑标准 400m 椭圆 |
| 圈数 | 路线模式下 = 跑几圈 |
| 配速 | 秒/公里，如 `360` = 6'00"/km |
| 配速起伏 | `0~1`，默认 `0.35`；`0` = 全程匀速（最容易被判机器人），越大越像真人 |

坐标直接填高德/腾讯地图上取的值（GCJ-02），App 内部自动转 WGS-84 注入。

### 3. 开始

点「开始模拟」→ 切到小程序 → 开始跑步。

---

## 原理

SportMock 通过 `LocationManager.addTestProvider()` 注册测试 provider，向
`gps` / `network` / `fused` / `passive` 四个通道持续注入 1 Hz 的位置点，
并伪造 `speed`、`bearing`、`accuracy`、`altitude`。

轨迹由两点决定：

- **椭圆模式**：标准 400m 田径场（直道 84.39 m × 2 + 弯道半径 36.80 m × 2 = 400.00 m）
- **路线模式**：任意折线闭环，按配速沿弧长推进

叠加 2.5 m 高斯噪声，模拟真实 GPS 抖动。

### 配速模型

真人跑步不会匀速，恒定速度是最明显的机器特征。瞬时速度按

```
v(t) = v_base × (1 + k·(慢漂移 + 随机游走 + 每秒微抖)) × 起跑/收尾/冲刺/停步修正
```

计算，其中 `k` 就是「配速起伏」参数：

- **慢漂移**：两条不同周期的正弦（~150–330 s / ~45–125 s），模拟体力起伏
- **随机游走**：OU 风格回归噪声，几十秒尺度的小幅波动
- **每秒微抖**：`σ ≈ 0.012·k`，GPS 与步伐本身的误差
- **起步**：前 12 s 从 0.35 倍速提到目标配速
- **收尾**：最后 35 m 逐渐减速
- **偶发事件**：随机触发 8–20 s 冲刺（×1.08–1.24）或 1.5–4 s 停步（×0.22，等灯/喘气）

距离按 `s += v·dt` 积分累加，`speed` / `accuracy` 字段随瞬时速度变化，
注入间隔带 ±80 ms 抖动——真实 GPS 回调本来就不是精确 1 Hz。

---

## 已知限制

**微信 / 腾讯地图会拒绝模拟位置。**

腾讯定位 SDK 会读取 `Location.isFromMockProvider()` 和
`Settings.Secure."mock_location"`，一旦识别为模拟数据就丢弃，退化到
WiFi / 基站定位；若网络定位被关闭，则 `wx.getLocation` 直接报错。

高德地图不受影响（使用自家 AMap SDK），但这对目标小程序没有帮助。

**解决办法只有 `SportMockHook`：**

| Hook 点 | 改为 |
|---|---|
| `Location.isFromMockProvider()` | `false` |
| `Location.isMock()` | `false` |
| `Settings.Secure.getString(cr,"mock_location")` | `"0"` |

```bash
adb install -r dist/SportMockHook.apk
```

在 LSPosed 管理器里启用，作用域勾选：

```
com.tencent.mm     (微信)
com.tencent.map    (腾讯地图)
```

**验证顺序：先用腾讯地图当探针**，它认了再开微信。

### 还没做的

| 缺口 | 影响 | 补法 |
|---|---|---|
| 传感器数据 | 计步器（`TYPE_STEP_COUNTER`）读数是 0，加速度计静止 | 在 Hook 里 hook `SystemSensorManager$SensorEventQueue.dispatchSensorEvent`，按配速合成步频波形（需要真机调试） |
| GNSS 卫星状态 | `GnssStatus` 回调没有卫星，精度突然变成 3 m 本身可疑 | hook `GnssStatus` / `getGnssStatus` |
| 基站 / WiFi 扫描 | 网络定位为空 | 需要 root 伪造扫描结果，成本高 |

只查坐标的独立校园跑 App 通常不需要这些；如果它读取计步或加速度，就得补第 1 项。

---

## 构建

不需要 Gradle，也不需要 Android Studio。

```bash
# 依赖：JDK 17、Android SDK（build-tools;34.0.0 + platforms;android-34）
export JAVA_HOME=/path/to/jdk17
export ANDROID_SDK_ROOT=/path/to/android-sdk

python3 android/build.py
```

产物输出到 `dist/`。

也支持 GitHub Actions：推代码即自动构建，APK 在 Actions 的 artifacts 里。

---

## 轨迹生成器

```bash
# 自定义路线，6'00" 配速，输出 GPX
python3 tools/sportrun_track.py \
  --path "28.490785,113.160746;28.490827,113.161532;28.489799,113.161682;28.489772,113.160942" \
  --pace 360 --format gpx --out track.gpx

# 标准 400m 田径场，3.75 圈
python3 tools/sportrun_track.py --center 31.0486 121.1978 --laps 3.75 --pace 390
```

输出格式：`json` / `geojson` / `gpx` / `csv` / `track`

坐标系参数：`--in-coord gcj02|wgs84`、`--out-coord gcj02|wgs84`

---

## 目录结构

```
android/
  app/            SportMock（定位模拟 App）
  hook/           LSPosed 模块
  build.py        跨平台构建脚本
tools/
  sportrun_track.py
data/
  route_points.txt
dist/
  SportMock.apk
  SportMockHook.apk
```

---

## License

MIT
