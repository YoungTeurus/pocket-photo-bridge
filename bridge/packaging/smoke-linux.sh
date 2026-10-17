#!/usr/bin/env bash
# Runs a native Linux portable archive with system Java excluded from children.
set -euo pipefail

repository=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
case "$(uname -m)" in x86_64) architecture=x64 ;; aarch64|arm64) architecture=arm64 ;; *) exit 1 ;; esac
archive=${1:-"$repository/bridge/build/portable/linux-$architecture/PocketPhotoBridge-0.5.0-linux-$architecture.tar.gz"}
work="$repository/bridge/build/portable/Linux smoke extraction with spaces"
reports="$repository/bridge/build/reports/smoke/linux"
rm -rf -- "$work" "$reports"
mkdir -p -- "$work" "$reports"
[[ -f "$archive.sha256" ]] || { printf '%s\n' 'The portable checksum is missing.' >&2; exit 1; }
(cd -- "$(dirname -- "$archive")" && sha256sum -c "$(basename -- "$archive").sha256")
tar -xf "$archive" -C "$work"
image="$work/PocketPhotoBridge"
launcher="$image/bin/bridge"
for file in "$launcher" "$image/runtime/bin/java" "$image/runtime/lib/modules" "$image/licenses/mizink-LICENSE.txt" "$image/licenses/PocketPhotoBridge-LICENSE.txt" "$image/licenses/jna/LICENSE.txt" "$image/mvp5-deployment.md" "$image/deploy/linux/install.sh" "$image/deploy/linux/pocket-photo-bridge.service"; do
    [[ -f "$file" ]] || { printf '%s\n' 'The extracted Linux portable distribution is incomplete.' >&2; exit 1; }
done
[[ -x "$launcher" ]] || { printf '%s\n' 'The distribution launcher is not executable.' >&2; exit 1; }
[[ -d "$image/runtime/legal" ]] || { printf '%s\n' 'Runtime legal notices are absent.' >&2; exit 1; }
unset JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS
env -u JAVA_HOME -u JDK_HOME -u JRE_HOME PATH='' "$image/runtime/bin/java" --list-modules >"$reports/runtime-modules.txt"
grep -Eq '^jdk.httpserver@17' "$reports/runtime-modules.txt" || { printf '%s\n' 'The bundled runtime lacks HttpServer.' >&2; exit 1; }
checks=0

require_line() {
    local name=$1 pattern=$2
    if ! grep -Eq -- "$pattern" "$reports/$name.stdout.txt"; then
        printf 'Smoke case %s did not produce its expected result.\n' "$name" >&2
        exit 1
    fi
}

run_case() {
    local name=$1 expected=$2 actual
    shift 2
    if timeout --kill-after=5s 60s env -u JAVA_HOME -u JDK_HOME -u JRE_HOME PATH='' "$launcher" "$@" >"$reports/$name.stdout.txt" 2>"$reports/$name.stderr.txt"; then
        actual=0
    else
        actual=$?
    fi
    printf '%s\n' "$actual" >"$reports/$name.exit.txt"
    if [[ "$actual" -ne "$expected" ]]; then
        printf 'Smoke case %s returned %s; expected %s.\n' "$name" "$actual" "$expected" >&2
        exit 1
    fi
    case "$name" in
        help|hardware-refused|invalid-usage|power-cut|native-bindings) ;;
        *)
            require_line "$name" '^SIMULATION_ONLY$'
            require_line "$name" '^NO_PHYSICAL_PRINT$'
            require_line "$name" '^BRIDGE_[A-Z_]+([ =].*)?$'
            ;;
    esac
    checks=$((checks + 1))
}

run_case help 0 --help
require_line help --simulate-all
run_case native-bindings 0 --linux-native-check
require_line native-bindings '^NO_PHYSICAL_PRINT$'
run_case suite 0 --simulate-all "--state-dir=$work/state with spaces/suite"
require_line suite '^SIMULATION_SUITE_PASS checks=[0-9]+$'
normal_state="$work/state with spaces/normal"
run_case normal 0 --simulate "--state-dir=$normal_state"
require_line normal '^SIMULATED_PRINTS count=1$'
run_case duplicate 0 --simulate "--state-dir=$normal_state"
require_line duplicate '^SIMULATED_PRINTS count=0$'
run_case crop 0 --simulate --crop "--state-dir=$work/state with spaces/crop"
require_line crop '^SIMULATED_PRINTS count=1$'
for scenario in missing-ssid camera-offline camera-timeout no-selected-jpeg interrupted-transfer printer-offline printer-busy; do
    run_case "$scenario" 2 "--simulate-failure=$scenario" "--state-dir=$work/state with spaces/$scenario"
    require_line "$scenario" '^SIMULATED_PRINTS count=0$'
done
for scenario in dropped-after-submit uncertain-completion; do
    run_case "$scenario" 3 "--simulate-failure=$scenario" "--state-dir=$work/state with spaces/$scenario"
done
for scenario in duplicate-trigger duplicate-photo restart manual-recovery; do
    run_case "$scenario" 0 "--simulate-failure=$scenario" "--state-dir=$work/state with spaces/$scenario"
done
run_case hardware-refused 64 --hardware
run_case invalid-usage 64 --unsupported-option
crash_state="$work/state with spaces/power-cut"
run_case power-cut 71 --simulate-failure=power-cut "--state-dir=$crash_state"
run_case after-power-cut 3 --simulate "--state-dir=$crash_state"
require_line after-power-cut '^SIMULATED_PRINTS count=0$'
run_case acknowledge 0 --acknowledge-checked-printer --confirm "--state-dir=$crash_state"
require_line acknowledge '^SIMULATED_PRINTS count=0$'
run_case after-acknowledge 0 --simulate "--state-dir=$crash_state"
require_line after-acknowledge '^SIMULATED_PRINTS count=0$'
python3 "$repository/bridge/packaging/smoke-service.py" "$launcher" "$work/service state with spaces" >"$reports/service-http.txt"
checks=$((checks + 1))
printf 'SMOKE_LINUX_PASS checks=%s bundled_runtime=true\n' "$checks" | tee "$reports/summary.txt"
