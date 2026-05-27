#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOOLCHAIN_DIR="$ROOT_DIR/../android/.local"

export JAVA_HOME="$TOOLCHAIN_DIR/jdk-root/usr/lib/jvm/java-17-openjdk-amd64"
export ANDROID_HOME="$TOOLCHAIN_DIR/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export GRADLE_USER_HOME="$TOOLCHAIN_DIR/gradle-user-home"
export PATH="$JAVA_HOME/bin:$TOOLCHAIN_DIR/gradle/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

trust_store="$JAVA_HOME/lib/security/cacerts"
export GRADLE_OPTS="${GRADLE_OPTS:-} -Djavax.net.ssl.trustStore=$trust_store -Djavax.net.ssl.trustStorePassword=changeit -Djavax.net.ssl.trustStoreType=PKCS12"

proxy_url="${https_proxy:-${HTTPS_PROXY:-${http_proxy:-${HTTP_PROXY:-}}}}"
if [[ "${PHONE_PROXY_USE_GRADLE_PROXY:-0}" == "1" && -n "$proxy_url" ]]; then
  proxy_no_scheme="${proxy_url#http://}"
  proxy_no_scheme="${proxy_no_scheme#https://}"
  proxy_host="${proxy_no_scheme%%:*}"
  proxy_port="${proxy_no_scheme##*:}"
  export GRADLE_OPTS="${GRADLE_OPTS:-} -Dhttp.proxyHost=$proxy_host -Dhttp.proxyPort=$proxy_port -Dhttps.proxyHost=$proxy_host -Dhttps.proxyPort=$proxy_port"
fi

printf 'sdk.dir=%s\n' "$ANDROID_HOME" > "$ROOT_DIR/local.properties"

cd "$ROOT_DIR"
"$TOOLCHAIN_DIR/gradle/bin/gradle" --no-daemon assembleDebug
