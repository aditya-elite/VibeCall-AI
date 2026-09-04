#!/usr/bin/env sh
set -eu

GRADLE_VERSION=8.9
PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
DIST_ROOT="$PROJECT_DIR/.gradle-dist"
GRADLE_HOME="$DIST_ROOT/gradle-$GRADLE_VERSION"
GRADLE_ZIP="$DIST_ROOT/gradle-$GRADLE_VERSION-bin.zip"

if [ ! -x "$GRADLE_HOME/bin/gradle" ]; then
  mkdir -p "$DIST_ROOT"
  echo "Downloading Gradle $GRADLE_VERSION from the official Gradle service..."
  curl -fL "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip" -o "$GRADLE_ZIP"
  unzip -q -o "$GRADLE_ZIP" -d "$DIST_ROOT"
fi

exec "$GRADLE_HOME/bin/gradle" "$@"
