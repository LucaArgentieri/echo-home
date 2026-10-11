#!/bin/sh
set -eu

APP_HOME=$(cd "${0%/*}" && pwd -P)
WRAPPER_DIR="$APP_HOME/gradle/wrapper"
WRAPPER_JAR="$WRAPPER_DIR/gradle-wrapper.jar"
WRAPPER_URL="https://raw.githubusercontent.com/gradle/gradle/v8.13.0/gradle/wrapper/gradle-wrapper.jar"
EXPECTED_SHA256="81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f"

sha256_file() {
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
  elif command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    echo "ERROR: shasum/sha256sum non disponibile" >&2
    exit 1
  fi
}

if [ ! -f "$WRAPPER_JAR" ]; then
  echo "Gradle Wrapper JAR non presente: download ufficiale Gradle 8.13..."
  mkdir -p "$WRAPPER_DIR"
  TMP="$WRAPPER_JAR.tmp"
  curl -L --fail --silent --show-error "$WRAPPER_URL" -o "$TMP"
  ACTUAL=$(sha256_file "$TMP")
  if [ "$ACTUAL" != "$EXPECTED_SHA256" ]; then
    rm -f "$TMP"
    echo "ERROR: checksum gradle-wrapper.jar non valido" >&2
    echo "Atteso: $EXPECTED_SHA256" >&2
    echo "Trovato: $ACTUAL" >&2
    exit 1
  fi
  mv "$TMP" "$WRAPPER_JAR"
fi

if [ -n "${JAVA_HOME:-}" ]; then
  JAVACMD="$JAVA_HOME/bin/java"
else
  JAVACMD=java
fi

exec "$JAVACMD" -Xmx64m -Xms64m -classpath "$WRAPPER_JAR" org.gradle.wrapper.GradleWrapperMain "$@"
