#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
SportRun 田径场轨迹生成器
────────────────────────────────────────────────────────
生成一条在 400m 标准田径场内、按真实配速、带 GPS 噪声的轨迹。
用途：
  1) 定位注入（Fake Location / LSPosed 模块）时按时间回放；
  2) 协议层重放时作为 track 字段的坐标源。

用法：
  python sportrun_track.py --center 31.048600 121.197800 --laps 3.75 --pace 390
  python sportrun_track.py --center 31.0486 121.1978 --laps 3.75 --pace 390 \
      --ckpt 31.0487,121.1976 31.0485,121.1980 31.0484,121.1977 31.0488,121.1979 \
      --format geojson --out track.geojson

参数：
  --center  lat lon     田径场几何中心（高德/腾讯地图长按取点）
  --laps    圈数        1.5km≈3.75，1.6km=4.0，2km=5.0
  --pace    秒/公里     男生 360-480，女生 400-520（务必落在学校区间内）
  --heading 旋转角      跑道长轴相对正北的角度（度），默认 0
  --ckpt    打卡点      lat,lon;lat,lon;... 用空格分隔，用来校验轨迹是否覆盖
  --in-coord  输入坐标系 gcj02(腾讯/高德地图取点, 默认) | wgs84(原始GPS)
  --out-coord 输出坐标系 wgs84(喂给系统层定位模拟, 默认) | gcj02(喂给微信JS层/上报)

【坐标系是这条路线最容易翻车的点】
  腾讯地图/高德地图上量出来的点是 GCJ-02（火星坐标）。
  系统层定位模拟器（开发者选项 / Fake Location / LSPosed hook）接受的是 WGS-84。
  微信 wx.getLocation 会自己再做一次 WGS-84 → GCJ-02。
  直接把地图上的 GCJ-02 喂给系统层，等于转两次，上海一带偏 400-600m，
  整条轨迹会整个飘出田径场。所以默认 --in-coord gcj02 --out-coord wgs84。
"""
import argparse
import datetime
import json
import math
import random

S = 84.39   # 直道长度 m
R = 36.80   # 弯道半径 m（测量线：2S + 2πR = 400.0 m，标准 IAAF 400m 跑道）


def oval_xy(s):
    """沿跑道累计弧长 s(m) -> 局部坐标 (x 向东, y 向北)，逆时针。"""
    lap = s % 400.0
    if lap < S:                                    # 下直道：西 -> 东
        return (-S / 2 + lap, -R)
    lap -= S
    if lap < math.pi * R:                          # 右弯道：-90° -> +90°
        a = -math.pi / 2 + lap / R
        return (S / 2 + R * math.cos(a), R * math.sin(a))
    lap -= math.pi * R
    if lap < S:                                    # 上直道：东 -> 西
        return (S / 2 - lap, R)
    lap -= S
    a = math.pi / 2 + lap / R                      # 左弯道：+90° -> +270°
    return (-S / 2 + R * math.cos(a), R * math.sin(a))


def haversine(lat1, lon1, lat2, lon2):
    r = 6371000.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp = p2 - p1
    dl = math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(a))


# ── GCJ-02 / WGS-84 互转 ────────────────────────────────────────────────
_PI = math.pi
_A = 6378245.0
_EE = 0.00669342162296594323


def _out_of_china(lat, lon):
    return not (73.66 < lon < 135.05 and 3.86 < lat < 53.55)


def _tf_lat(x, y):
    ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y
    ret += 0.2 * math.sqrt(abs(x))
    ret += (20.0 * math.sin(6.0 * x * _PI) + 20.0 * math.sin(2.0 * x * _PI)) * 2.0 / 3.0
    ret += (20.0 * math.sin(y * _PI) + 40.0 * math.sin(y / 3.0 * _PI)) * 2.0 / 3.0
    ret += (160.0 * math.sin(y / 12.0 * _PI) + 320 * math.sin(y * _PI / 30.0)) * 2.0 / 3.0
    return ret


def _tf_lon(x, y):
    ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * math.sqrt(abs(x))
    ret += (20.0 * math.sin(6.0 * x * _PI) + 20.0 * math.sin(2.0 * x * _PI)) * 2.0 / 3.0
    ret += (20.0 * math.sin(x * _PI) + 40.0 * math.sin(x / 3.0 * _PI)) * 2.0 / 3.0
    ret += (150.0 * math.sin(x / 12.0 * _PI) + 300.0 * math.sin(x / 30.0 * _PI)) * 2.0 / 3.0
    return ret


def wgs84_to_gcj02(lat, lon):
    if _out_of_china(lat, lon):
        return lat, lon
    dlat = _tf_lat(lon - 105.0, lat - 35.0)
    dlon = _tf_lon(lon - 105.0, lat - 35.0)
    radlat = lat / 180.0 * _PI
    magic = math.sin(radlat)
    magic = 1 - _EE * magic * magic
    sqrtmagic = math.sqrt(magic)
    dlat = (dlat * 180.0) / ((_A * (1 - _EE)) / (magic * sqrtmagic) * _PI)
    dlon = (dlon * 180.0) / (_A / sqrtmagic * math.cos(radlat) * _PI)
    return lat + dlat, lon + dlon


def gcj02_to_wgs84(lat, lon):
    """迭代反解，精度 < 1e-9 度（约 0.1 mm）。"""
    if _out_of_china(lat, lon):
        return lat, lon
    wlat, wlon = lat, lon
    for _ in range(12):
        glat, glon = wgs84_to_gcj02(wlat, wlon)
        dlat, dlon = glat - lat, glon - lon
        wlat -= dlat
        wlon -= dlon
        if abs(dlat) < 1e-10 and abs(dlon) < 1e-10:
            break
    return wlat, wlon


def gen(center, laps, pace, heading=0.0, step=12.0, jitter=2.5, seed=None,
        start_offset=0.0):
    """返回 [(lat, lon, t_sec, v_ms)]。step: 相邻采样点间距 m；jitter: GPS 噪声 m。"""
    rnd = random.Random(seed)
    lat0, lon0 = center
    th = math.radians(heading)
    mlat = 111320.0
    mlon = 111320.0 * math.cos(math.radians(lat0))
    total = laps * 400.0
    n = max(1, int(total / step))
    pts = []
    t = 0.0
    for i in range(n + 1):
        s = start_offset + total * i / n
        x, y = oval_xy(s)
        # 旋转跑道朝向
        xr = x * math.cos(th) - y * math.sin(th)
        yr = x * math.sin(th) + y * math.cos(th)
        # 真实 GPS 抖动（手机跑步 App 实测典型 2-4 m，这里默认 2.5 m）
        xr += rnd.gauss(0, jitter)
        yr += rnd.gauss(0, jitter)
        pts.append((
            round(lat0 + yr / mlat, 6),
            round(lon0 + xr / mlon, 6),
            round(t, 3),
            round(1000.0 / pace, 3),
        ))
        t += (total / n) / 1000.0 * pace
    return pts, total, t


def check_ckpt(pts, ckpts, radius=25.0):
    """检查轨迹是否每个打卡点都进过 radius 米。返回 [(idx, 最近距离)]。"""
    out = []
    for i, (la, lo) in enumerate(ckpts):
        d = min(haversine(la, lo, p[0], p[1]) for p in pts)
        out.append((i + 1, round(d, 1), d <= radius))
    return out


def resample_polyline(poly, step=5.0, closed=True):
    """把路点折线按 step 米等距重采样，返回 [(lat, lon)]。"""
    segs = list(zip(poly, poly[1:]))
    if closed:
        segs.append((poly[-1], poly[0]))
    out = []
    for (la1, lo1), (la2, lo2) in segs:
        d = haversine(la1, lo1, la2, lo2)
        n = max(1, int(d / step))
        for k in range(n):
            f = k / n
            out.append((la1 + (la2 - la1) * f, lo1 + (lo2 - lo1) * f))
    if not out:
        out = list(poly)
    return out


def gen_from_path(poly, pace, jitter=2.5, step=5.0, closed=True, seed=None):
    """沿给定路点跑，返回 ([(lat,lon,t,v)], 总里程m, 总时长s)。"""
    rnd = random.Random(seed)
    base = resample_polyline(poly, step, closed)
    pts, t, dist = [], 0.0, 0.0
    prev = None
    for (la, lo) in base:
        if prev is not None:
            seg = haversine(prev[0], prev[1], la, lo)
            dist += seg
            t += seg / 1000.0 * pace
        prev = (la, lo)
        jla = la + rnd.gauss(0, jitter) / 111320.0
        jlo = lo + rnd.gauss(0, jitter) / (111320.0 * math.cos(math.radians(la)))
        pts.append((round(jla, 6), round(jlo, 6), round(t, 3), round(1000.0 / pace, 3)))
    if closed and len(pts) > 1:
        seg = haversine(prev[0], prev[1], base[0][0], base[0][1])
        dist += seg
        t += seg / 1000.0 * pace
    return pts, dist, t


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--center", nargs=2, type=float, default=None,
                    metavar=("LAT", "LON"), help="田径场中心坐标（椭圆模式）")
    ap.add_argument("--path", default=None,
                    help="校园路线模式：路点 'lat,lon;lat,lon;...'，按顺序连成闭环")
    ap.add_argument("--open", action="store_true", help="路点不闭环（默认闭环）")
    ap.add_argument("--step", type=float, default=5.0, help="采样间距 m")
    ap.add_argument("--laps", type=float, default=3.75)
    ap.add_argument("--pace", type=float, default=390.0, help="秒/公里")
    ap.add_argument("--heading", type=float, default=0.0)
    ap.add_argument("--ckpt", nargs="*", default=[])
    ap.add_argument("--radius", type=float, default=25.0, help="打卡点判定半径 m")
    ap.add_argument("--in-coord", choices=["gcj02", "wgs84"], default="gcj02",
                    help="输入坐标系：地图取点用 gcj02(默认)，原始GPS用 wgs84")
    ap.add_argument("--out-coord", choices=["gcj02", "wgs84"], default="wgs84",
                    help="输出坐标系：系统层注入用 wgs84(默认)，微信JS层/上报用 gcj02")
    ap.add_argument("--format", choices=["json", "geojson", "track", "gpx", "csv"], default="json")
    ap.add_argument("--out", default="track.json")
    ap.add_argument("--seed", type=int, default=None)
    a = ap.parse_args()

    def to_wgs(lat, lon):
        return gcj02_to_wgs84(lat, lon) if a.in_coord == "gcj02" else (lat, lon)

    def from_wgs(lat, lon):
        return wgs84_to_gcj02(lat, lon) if a.out_coord == "gcj02" else (lat, lon)

    ckpts = [to_wgs(*[float(v) for v in c.split(",")]) for c in a.ckpt]

    if a.path:
        poly = [to_wgs(*[float(v) for v in p.split(",")])
                for p in a.path.replace(" ", "").split(";") if p]
        pts, total, dur = gen_from_path(poly, a.pace, step=a.step,
                                        closed=not a.open, seed=a.seed)
    else:
        if not a.center:
            ap.error("要么给 --center（椭圆模式），要么给 --path（路线模式）")
        center = to_wgs(a.center[0], a.center[1])
        pts, total, dur = gen(center, a.laps, a.pace, a.heading, seed=a.seed)

    if a.in_coord != a.out_coord:
        shifted = []
        for la, lo, t, v in pts:
            gla, glo = from_wgs(la, lo)
            shifted.append((round(gla, 6), round(glo, 6), t, v))
        pts = shifted

    print(f"里程 {total/1000:.2f} km | 时长 {int(dur)//60}:{int(dur)%60:02d} "
          f"| 配速 {int(a.pace)//60}'{int(a.pace)%60:02d}\"/km | 采样点 {len(pts)}")
    print(f"坐标系: 输入 {a.in_coord} -> 输出 {a.out_coord}")

    if ckpts:
        print("打卡点覆盖检查（半径 %.0f m）:" % a.radius)
        ok = True
        for i, d, hit in check_ckpt(pts, ckpts, a.radius):
            print(f"  #{i}: 最近 {d} m -> {'OK' if hit else '未覆盖!'}")
            ok &= hit
        if not ok:
            print("  [!] 有打卡点未覆盖：调 --heading / --radius，或把 --center 对准打卡点几何中心")

    if a.format == "gpx":
        t0 = datetime.datetime(2026, 1, 1, tzinfo=datetime.timezone.utc)
        head = ['<?xml version="1.0" encoding="UTF-8"?>',
                '<gpx version="1.1" creator="sportrun_track" '
                'xmlns="http://www.topografix.com/GPX/1/1">',
                '<trk><name>SportRun</name><trkseg>']
        body = []
        for la, lo, t, v in pts:
            ts = (t0 + datetime.timedelta(seconds=t)).strftime("%Y-%m-%dT%H:%M:%SZ")
            body.append(f'<trkpt lat="{la}" lon="{lo}"><time>{ts}</time></trkpt>')
        with open(a.out, "w", encoding="utf-8") as f:
            f.write("\n".join(head + body + ['</trkseg></trk></gpx>']))
        print("已写入", a.out, "（1x 速度回放，总时长 %d 秒）" % round(dur))
        return

    if a.format == "csv":
        # 有些模拟器只吃纯坐标表：lat,lon[,elapsed]
        with open(a.out, "w", encoding="utf-8", newline="") as f:
            f.write("lat,lon,elapsed_s,speed_mps\n")
            for la, lo, t, v in pts:
                f.write(f"{la},{lo},{t:.1f},{v}\n")
        print("已写入", a.out, "（lat,lon + 相对秒数）")
        return

    if a.format == "geojson":
        data = {
            "type": "FeatureCollection",
            "features": [{
                "type": "Feature",
                "properties": {"pace_sec_per_km": a.pace, "laps": a.laps},
                "geometry": {
                    "type": "LineString",
                    "coordinates": [[p[1], p[0]] for p in pts],
                },
            }],
        }
    elif a.format == "track":
        # 常见小程序上报格式（字段名按抓包结果改）
        data = [{"lat": p[0], "lng": p[1], "timestamp": int(p[2] * 1000),
                 "speed": p[3]} for p in pts]
    else:
        data = [{"lat": p[0], "lon": p[1], "t": p[2], "v": p[3]} for p in pts]

    with open(a.out, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=1)
    print("已写入", a.out)


if __name__ == "__main__":
    main()
