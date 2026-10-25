#!/usr/bin/env sh
# Lightweight Gradle launcher (uses preinstalled/setup-gradle in CI or local gradle wrapper)
if command -v gradle >/dev/null 2>&1; then
    exec gradle "$@"
else
    echo "Gradle CLI not found in PATH. In GitHub Actions, gradle/actions/setup-gradle provides Gradle 8.7 automatically."
    exit 1
fi
