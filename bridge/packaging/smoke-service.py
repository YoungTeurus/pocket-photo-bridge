#!/usr/bin/env python3
"""Build-machine smoke: extracted launcher, no system Java, real authenticated HTTP."""
import http.cookiejar
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request


def require(condition, reason):
    if not condition:
        raise RuntimeError(reason)


def main():
    require(len(sys.argv) == 3, "launcher_and_state_required")
    launcher = str(Path(sys.argv[1]).resolve())
    state = Path(sys.argv[2]).resolve()
    require(not state.exists(), "fresh_smoke_state_required")
    environment = os.environ.copy()
    for name in ("JAVA_HOME", "JDK_HOME", "JRE_HOME", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"):
        environment.pop(name, None)
    environment["PATH"] = ""
    address = "http://127.0.0.1:8080"
    # Exclude proxy configuration, including HTTP_PROXY inherited from CI.
    anonymous = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    authenticated = urllib.request.build_opener(
        urllib.request.ProxyHandler({}),
        urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()),
    )
    process = None
    try:
        bootstrap = subprocess.run(
            [launcher, "--make-bootstrap", f"--state-dir={state}"],
            env=environment, capture_output=True, text=True, encoding="utf-8", timeout=30,
        )
        require(bootstrap.returncode == 0, "bootstrap_failed")
        passwords = []
        for label in ("Setup administrator password", "Setup Wi-Fi password"):
            match = re.search(r"^" + re.escape(label) + r":\s*(\S+)\s*$", bootstrap.stdout, re.MULTILINE)
            require(match is not None, "private_bootstrap_instructions_missing")
            passwords.append(match.group(1))
        # Temporary output is private, never printed or copied to smoke reports.
        with tempfile.TemporaryFile() as private_output:
            process = subprocess.Popen(
                [launcher, "--simulate-service", f"--state-dir={state}"],
                env=environment, stdin=subprocess.DEVNULL, stdout=private_output, stderr=private_output,
            )
            deadline = time.monotonic() + 30
            login_page = None
            while time.monotonic() < deadline:
                require(process.poll() is None, "service_exited_before_http_ready")
                try:
                    with authenticated.open(address + "/", timeout=1) as response:
                        require(response.status == 200, "login_page_status")
                        login_page = response.read(65537).decode("utf-8")
                        break
                except (urllib.error.URLError, TimeoutError):
                    time.sleep(1.0)
            require(login_page is not None and len(login_page) <= 65536, "login_page_not_ready")
            match = re.search(r'name="csrf" value="([A-Za-z0-9_-]+)"', login_page)
            require(match is not None, "login_csrf_missing")
            for route in ("/status", "/diagnostics"):
                try:
                    anonymous.open(address + route, timeout=5).close()
                    raise RuntimeError("anonymous_private_route_allowed")
                except urllib.error.HTTPError as error:
                    require(error.code == 401, "anonymous_private_route_status")
            form = urllib.parse.urlencode({"password": passwords[0], "csrf": match.group(1)}).encode("ascii")
            request = urllib.request.Request(
                address + "/login", data=form,
                headers={"Content-Type": "application/x-www-form-urlencoded", "Origin": address},
            )
            with authenticated.open(request, timeout=5) as response:
                require(response.status == 200, "login_failed")
                authenticated_page = response.read(65537).decode("utf-8")
                require(len(authenticated_page) <= 65536, "authenticated_page_oversized")
            match = re.search(r'name="csrf" value="([A-Za-z0-9_-]+)"', authenticated_page)
            require(match is not None, "authenticated_csrf_missing")
            csrf = match.group(1)

            def private_get(route):
                require(process.poll() is None, "service_exited_during_http_smoke")
                with authenticated.open(address + route, timeout=5) as response:
                    require(response.status == 200, "authenticated_private_route_status")
                    body = response.read(65537).decode("utf-8")
                    require(len(body) <= 65536, "private_route_oversized")
                    require(all(password not in body for password in passwords), "credentials_in_private_route")
                    return body

            def status():
                value = json.loads(private_get("/status"))
                require(value.get("simulation") is True, "service_is_not_simulation")
                require(value.get("mode") in ("SETUP", "OPERATION"), "service_mode_missing")
                require(isinstance(value.get("stage"), str) and value["stage"], "service_stage_missing")
                require(all(type(value.get(key)) is int and value[key] >= 0 for key in ("queued", "prints", "completed")), "service_counts_missing")
                return value

            def post(route, **fields):
                form = urllib.parse.urlencode({"csrf": csrf, **fields}).encode("ascii")
                request = urllib.request.Request(
                    address + route, data=form,
                    headers={"Content-Type": "application/x-www-form-urlencoded", "Origin": address},
                )
                with authenticated.open(request, timeout=5) as response:
                    require(response.status == 200, "service_control_failed")
                    require(len(response.read(65537)) <= 65536, "service_control_response_oversized")

            def wait_counts(name, queued, prints, completed):
                deadline = time.monotonic() + 15
                while time.monotonic() < deadline:
                    value = status()
                    require(value["prints"] <= prints and value["completed"] <= completed, name + "_unexpected_submission")
                    if (value["queued"], value["prints"], value["completed"]) == (queued, prints, completed):
                        return value
                    time.sleep(1.0)
                raise RuntimeError(name + "_counts_timeout")

            def wait_operation():
                deadline = time.monotonic() + 15
                while time.monotonic() < deadline:
                    if status()["mode"] == "OPERATION":
                        return
                    time.sleep(1.0)
                raise RuntimeError("run_did_not_enter_operation")

            def stable_counts(name, queued, prints, completed, seconds):
                deadline = time.monotonic() + seconds
                while time.monotonic() < deadline:
                    value = status()
                    require((value["queued"], value["prints"], value["completed"]) == (queued, prints, completed), name + "_counts_changed")
                    time.sleep(1.0)

            initial = status()
            require(initial["mode"] == "SETUP", "first_boot_not_setup")
            require((initial["queued"], initial["prints"], initial["completed"]) == (0, 0, 0), "fresh_service_state_not_empty")
            private_get("/diagnostics")
            post("/simulate", action="printer-off")
            post("/run")
            wait_operation()
            post("/simulate", action="select-photo")
            wait_counts("printer_off_retention", queued=1, prints=0, completed=0)
            stable_counts("printer_off_retention", queued=1, prints=0, completed=0, seconds=0.5)
            post("/simulate", action="printer-on")
            wait_counts("printer_on_completion", queued=0, prints=1, completed=1)
            post("/simulate", action="duplicate-photo")
            stable_counts("duplicate_suppression", queued=0, prints=1, completed=1, seconds=3)
            post("/simulate", action="printer-busy")
            post("/simulate", action="select-photo")
            wait_counts("printer_busy_retention", queued=1, prints=1, completed=1)
            stable_counts("printer_busy_retention", queued=1, prints=1, completed=1, seconds=0.5)
            post("/simulate", action="printer-ready")
            wait_counts("printer_ready_completion", queued=0, prints=2, completed=2)
            private_get("/diagnostics")
            require(process.poll() is None, "service_exited_during_http_smoke")
            process.terminate()
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
            process = None
        print("PACKAGED_SERVICE_HTTP_SMOKE_PASS bundled_runtime=true authenticated=true credentials_redacted=true orchestration=true")
    finally:
        if process is not None:
            if process.poll() is None:
                process.kill()
            process.wait(timeout=5)
        if state.exists():
            shutil.rmtree(state)


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        # Exceptions never contain response bodies or captured bootstrap output.
        reason = ("HTTP_STATUS_" + str(error.code)) if isinstance(error, urllib.error.HTTPError) else (str(error) if isinstance(error, RuntimeError) else type(error).__name__)
        print("PACKAGED_SERVICE_HTTP_SMOKE_FAILED category=" + reason, file=sys.stderr)
        sys.exit(1)
