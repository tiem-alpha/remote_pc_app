#!/usr/bin/env python3
"""Build and run the clipboard protocol tests on an attached arm64 Android device.
Run after ./gradlew :app:assembleDebug. Optional first argument: adb serial.
"""
import json
import os
from pathlib import Path
import shlex
import subprocess
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[2]
CORE = ROOT / "third_party/freerdp/client/Android/Studio/freeRDPCore"
databases = sorted(CORE.glob(".cxx/Debug/*/arm64-v8a/compile_commands.json"),
                   key=lambda p: p.stat().st_mtime, reverse=True)
entry = next(entry for db in databases for entry in json.loads(db.read_text())
             if entry["file"].endswith("remotePcCpp/android_cliprdr.c"))
flags = shlex.split(entry["command"])
for option in ("-o", "-c"):
    index = flags.index(option)
    del flags[index:index + 2]
adb = ["adb"] + (["-s", sys.argv[1]] if len(sys.argv) > 1 else [])
with tempfile.TemporaryDirectory(prefix="remote_pc_clipboard_test_") as temp:
    folder = Path(temp)
    with zipfile.ZipFile(ROOT / "app/build/outputs/apk/debug/app-debug.apk") as apk:
        for member in apk.namelist():
            if member.startswith("lib/arm64-v8a/") and member.endswith(".so"):
                (folder / Path(member).name).write_bytes(apk.read(member))
    binary = folder / "libclipboard_test.so"
    subprocess.run(flags + ["-I" + str(Path(entry["file"]).parent),
        str(ROOT / "tests/native/clipboard_send_test.c"), "-fPIC", "-shared",
        "-Wl,--gc-sections", "-L" + temp, "-Wl,-rpath-link," + temp,
        "-lfreerdp3", "-lwinpr3", "-o", str(binary)], check=True)
    subprocess.run(["javac", "--release", "11", "-d", temp,
                    str(ROOT / "tests/native/ClipboardProtocolTest.java")], check=True)
    sdk = Path(os.environ.get("ANDROID_HOME") or os.environ["ANDROID_SDK_ROOT"])
    subprocess.run([str(sdk / "build-tools/36.0.0/d8"), "--output", str(folder / "test.jar"),
                    str(folder / "ClipboardProtocolTest.class")], check=True)
    remote = "/data/local/tmp/" + folder.name
    subprocess.run(adb + ["push", str(folder), remote], check=True, stdout=subprocess.DEVNULL)
    try:
        subprocess.run(adb + ["shell", "LD_LIBRARY_PATH=" + shlex.quote(remote) + " "
                        + "CLASSPATH=" + shlex.quote(remote + "/test.jar")
                        + " app_process /system/bin ClipboardProtocolTest "
                        + shlex.quote(remote)], check=True)
    finally:
        subprocess.run(adb + ["shell", "rm", "-rf", remote], check=True)
