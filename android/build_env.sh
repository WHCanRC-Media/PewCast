#!/bin/bash
# Source this file before building the Android app:
#   source build_env.sh
#   ./gradlew assembleDebug

# Find Android SDK in common locations
if [[ -n "$ANDROID_HOME" && -d "$ANDROID_HOME" ]]; then
    :  # Use existing ANDROID_HOME
elif [[ -d "$HOME/work/tools/android-sdk" ]]; then
    export ANDROID_HOME="$HOME/work/tools/android-sdk"
elif [[ -d "$HOME/Android/Sdk" ]]; then
    export ANDROID_HOME="$HOME/Android/Sdk"
elif [[ -d "$HOME/android-sdk" ]]; then
    export ANDROID_HOME="$HOME/android-sdk"
elif [[ -d "/opt/android-sdk" ]]; then
    export ANDROID_HOME="/opt/android-sdk"
else
    echo "ERROR: Android SDK not found. Set ANDROID_HOME or install SDK to one of:"
    echo "  ~/work/tools/android-sdk"
    echo "  ~/Android/Sdk"
    echo "  ~/android-sdk"
    echo "  /opt/android-sdk"
    return 1 2>/dev/null || exit 1
fi

export ANDROID_SDK_ROOT="$ANDROID_HOME"
export JAVA_HOME="/usr/lib/jvm/java-21-openjdk-amd64"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

#echo "Android build environment configured:"
#echo "  JAVA_HOME=$JAVA_HOME"
#echo "  ANDROID_HOME=$ANDROID_HOME"
java -version 2>&1 | head -1
