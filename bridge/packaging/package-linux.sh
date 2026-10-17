#!/usr/bin/env bash
# Build-machine only: native Linux JDK 17; the resulting archive needs no JDK.
set -euo pipefail

repository=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
version=0.5.0
case "$(uname -m)" in
    x86_64) architecture=x64 ;;
    aarch64|arm64) architecture=arm64 ;;
    *) printf '%s\n' 'Packaging requires Linux x64 or ARM64.' >&2; exit 1 ;;
esac
[[ $(uname -s) == Linux ]] || { printf '%s\n' 'Packaging requires Linux.' >&2; exit 1; }
[[ -n ${JAVA_HOME:-} ]] || { printf '%s\n' 'JAVA_HOME must identify the build JDK 17.' >&2; exit 1; }
jdk=$JAVA_HOME
for tool in java jlink; do
    [[ -x "$jdk/bin/$tool" ]] || { printf '%s\n' 'The build JDK is missing a packaging tool.' >&2; exit 1; }
done
[[ -f "$jdk/release" ]] && grep -Eq '^JAVA_VERSION="17([.+"]|$)' "$jdk/release" || {
    printf '%s\n' 'The build JDK must be Java 17.' >&2; exit 1;
}
distribution="$repository/bridge/build/install/bridge"
[[ -f "$distribution/lib/bridge-$version.jar" ]] || {
    printf '%s\n' 'Run :bridge:installDist with -PheadlessOnly=true before packaging.' >&2; exit 1;
}
output="$repository/bridge/build/portable/linux-$architecture"
image="$output/image/PocketPhotoBridge"
archive="$output/PocketPhotoBridge-$version-linux-$architecture.tar.gz"
rm -rf -- "$output"
mkdir -p -- "$image"
cp -a -- "$distribution/." "$image/"
# ImageIO/Java2D and HttpServer remain required in headless service mode.
"$jdk/bin/jlink" --module-path "$jdk/jmods" \
    --add-modules java.base,java.desktop,java.logging,java.xml,java.net.http,jdk.httpserver,jdk.crypto.ec,jdk.unsupported \
    --strip-debug --no-header-files --no-man-pages --compress=2 --output "$image/runtime"
cp -- "$repository/bridge/packaging/linux-launcher.sh" "$image/bin/bridge"
chmod 755 "$image/bin/bridge" "$image/deploy/linux/install.sh"
mkdir -p -- "$image/licenses/java-runtime"
for notice in LICENSE NOTICE release; do
    [[ ! -f "$jdk/$notice" ]] || cp -- "$jdk/$notice" "$image/licenses/java-runtime/"
done
[[ -d "$image/runtime/legal" ]] || { printf '%s\n' 'Runtime legal notices are absent.' >&2; exit 1; }
tar -czf "$archive" -C "$output/image" PocketPhotoBridge
(cd -- "$output" && sha256sum "$(basename -- "$archive")" >"$(basename -- "$archive").sha256")
printf 'LINUX_PORTABLE_PACKAGE_CREATED version=%s architecture=%s\n' "$version" "$architecture"
