"""Builds app/src/main/assets/aiunit_bridge.dex: the two AIUnit subclasses the camera process loads
(AiCompositionCloud). The com.oplus.aiunit.core placeholders only satisfy the compiler; the camera
provides the real classes, so only local/jiege/aiunit goes into the dex.

  python build.py
"""
import glob
import os
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ASSET = os.path.join(HERE, "..", "..", "app", "src", "main", "assets", "aiunit_bridge.dex")
SDK = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME") or os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk")
BUILD_TOOLS = sorted(glob.glob(os.path.join(SDK, "build-tools", "*")))[-1]
ANDROID_JAR = sorted(glob.glob(os.path.join(SDK, "platforms", "android-*", "android.jar")))[-1]
D8 = os.path.join(BUILD_TOOLS, "d8.bat" if os.name == "nt" else "d8")

work = tempfile.mkdtemp(prefix="aibridge_")
try:
    classes = os.path.join(work, "classes")
    os.makedirs(classes)
    sources = glob.glob(os.path.join(HERE, "src", "**", "*.java"), recursive=True)
    subprocess.check_call(["javac", "-source", "17", "-target", "17", "-classpath", ANDROID_JAR, "-d", classes] + sources)
    bridge = glob.glob(os.path.join(classes, "local", "jiege", "aiunit", "*.class"))
    out = os.path.join(work, "dex")
    os.makedirs(out)
    subprocess.check_call([D8, "--min-api", "28", "--output", out, "--lib", ANDROID_JAR] + bridge)
    shutil.copyfile(os.path.join(out, "classes.dex"), ASSET)
    print("wrote", os.path.normpath(ASSET), os.path.getsize(ASSET), "bytes")
finally:
    shutil.rmtree(work, ignore_errors=True)
