#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_DIR="$ROOT_DIR/android"
TOOLCHAIN_DIR="$ANDROID_DIR/.local"
DOWNLOAD_DIR="$TOOLCHAIN_DIR/downloads"
JDK_EXTRACT_ROOT="$TOOLCHAIN_DIR/jdk-root"
JDK_ROOT="$JDK_EXTRACT_ROOT/usr/lib/jvm/java-17-openjdk-amd64"
GRADLE_ROOT="$TOOLCHAIN_DIR/gradle"
SDK_ROOT="$TOOLCHAIN_DIR/android-sdk"
CMDLINE_TOOLS_ROOT="$SDK_ROOT/cmdline-tools/latest"
LICENSES_DIR="$SDK_ROOT/licenses"

JDK_JRE_DEB="openjdk-17-jre-headless_17.0.18+8-1~24.04.1_amd64.deb"
JDK_JDK_DEB="openjdk-17-jdk-headless_17.0.18+8-1~24.04.1_amd64.deb"
GRADLE_VERSION="8.7"
GRADLE_URL="https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip"
ANDROID_CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-14742923_latest.zip"
PLATFORM_TOOLS_ZIP="platform-tools_r37.0.0-linux.zip"
BUILD_TOOLS_ZIP="build-tools_r34-linux.zip"
PLATFORM_34_ZIP="platform-34-ext7_r03.zip"

mkdir -p "$DOWNLOAD_DIR" "$SDK_ROOT/cmdline-tools"

download() {
  local url="$1"
  local output="$2"
  if [[ -f "$output" ]]; then
    echo "using cached $(basename "$output")"
    return 0
  fi
  curl -fL -C - --retry 5 --retry-all-errors --retry-delay 2 "$url" -o "$output"
}

download_java_debs() {
  if [[ -f "$DOWNLOAD_DIR/$JDK_JRE_DEB" && -f "$DOWNLOAD_DIR/$JDK_JDK_DEB" ]]; then
    echo "using cached java deb packages"
    return 0
  fi
  (
    cd "$DOWNLOAD_DIR"
    apt-get download \
      "openjdk-17-jre-headless=17.0.18+8-1~24.04.1" \
      "openjdk-17-jdk-headless=17.0.18+8-1~24.04.1"
  )
}

build_local_cacerts() {
  local cacerts_path="$1"
  local cert_bundle="/etc/ssl/certs/ca-certificates.crt"
  local temp_cert_dir

  rm -f "$cacerts_path"
  temp_cert_dir="$(mktemp -d)"

  awk -v outdir="$temp_cert_dir" '
    /BEGIN CERTIFICATE/ {
      n += 1
      file = sprintf("%s/cert-%03d.pem", outdir, n)
    }
    { print >> file }
  ' "$cert_bundle"

  "$JDK_ROOT/bin/keytool" \
    -genkeypair \
    -alias __bootstrap__ \
    -dname "CN=bootstrap" \
    -keyalg RSA \
    -keysize 2048 \
    -validity 1 \
    -keystore "$cacerts_path" \
    -storepass changeit \
    -keypass changeit \
    -noprompt >/dev/null 2>&1

  "$JDK_ROOT/bin/keytool" \
    -delete \
    -alias __bootstrap__ \
    -keystore "$cacerts_path" \
    -storepass changeit >/dev/null 2>&1 || true

  for cert in "$temp_cert_dir"/*.pem; do
    "$JDK_ROOT/bin/keytool" \
      -importcert \
      -alias "$(basename "$cert" .pem)" \
      -file "$cert" \
      -keystore "$cacerts_path" \
      -storepass changeit \
      -noprompt >/dev/null 2>&1
  done

  rm -rf "$temp_cert_dir"
}

materialize_jdk_symlinks() {
  while IFS= read -r link_path; do
    local target
    target="$(readlink "$link_path")"
    case "$target" in
      /etc/java-17-openjdk/*)
        local relative_path
        local source_path
        relative_path="${target#/etc/java-17-openjdk/}"
        source_path="$JDK_EXTRACT_ROOT/etc/java-17-openjdk/$relative_path"
        if [[ -f "$source_path" ]]; then
          rm -f "$link_path"
          cp -a "$source_path" "$link_path"
        fi
        ;;
    esac
  done < <(find "$JDK_ROOT" -type l)
}

install_local_sdk_packages() {
  mkdir -p "$SDK_ROOT"

  if [[ ! -x "$SDK_ROOT/platform-tools/adb" ]]; then
    rm -rf "$SDK_ROOT/platform-tools"
    python3 "$ROOT_DIR/scripts/extract_zip.py" \
      "$DOWNLOAD_DIR/$PLATFORM_TOOLS_ZIP" \
      "$SDK_ROOT"
  fi

  if [[ ! -x "$SDK_ROOT/build-tools/34.0.0/aapt2" ]]; then
    rm -rf "$SDK_ROOT/build-tools/34.0.0" "$SDK_ROOT/android-14"
    mkdir -p "$SDK_ROOT/build-tools"
    python3 "$ROOT_DIR/scripts/extract_zip.py" \
      "$DOWNLOAD_DIR/$BUILD_TOOLS_ZIP" \
      "$SDK_ROOT"
    mv "$SDK_ROOT/android-14" "$SDK_ROOT/build-tools/34.0.0"
  fi

  if [[ ! -f "$SDK_ROOT/platforms/android-34/android.jar" ]]; then
    rm -rf "$SDK_ROOT/platforms/android-34" "$SDK_ROOT/android-34"
    python3 "$ROOT_DIR/scripts/extract_zip.py" \
      "$DOWNLOAD_DIR/$PLATFORM_34_ZIP" \
      "$SDK_ROOT"
    mkdir -p "$SDK_ROOT/platforms"
    mv "$SDK_ROOT/android-34" "$SDK_ROOT/platforms/android-34"
  fi

  chmod +x "$SDK_ROOT/platform-tools/"* || true
  chmod +x "$SDK_ROOT/build-tools/34.0.0/"* || true
}

write_android_licenses() {
  mkdir -p "$LICENSES_DIR"
  cat > "$LICENSES_DIR/android-sdk-license" <<'EOF'
24333f8a63b6825ea9c5514f83c2829b004d1fee
d56f5187479451eabf01fb78af6dfcb131a6481e
EOF
}

download_java_debs
download "$GRADLE_URL" "$DOWNLOAD_DIR/gradle-${GRADLE_VERSION}-bin.zip" &
gradle_download_pid=$!
download "$ANDROID_CMDLINE_TOOLS_URL" "$DOWNLOAD_DIR/commandlinetools-linux-14742923_latest.zip" &
android_tools_download_pid=$!
wait "$gradle_download_pid" "$android_tools_download_pid"

if [[ ! -x "$JDK_ROOT/bin/java" ]]; then
  rm -rf "$JDK_EXTRACT_ROOT"
  mkdir -p "$JDK_EXTRACT_ROOT"
  dpkg-deb -x "$DOWNLOAD_DIR/$JDK_JRE_DEB" "$JDK_EXTRACT_ROOT"
  dpkg-deb -x "$DOWNLOAD_DIR/$JDK_JDK_DEB" "$JDK_EXTRACT_ROOT"
fi

materialize_jdk_symlinks

if [[ -L "$JDK_ROOT/lib/security/cacerts" || ! -f "$JDK_ROOT/lib/security/cacerts" ]]; then
  build_local_cacerts "$JDK_ROOT/lib/security/cacerts"
fi

if [[ ! -x "$GRADLE_ROOT/bin/gradle" ]]; then
  rm -rf "$TOOLCHAIN_DIR/gradle-${GRADLE_VERSION}" "$GRADLE_ROOT"
  python3 "$ROOT_DIR/scripts/extract_zip.py" \
    "$DOWNLOAD_DIR/gradle-${GRADLE_VERSION}-bin.zip" \
    "$TOOLCHAIN_DIR"
  mv "$TOOLCHAIN_DIR/gradle-${GRADLE_VERSION}" "$GRADLE_ROOT"
fi
chmod +x "$GRADLE_ROOT/bin/gradle"

if [[ ! -x "$CMDLINE_TOOLS_ROOT/bin/sdkmanager" ]]; then
  rm -rf "$TOOLCHAIN_DIR/cmdline-tools-temp" "$SDK_ROOT/cmdline-tools/latest"
  mkdir -p "$TOOLCHAIN_DIR/cmdline-tools-temp"
  python3 "$ROOT_DIR/scripts/extract_zip.py" \
    "$DOWNLOAD_DIR/commandlinetools-linux-14742923_latest.zip" \
    "$TOOLCHAIN_DIR/cmdline-tools-temp"
  mkdir -p "$CMDLINE_TOOLS_ROOT"
  cp -R "$TOOLCHAIN_DIR/cmdline-tools-temp/cmdline-tools/." "$CMDLINE_TOOLS_ROOT/"
  rm -rf "$TOOLCHAIN_DIR/cmdline-tools-temp"
fi
chmod +x "$CMDLINE_TOOLS_ROOT/bin/"*

export JAVA_HOME="$JDK_ROOT"
export ANDROID_HOME="$SDK_ROOT"
export ANDROID_SDK_ROOT="$SDK_ROOT"
export PATH="$JAVA_HOME/bin:$GRADLE_ROOT/bin:$CMDLINE_TOOLS_ROOT/bin:$SDK_ROOT/platform-tools:$PATH"

install_local_sdk_packages
write_android_licenses

cat > "$ANDROID_DIR/local.properties" <<EOF
sdk.dir=$SDK_ROOT
EOF

echo "JAVA_HOME=$JAVA_HOME"
echo "ANDROID_SDK_ROOT=$ANDROID_SDK_ROOT"
echo "GRADLE_BIN=$GRADLE_ROOT/bin/gradle"
echo "ADB_BIN=$SDK_ROOT/platform-tools/adb"
