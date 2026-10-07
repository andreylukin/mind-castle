#!/bin/sh
# Build, then re-sign under a stable identity. macOS recorded a Screen Recording denial for the
# default ad-hoc identity "castle-streamer" (2026-10-06); binaries signed as dev.mindcastle.streamer capture fine.
# Run the result as .build/release/castle-streamer-b, attached to a terminal (not reparented to launchd).
set -e
cd "$(dirname "$0")"
swift build -c release "$@"
# Write a new file and rename it into place, so a running castle-streamer-b keeps its old inode.
cp .build/release/castle-streamer .build/release/castle-streamer-b.tmp
codesign -f -s - -i dev.mindcastle.streamer .build/release/castle-streamer-b.tmp
mv -f .build/release/castle-streamer-b.tmp .build/release/castle-streamer-b
