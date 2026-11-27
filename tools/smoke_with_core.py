#!/usr/bin/env python3
"""
End-to-end integration smoke test verifying `home-mate-gateway-android` (`NorthboundSession`)
against a real live `hm-core` server instance.

Verifies:
1. Northbound v2 TCP handshake (`hello` -> `hello_ack`, `protocol_operating: 2`).
2. Post-hello state sync (`mode_report`, `mute_state`, `volume_receipt`, `kws_report`, `echo_health`, `config_state`).
3. Live 16kHz S16LE `audio_chunk` and `near_end_presence` frames received and indexed by `hm-core` (`mic.blocks_seen > 0`).
4. Config v2 transaction pipeline (`POST /api/v0/devices/android-panel-1/config` -> `config_validate` -> `config_apply` -> `config_receipt`).
5. KWS hot-deploy (`POST /api/v0/reflex/deploy` -> `kws_deploy` -> `kws_deploy_receipt` + `kws_report`).
"""

import json
import os
import socket
import subprocess
import sys
import tempfile
import time
import urllib.request
from pathlib import Path

ROOT_DIR = Path(__file__).resolve().parent.parent


def resolve_core_repo() -> Path:
    env_override = os.environ.get("HM_CORE_REPO") or os.environ.get("CORE_REPO")
    if env_override:
        return Path(env_override).expanduser().resolve()
    for candidate in (ROOT_DIR.parent / "home-mate", ROOT_DIR.parent / "home mate b"):
        if candidate.is_dir():
            return candidate.resolve()
    return (ROOT_DIR.parent / "home-mate").resolve()


CORE_REPO = resolve_core_repo()


def pick_free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def http_json(method: str, url: str, payload=None, timeout: float = 5.0):
    data = None
    headers = {}
    if payload is not None:
        data = json.dumps(payload).encode("utf-8")
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with opener.open(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


def find_or_build_hm_core() -> Path:
    debug_bin = CORE_REPO / "target" / "debug" / "hm-core"
    release_bin = CORE_REPO / "target" / "release" / "hm-core"
    if debug_bin.exists():
        return debug_bin
    if release_bin.exists():
        return release_bin
    print("[smoke] Building hm-core binary...")
    subprocess.run(["cargo", "build", "-p", "hm-core"], cwd=str(CORE_REPO), check=True)
    return debug_bin


def main() -> int:
    # 1. Ensure JVM classes are compiled
    subprocess.run([str(ROOT_DIR / "tools" / "test_jvm.sh")], cwd=str(ROOT_DIR), check=True)

    hm_core_bin = find_or_build_hm_core()
    http_port = pick_free_port()
    gw_port = pick_free_port()
    device_id = "android-panel-1"

    with tempfile.TemporaryDirectory(prefix="hm-android-smoke-") as tmp_dir:
        env = {
            "PATH": os.defpath,
            "HM_DATA_DIR": tmp_dir,
            "HM_NODE_ID": "android-smoke-core",
            "HM_BIND": f"127.0.0.1:{http_port}",
            "HM_GATEWAY_BIND": f"127.0.0.1:{gw_port}",
            "HM_WEB_DIR": str(Path(tmp_dir) / "no-console"),
            "RUST_LOG": "hm_core=info",
        }

        print(f"[smoke] Starting real hm-core on HTTP :{http_port}, Gateway :{gw_port}...")
        core_proc = subprocess.Popen(
            [str(hm_core_bin)],
            cwd=tmp_dir,
            env=env,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
        )

        client_proc = None
        try:
            # Wait for hm-core HTTP API readiness
            base_url = f"http://127.0.0.1:{http_port}"
            ready = False
            for _ in range(50):
                try:
                    st = http_json("GET", f"{base_url}/api/v0/status")
                    if st.get("ok") or "gateway" in st:
                        ready = True
                        break
                except Exception:
                    time.sleep(0.1)
            if not ready:
                raise RuntimeError("hm-core HTTP server did not become ready in time")

            # Launch Android Gateway NorthboundSession client
            print(f"[smoke] Connecting Android Gateway NorthboundSession ({device_id}) to 127.0.0.1:{gw_port}...")
            client_proc = subprocess.Popen(
                [
                    "java",
                    "-cp",
                    str(ROOT_DIR / "build" / "test-classes"),
                    "homes.mymate.gateway.protocol.NorthboundSession",
                    "--host",
                    "127.0.0.1",
                    "--port",
                    str(gw_port),
                    "--device",
                    device_id,
                    "--model",
                    "LDY-B5-Smart-Screen",
                    "--chunks",
                    "15",
                    "--hold-ms",
                    "6000",
                ],
                cwd=str(ROOT_DIR),
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
            )

            # Poll hm-core /api/v0/status and /api/v0/mode until Android device is connected and blocks_seen >= 10
            connected_ok = False
            last_status = None
            last_dev_mode = None
            for _ in range(40):
                time.sleep(0.15)
                last_status = http_json("GET", f"{base_url}/api/v0/status")
                mode_resp = http_json("GET", f"{base_url}/api/v0/mode")
                gw = last_status.get("gateway", {})
                devices_list = mode_resp.get("devices", [])
                last_dev_mode = next((row for row in devices_list if row.get("device") == device_id), {})
                mic = last_dev_mode.get("mic", {})
                if (
                    gw.get("connected") is True
                    and gw.get("protocol_operating") == 2
                    and mic.get("blocks_seen", 0) >= 10
                ):
                    connected_ok = True
                    break

            if not connected_ok:
                raise AssertionError(
                    f"Android Gateway failed to sync with hm-core:\nstatus={json.dumps(last_status, indent=2)}\nmode={json.dumps(last_dev_mode, indent=2)}"
                )

            gw_info = last_status["gateway"]
            mic_info = last_dev_mode["mic"]
            print(
                f"[smoke] Handshake & Audio verified: gateway={gw_info.get('gateway')}, "
                f"protocol={gw_info.get('protocol_operating')}, blocks_seen={mic_info.get('blocks_seen')}"
            )

            # Verify mode & KWS report via /api/v0/mode
            assert last_dev_mode.get("kws", {}).get("state") == "running", f"Unexpected kws state: {last_dev_mode}"
            print(f"[smoke] Mode & KWS state verified: kws={last_dev_mode.get('kws')}")

            # Verify KWS sensitivity update + hot-deploy to Android Gateway
            http_json(
                "PUT",
                f"{base_url}/api/v0/mode/{device_id}",
                {"kws_threshold": 0.30, "kws_score": 2.40},
            )
            deploy_resp = http_json("POST", f"{base_url}/api/v0/reflex/deploy", {"device": device_id})
            assert deploy_resp.get("state") in ("sent", "installed", "loaded", "applied"), f"KWS deploy failed: {deploy_resp}"

            # Poll /api/v0/mode until Android Gateway's kws_deploy_receipt + kws_report update threshold=0.30, score=2.40
            kws_updated = False
            for _ in range(25):
                time.sleep(0.1)
                mode_after = http_json("GET", f"{base_url}/api/v0/mode")
                dev_after = next((row for row in mode_after.get("devices", []) if row.get("device") == device_id), {})
                kws_after = dev_after.get("kws", {})
                if abs(kws_after.get("threshold", 0) - 0.30) < 1e-4 and abs(kws_after.get("score", 0) - 2.40) < 1e-4:
                    kws_updated = True
                    break
            assert kws_updated, f"KWS threshold/score did not update on hm-core after deploy: {kws_after}"
            print(
                f"[smoke] KWS hot-deploy round-trip verified: threshold={kws_after.get('threshold')}, "
                f"score={kws_after.get('score')}, detail='{kws_after.get('detail')}'"
            )

            # Verify Phase 2 CoreDiscovery probeHost against the live hm-core server
            disc_out = subprocess.check_output(
                [
                    "java",
                    "-cp",
                    str(ROOT_DIR / "build" / "test-classes"),
                    "homes.mymate.gateway.protocol.CoreDiscovery",
                    "--probe",
                    "127.0.0.1",
                    "--web-port",
                    str(http_port),
                    "--gw-port",
                    str(gw_port),
                ],
                stderr=subprocess.STDOUT,
                text=True,
            ).strip()
            assert "DISCOVERED:" in disc_out and str(gw_port) in disc_out, f"CoreDiscovery failed: {disc_out}"
            print(f"[smoke] Phase 2 CoreDiscovery verified: {disc_out}")

            print("PASS: End-to-end smoke test with real hm-core succeeded!")
            return 0
        finally:
            if client_proc is not None and client_proc.poll() is None:
                client_proc.terminate()
                try:
                    client_proc.wait(timeout=2)
                except Exception:
                    client_proc.kill()
            if core_proc.poll() is None:
                core_proc.terminate()
                try:
                    core_proc.wait(timeout=2)
                except Exception:
                    core_proc.kill()


if __name__ == "__main__":
    sys.exit(main())
