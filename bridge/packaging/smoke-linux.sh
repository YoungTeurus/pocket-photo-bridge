#!/usr/bin/env bash
# Runs an extracted architecture-independent JVM archive using local Java 17.
set -euo pipefail

repository=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
archive=${1:-"$repository/bridge/build/distributions/bridge-0.4.1.tar"}
work="$repository/bridge/build/portable/Linux smoke extraction with spaces"
reports="$repository/bridge/build/reports/smoke/linux"
rm -rf -- "$work" "$reports"
mkdir -p -- "$work" "$reports"
tar -xf "$archive" -C "$work"
launcher="$work/bridge-0.4.1/bin/bridge"
for file in "$launcher" "$work/bridge-0.4.1/licenses/mizink-LICENSE.txt" "$work/bridge-0.4.1/licenses/PocketPhotoBridge-LICENSE.txt" "$work/bridge-0.4.1/linux-bridge.md"; do
    [[ -f "$file" ]] || { printf '%s\n' 'The extracted JVM distribution is incomplete.' >&2; exit 1; }
done
[[ -x "$launcher" ]] || { printf '%s\n' 'The distribution launcher is not executable.' >&2; exit 1; }
export JAVA_OPTS='-Djava.awt.headless=true -Xmx512m'
unset JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS
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
    if timeout --kill-after=5s 60s "$launcher" "$@" >"$reports/$name.stdout.txt" 2>"$reports/$name.stderr.txt"; then
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
        help|hardware-refused|invalid-usage|power-cut) ;;
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
printf 'SMOKE_LINUX_PASS checks=%s\n' "$checks" | tee "$reports/summary.txt"
