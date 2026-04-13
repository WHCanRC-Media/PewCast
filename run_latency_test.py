#!/usr/bin/env python3
"""
Automated end-to-end latency test.

Builds the server and Android app, deploys to a connected phone,
runs chirp-based latency tests, and prints results. Zero phone interaction.

Usage:
    python3 run_latency_test.py [--count N] [--device AUDIO_DEVICE] [--test-tone] [--skip-build]

How it works:
    1. Build & install server + Android app
    2. Start server (captures audio from mic, encodes with Opus, streams via UDP)
    3. Launch app normally — it discovers the server via mDNS and sits on ListeningScreen
    4. After 8s settle time, send a second intent with action=latency_test
    5. The app auto-connects, navigates to LatencyTestScreen, and runs N chirp tests
    6. Each test: phone plays a chirp -> air -> server mic -> Opus encode -> UDP ->
       phone decode -> cross-correlation detection -> round-trip latency in ms
    7. Script parses logcat for LATENCY_RESULT / LATENCY_SUMMARY lines

Lessons learned (from whcal-native, applicable here too):
    - OPPO/ColorOS (and likely other OEM Androids) restrict network access for
      app processes immediately after force-stop + am start. The fix: launch the
      app normally first, let it settle, THEN send the automation intent via
      onNewIntent to the already-running process.
    - The activity needs android:launchMode="singleTop" in the manifest for
      onNewIntent to fire when sending intents to an already-running activity.
    - Server stdout/stderr must go to a log file, not subprocess.PIPE. If the
      pipe buffer fills up (64KB on Linux), the server blocks on write and hangs.
"""

import argparse
import os
import re
import signal
import socket
import subprocess
import sys
import time
import urllib.request
import urllib.error
import ssl

PROJECT_DIR = os.path.dirname(os.path.abspath(__file__))
SERVER_BINARY = os.path.join(PROJECT_DIR, "target", "release", "pewcast")
APK_PATH = os.path.join(
    PROJECT_DIR, "android", "app", "build", "outputs", "apk", "debug", "app-debug.apk"
)
PACKAGE = "org.pewcast.pewcast"
ACTIVITY = f"{PACKAGE}/.MainActivity"

# Will be set to ["-s", "<serial>"] if needed, or [] if only one device
ADB_SERIAL_ARGS = []


def adb(*args):
    """Build an adb command with the serial args prepended."""
    return ["adb"] + ADB_SERIAL_ARGS + list(args)


def run(cmd, **kwargs):
    print(f"  $ {' '.join(cmd)}")
    return subprocess.run(cmd, check=True, **kwargs)


def pick_device(serial=None):
    """Pick a device serial, auto-selecting if there are multiple."""
    global ADB_SERIAL_ARGS
    if serial:
        ADB_SERIAL_ARGS = ["-s", serial]
        return serial

    result = subprocess.run(["adb", "devices"], capture_output=True, text=True)
    lines = [l for l in result.stdout.strip().splitlines()[1:] if l.strip() and "device" in l]
    if len(lines) == 0:
        print("ERROR: No adb devices found")
        sys.exit(1)
    if len(lines) == 1:
        return None  # adb will use the only device

    # Multiple devices - prefer USB serial, then adb-TLS, then IP
    serials = [l.split()[0] for l in lines]
    usb = [s for s in serials if "." not in s and ":" not in s and not s.startswith("adb-")]
    tls = [s for s in serials if s.startswith("adb-")]
    chosen = (usb or tls or serials)[0]
    ADB_SERIAL_ARGS = ["-s", chosen]
    print(f"  Multiple devices found, using: {chosen}")
    return chosen


def get_local_ip():
    """Get the local IP address reachable by devices on the LAN."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))
        return s.getsockname()[0]
    finally:
        s.close()


def build_server():
    print("\n=== Building server ===")
    run(["cargo", "build", "--release"], cwd=PROJECT_DIR)


def build_android():
    print("\n=== Building Android app ===")
    cmd = "source android/build_env.sh && cd android && ./gradlew assembleDebug"
    subprocess.run(cmd, shell=True, check=True, cwd=PROJECT_DIR, executable="/bin/bash")


def install_apk():
    print("\n=== Installing APK ===")
    run(adb("install", "-r", APK_PATH))


def wait_for_server(port, timeout=15):
    """Poll the server /status endpoint until it responds."""
    # Server uses self-signed TLS, so we need to skip verification
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    url = f"https://localhost:{port}/status"
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            resp = urllib.request.urlopen(url, timeout=2, context=ctx)
            if resp.status == 200:
                return True
        except (urllib.error.URLError, OSError):
            pass
        time.sleep(0.5)
    return False


def main():
    parser = argparse.ArgumentParser(description="Automated latency test")
    parser.add_argument("--count", type=int, default=10, help="Number of chirp tests to run")
    parser.add_argument("--device", type=str, help="Audio input device name for server")
    parser.add_argument("--test-tone", action="store_true", help="Use 440Hz test tone instead of mic")
    parser.add_argument("--skip-build", action="store_true", help="Skip building server and app")
    parser.add_argument("--port", type=int, default=8080, help="Server port")
    parser.add_argument("--serial", type=str, help="ADB device serial (auto-detected if omitted)")
    args = parser.parse_args()

    pick_device(args.serial)

    if not args.skip_build:
        build_server()
        build_android()
    install_apk()

    # Start server
    print("\n=== Starting server ===")
    server_cmd = [SERVER_BINARY]
    if args.test_tone:
        server_cmd.append("--test-tone")
    if args.device:
        server_cmd.extend(["--device", args.device])
    server_log = open(os.path.join(PROJECT_DIR, "server_test.log"), "w")
    server_proc = subprocess.Popen(
        server_cmd,
        cwd=PROJECT_DIR,
        stdout=server_log,
        stderr=server_log,
    )

    try:
        print(f"  Waiting for server on port {args.port}...")
        if not wait_for_server(args.port):
            print("ERROR: Server failed to start within 15s")
            print(f"  Check server_test.log for details")
            server_proc.terminate()
            sys.exit(1)
        print("  Server is ready.")

        local_ip = get_local_ip()
        server_address = f"{local_ip}:{args.port}"
        print(f"  Server LAN address: {server_address}")
        print("  App will discover server via mDNS")

        # Two-phase launch: we MUST launch the app normally first because
        # OPPO/ColorOS throttles network access for freshly-started app processes.
        # Phase 1: normal launch — app discovers server via mDNS, gets network access.
        # Phase 2: send latency_test intent — app navigates to test screen and runs tests.
        print("\n=== Launching app ===")
        subprocess.run(adb("shell", "am", "force-stop", PACKAGE),
                       capture_output=True)
        run(adb("shell", "am", "start", "-n", ACTIVITY))
        print("  Waiting for app to settle and discover server via mDNS...")
        time.sleep(8)

        # Now send the latency test intent to the running app
        print("\n=== Starting latency test ===")
        subprocess.run(adb("logcat", "-c"), capture_output=True)
        run(adb(
            "shell", "am", "start",
            "-n", ACTIVITY,
            "--es", "action", "latency_test",
            "--ei", "count", str(args.count),
            "--es", "server_address", server_address,
        ))

        # Monitor logcat for results
        print(f"\n=== Running {args.count} latency tests ===")
        logcat_proc = subprocess.Popen(
            adb("logcat", "-s", "LatencyTestScreen:I"),
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            text=True,
        )

        results = []
        timeouts = 0
        test_num = 0
        deadline = time.time() + (args.count * 10) + 30  # generous timeout

        try:
            for line in logcat_proc.stdout:
                line = line.strip()

                # Individual test result
                m = re.search(r"LATENCY_RESULT: (\d+)ms", line)
                if m:
                    test_num += 1
                    ms = int(m.group(1))
                    results.append(ms)
                    print(f"  Test {test_num:3d}: {ms} ms")
                    continue

                m = re.search(r"LATENCY_RESULT: timeout", line)
                if m:
                    test_num += 1
                    timeouts += 1
                    print(f"  Test {test_num:3d}: TIMEOUT")
                    continue

                # Summary line = we're done
                if "LATENCY_SUMMARY:" in line:
                    print(f"\n  {line.split('LATENCY_SUMMARY:')[1].strip()}")
                    break

                # Done signal (after report sent)
                if "LATENCY_DONE" in line:
                    break

                if time.time() > deadline:
                    print("\nERROR: Overall timeout waiting for test results")
                    break
        finally:
            logcat_proc.terminate()

        # Print our own summary
        print("\n=== Results ===")
        if results:
            avg = sum(results) / len(results)
            mn = min(results)
            mx = max(results)
            sorted_results = sorted(results)
            p50 = sorted_results[len(sorted_results) // 2]
            print(f"  Tests:    {len(results)} successful, {timeouts} timeouts")
            print(f"  Min:      {mn} ms")
            print(f"  p50:      {p50} ms")
            print(f"  Average:  {avg:.1f} ms")
            print(f"  Max:      {mx} ms")
        else:
            print(f"  No successful tests ({timeouts} timeouts)")

    finally:
        # Cleanup
        print("\n=== Cleanup ===")
        subprocess.run(adb("shell", "am", "force-stop", PACKAGE),
                       capture_output=True)
        server_proc.terminate()
        try:
            server_proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            server_proc.kill()
        print("  Done.")


if __name__ == "__main__":
    main()
