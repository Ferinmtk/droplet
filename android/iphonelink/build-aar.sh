#!/bin/sh
# Builds the iPhone link's transport (this Go package, with Pion) into
# android/app/libs/iphonelink.aar, for 64- and 32-bit ARM phones. Gradle runs
# it before each build when a .go file changed (app/build.gradle.kts).
#
# Needs Go (go.mod's version), gomobile and gobind:
#   go install golang.org/x/mobile/cmd/gomobile@latest golang.org/x/mobile/cmd/gobind@latest
# and the Android NDK: ANDROID_NDK_HOME, or one under $ANDROID_HOME/ndk.
set -eu
cd "$(dirname "$0")"
out=../app/libs/iphonelink.aar
mkdir -p ../app/libs
export PATH="$(go env GOPATH)/bin:$PATH"
if [ -z "${ANDROID_NDK_HOME:-}" ] && [ -d "${ANDROID_HOME:-}/ndk" ]; then
    ANDROID_NDK_HOME="$ANDROID_HOME/ndk/$(ls "$ANDROID_HOME/ndk" | sort -V | tail -1)"
    export ANDROID_NDK_HOME
fi
# -checklinkname=0: pion/transport's interface lister (wlynxg/anet) reaches into net
gomobile bind -target=android/arm64,android/arm -androidapi 26 -javapkg dev.droplet.app \
    -ldflags="-s -w -checklinkname=0" -trimpath -o "$out" .
rm -f ../app/libs/iphonelink-sources.jar
