#!/bin/sh
# The server runs as "tonearm" (uid 1000). Started as root (the image's default), it first gives DATA_DIR to
# that user, so a folder mounted from the host works without a chown by hand. Where that isn't allowed (some
# NAS shares), it stays root rather than being unable to save anything.
SERVER=/opt/tonearm-server/bin/tonearm-server
if [ "$(id -u)" = 0 ]; then
    mkdir -p "$DATA_DIR"
    chown -R tonearm:tonearm "$DATA_DIR" 2>/dev/null
    if su-exec tonearm sh -c 'probe="$DATA_DIR/.write-check" && : > "$probe" && rm -f "$probe"' 2>/dev/null; then
        exec su-exec tonearm "$SERVER" "$@"
    fi
    echo "$DATA_DIR can't be given to the tonearm user (uid 1000), so the server runs as root" >&2
fi
exec "$SERVER" "$@"
