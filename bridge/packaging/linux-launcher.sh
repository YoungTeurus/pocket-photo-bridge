#!/bin/sh
# Uses only the bundled runtime, including when PATH and JAVA_HOME are absent.
set -eu
application_dir=$(CDPATH= cd -- "${0%/*}/.." && pwd)
unset JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS
exec "$application_dir/runtime/bin/java" \
    -Djava.awt.headless=true -Dfile.encoding=UTF-8 -Xmx512m \
    -cp "$application_dir/lib/*" dev.pocketphotobridge.bridge.BridgeCliKt "$@"
