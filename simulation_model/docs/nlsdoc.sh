#!/bin/sh
# Generate the HTML reference for the NetLogo model from its `;;` comments.
#
#   ./nlsdoc.sh            write simulation_model/docs/api
#   ./nlsdoc.sh --check    report documentation coverage instead
#
# Any Java 17+ will do; NetLogo bundles one, so no extra install is needed.
set -e

here=$(cd "$(dirname "$0")" && pwd)
root=$(dirname "$here")
out="$here/api"

java_bin=""
if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    java_bin="$JAVA_HOME/bin/java"
elif command -v java >/dev/null 2>&1; then
    java_bin=java
else
    for candidate in \
        /Applications/NetLogo*/runtime/bin/java \
        /opt/netlogo*/runtime/bin/java \
        /usr/local/netlogo*/runtime/bin/java \
        "/c/Program Files/NetLogo"*/runtime/bin/java.exe
    do
        if [ -x "$candidate" ]; then
            java_bin="$candidate"
            break
        fi
    done
fi

if [ -z "$java_bin" ]; then
    echo "nlsdoc: no Java 17+ found. Install a JDK, or NetLogo, and try again." >&2
    exit 1
fi

exec "$java_bin" "$here/NlsDoc.java" --root "$root" --out "$out" "$@"
