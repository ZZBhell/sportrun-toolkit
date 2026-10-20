#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Build SportMock.apk (mock-location app) and SportMockHook.apk (LSPosed module)
without Gradle and without Android Studio.

Requirements
------------
  JAVA_HOME        -> JDK 17+
  ANDROID_SDK_ROOT -> Android SDK containing
                      build-tools;34.x  and  platforms;android-34

Usage
-----
  python android/build.py

Output
------
  dist/SportMock.apk
  dist/SportMockHook.apk
"""
import os
import shutil
import subprocess
import sys
import urllib.request
import ssl
import zipfile

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

IS_WIN = os.name == "nt"
EXE = ".exe" if IS_WIN else ""
BAT = ".bat" if IS_WIN else ""

ANDROID = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(ANDROID)
DIST = os.path.join(REPO, "dist")
WORK = os.path.join(ANDROID, ".work")

SDK = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
if not SDK or not os.path.isdir(SDK):
    sys.exit("ANDROID_SDK_ROOT / ANDROID_HOME is not set")

JAVA_HOME = os.environ.get("JAVA_HOME", "")


def tool(name, *dirs):
    for d in dirs:
        p = os.path.join(d, name)
        if os.path.isfile(p):
            return p
    sys.exit("cannot find " + name)


def find_build_tools():
    root = os.path.join(SDK, "build-tools")
    if not os.path.isdir(root):
        sys.exit("no build-tools in SDK")
    want = os.environ.get("BUILD_TOOLS_VERSION")
    names = sorted(os.listdir(root))
    if want:
        return os.path.join(root, want)
    pref = [n for n in names if n.startswith("34")]
    if not pref:
        sys.exit("build-tools 34.x not found, install it: sdkmanager 'build-tools;34.0.0'")
    return os.path.join(root, pref[-1])


def find_android_jar():
    root = os.path.join(SDK, "platforms")
    if not os.path.isdir(root):
        sys.exit("no platforms in SDK")
    for n in sorted(os.listdir(root), reverse=True):
        if n.startswith("android-34"):
            p = os.path.join(root, n, "android.jar")
            if os.path.isfile(p):
                return p
    sys.exit("platform android-34 not found: sdkmanager 'platforms;android-34'")


BT = find_build_tools()
ANDROID_JAR = find_android_jar()

AAPT2 = tool("aapt2" + EXE, BT)
ZIPALIGN = tool("zipalign" + EXE, BT)
D8 = tool("d8" + BAT, BT)
APKSIGNER = tool("apksigner" + BAT, BT)

JAVAC = os.path.join(JAVA_HOME, "bin", "javac" + EXE) if JAVA_HOME else "javac" + EXE
KEYTOOL = os.path.join(JAVA_HOME, "bin", "keytool" + EXE) if JAVA_HOME else "keytool" + EXE

ENV = dict(os.environ)
if JAVA_HOME:
    ENV["JAVA_HOME"] = JAVA_HOME
    ENV["PATH"] = os.path.join(JAVA_HOME, "bin") + os.pathsep + ENV.get("PATH", "")

KS = os.path.join(WORK, "debug.keystore")
API_JAR = os.path.join(WORK, "api-82.jar")
API_URLS = [
    "https://maven.aliyun.com/repository/public/de/robv/android/xposed/api/82/api-82.jar",
    "https://repo1.maven.org/maven2/de/robv/android/xposed/api/82/api-82.jar",
]


def run(cmd):
    print("$", os.path.basename(cmd[0]), " ".join(cmd[1:])[:140], flush=True)
    p = subprocess.run(cmd, env=ENV, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    out = p.stdout.decode("utf-8", "replace")
    if p.returncode != 0:
        print(out[-4000:], flush=True)
        sys.exit(p.returncode)
    return out


def ensure_keystore():
    if os.path.isfile(KS):
        return
    run([KEYTOOL, "-genkeypair", "-keystore", KS,
         "-storepass", "android", "-keypass", "android",
         "-alias", "androiddebugkey", "-keyalg", "RSA",
         "-keysize", "2048", "-validity", "10000",
         "-dname", "CN=Android Debug,O=Android,C=US"])


def ensure_api_jar():
    if os.path.isfile(API_JAR):
        return
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    for url in API_URLS:
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
            data = urllib.request.urlopen(req, timeout=30, context=ctx).read()
            if len(data) > 5000:
                with open(API_JAR, "wb") as f:
                    f.write(data)
                print("downloaded", os.path.basename(API_JAR), len(data), "bytes")
                return
        except Exception as e:
            print("api jar:", url, e)
    sys.exit("cannot download Xposed api-82.jar; put it at " + API_JAR)


def compile_module(manifest, assets, src, out_apk, version, api_jar=None):
    work = os.path.join(WORK, os.path.splitext(os.path.basename(out_apk))[0])
    if os.path.isdir(work):
        shutil.rmtree(work, ignore_errors=True)
    os.makedirs(work)

    base = os.path.join(work, "base.apk")
    cmd = [AAPT2, "link", "-o", base, "-I", ANDROID_JAR, "--manifest", manifest,
           "--min-sdk-version", "26", "--target-sdk-version", "34",
           "--version-code", "1", "--version-name", version]
    if assets and os.path.isdir(assets):
        cmd += ["-A", assets]
    run(cmd)

    classes = os.path.join(work, "classes")
    os.makedirs(classes)
    jfiles = []
    for r, _d, fs in os.walk(src):
        jfiles += [os.path.join(r, f) for f in fs if f.endswith(".java")]
    jcmd = [JAVAC, "-source", "8", "-target", "8", "-encoding", "UTF-8",
            "-bootclasspath", ANDROID_JAR, "-nowarn", "-d", classes]
    if api_jar:
        jcmd += ["-cp", api_jar]
    run(jcmd + jfiles)

    dexout = os.path.join(work, "dex")
    os.makedirs(dexout)
    cls = []
    for r, _d, fs in os.walk(classes):
        cls += [os.path.join(r, f) for f in fs if f.endswith(".class")]
    dcmd = [D8, "--lib", ANDROID_JAR, "--min-api", "26", "--output", dexout]
    if api_jar:
        dcmd += ["--classpath", api_jar]
    run(dcmd + cls)

    packed = os.path.join(work, "packed.apk")
    shutil.copyfile(base, packed)
    with zipfile.ZipFile(packed, "a", zipfile.ZIP_DEFLATED) as z:
        z.write(os.path.join(dexout, "classes.dex"), "classes.dex")

    aligned = os.path.join(work, "aligned.apk")
    run([ZIPALIGN, "-f", "-p", "4", packed, aligned])

    if os.path.isfile(out_apk):
        os.remove(out_apk)
    run([APKSIGNER, "sign", "--ks", KS,
         "--ks-pass", "pass:android", "--key-pass", "pass:android",
         "--ks-key-alias", "androiddebugkey", "--out", out_apk, aligned])
    print("built", out_apk, os.path.getsize(out_apk), "bytes\n")


def main():
    os.makedirs(DIST, exist_ok=True)
    os.makedirs(WORK, exist_ok=True)
    ensure_keystore()

    compile_module(
        manifest=os.path.join(ANDROID, "app", "AndroidManifest.xml"),
        assets=None,
        src=os.path.join(ANDROID, "app", "src"),
        out_apk=os.path.join(DIST, "SportMock.apk"),
        version="1.4",
    )

    ensure_api_jar()
    compile_module(
        manifest=os.path.join(ANDROID, "hook", "AndroidManifest.xml"),
        assets=os.path.join(ANDROID, "hook", "assets"),
        src=os.path.join(ANDROID, "hook", "src"),
        out_apk=os.path.join(DIST, "SportMockHook.apk"),
        version="1.0",
        api_jar=API_JAR,
    )


if __name__ == "__main__":
    main()
