#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIST_DIR="${ROOT_DIR}/dist"

TARGET_DIRS=(
    "${ROOT_DIR}/target"
)

CRATE_NAME="dianyaapi-jni"
SDK_VERSION="$(sed -n 's/^version = "\(.*\)"/\1/p' "${ROOT_DIR}/java/build.gradle.kts" | head -n 1)"
if [[ -z "${SDK_VERSION}" ]]; then
    SDK_VERSION="0.0.0"
fi

ANDROID_PLATFORM_LEVEL=21
SUPPORTED_ANDROID_ABIS=("arm64-v8a" "x86_64" "armeabi-v7a")
DEFAULT_ANDROID_ABIS=("arm64-v8a" "x86_64")
ANDROID_ABIS_SELECTED=("${DEFAULT_ANDROID_ABIS[@]}")
HOST_TRIPLE=""
HOST_LIB_FILENAME=""
HOST_JAR_SUBDIR=""

TMP_NATIVE_DIR="${ROOT_DIR}/build/native"
JAR_NATIVE_DIR="${TMP_NATIVE_DIR}/jar"
ANDROID_NATIVE_DIR="${TMP_NATIVE_DIR}/android"

if [[ -x "${ROOT_DIR}/gradlew" ]]; then
    GRADLE_CMD="${ROOT_DIR}/gradlew"
else
    if command -v gradle >/dev/null 2>&1; then
        GRADLE_CMD="gradle"
    else
        echo "未找到 gradle 或 gradlew，请先安装 Gradle 或在 ${ROOT_DIR} 下执行 'gradle wrapper'。" >&2
        exit 1
    fi
fi

BUILD_PROFILE="debug"
MODE=""

usage() {
    cat <<EOF
用法：$0 [--debug|--release] [--platform <api_level>] [--arch <abi1,abi2|all>] [jar|aar|all]

参数说明：
  --debug / --release    选择构建 profile，默认 debug
  --platform             指定 cargo-ndk 的 Android API level，默认 21
  --arch                 指定需要构建的 Android ABI（逗号分隔或 all）
  jar|aar|all            需要生成的产物，默认 all

当前支持的 Android ABI：${SUPPORTED_ANDROID_ABIS[*]}
EOF
}

parse_android_arches() {
    local raw="$1"
    local cleaned="${raw// /}"

    if [[ -z "${cleaned}" ]]; then
        echo "--arch 需要至少一个 ABI 值" >&2
        usage
        exit 1
    fi

    if [[ "${cleaned}" == "all" ]]; then
        ANDROID_ABIS_SELECTED=("${SUPPORTED_ANDROID_ABIS[@]}")
        return
    fi

    local -a parsed=()
    IFS=',' read -r -a parsed <<<"${cleaned}"
    ANDROID_ABIS_SELECTED=()
    for abi in "${parsed[@]}"; do
        [[ -z "${abi}" ]] && continue
        ANDROID_ABIS_SELECTED+=("${abi}")
    done

    if [[ "${#ANDROID_ABIS_SELECTED[@]}" -eq 0 ]]; then
        echo "--arch 需要至少一个有效的 ABI 名称" >&2
        usage
        exit 1
    fi
}

is_supported_android_abi() {
    local abi="$1"
    case "${abi}" in
        arm64-v8a|x86_64|armeabi-v7a)
            return 0
            ;;
        *)
            return 1
            ;;
    esac
}

validate_android_abis() {
    if [[ "${#ANDROID_ABIS_SELECTED[@]}" -eq 0 ]]; then
        echo "至少需要一个 Android ABI" >&2
        usage
        exit 1
    fi

    local abi
    for abi in "${ANDROID_ABIS_SELECTED[@]}"; do
        if ! is_supported_android_abi "${abi}"; then
            echo "不支持的 Android ABI：${abi}" >&2
            usage
            exit 1
        fi
    done
}

android_target_for_abi() {
    local abi="$1"
    case "${abi}" in
        arm64-v8a)
            printf "%s" "aarch64-linux-android"
            ;;
        x86_64)
            printf "%s" "x86_64-linux-android"
            ;;
        armeabi-v7a)
            printf "%s" "armv7-linux-androideabi"
            ;;
        *)
            return 1
            ;;
    esac
}

detect_host_native_info() {
    if [[ -n "${HOST_TRIPLE}" ]]; then
        return
    fi

    if ! command -v rustc >/dev/null 2>&1; then
        echo "未找到 rustc，请先安装 Rust toolchain。" >&2
        exit 1
    fi

    HOST_TRIPLE="$(rustc -vV | awk '/^host: / { print $2 }')"
    if [[ -z "${HOST_TRIPLE}" ]]; then
        echo "无法检测当前 host triple。" >&2
        exit 1
    fi

    local arch="${HOST_TRIPLE%%-*}"
    case "${HOST_TRIPLE}" in
        *windows-msvc*)
            HOST_LIB_FILENAME="dianyaapi_jni.dll"
            HOST_JAR_SUBDIR="windows-${arch}"
            ;;
        *apple-darwin*)
            HOST_LIB_FILENAME="libdianyaapi_jni.dylib"
            HOST_JAR_SUBDIR="macos-${arch}"
            ;;
        *linux-gnu*|*linux-musl*)
            HOST_LIB_FILENAME="libdianyaapi_jni.so"
            HOST_JAR_SUBDIR="linux-${arch}"
            ;;
        *)
            echo "暂不支持当前宿主平台：${HOST_TRIPLE}" >&2
            exit 1
            ;;
    esac
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --debug)
            BUILD_PROFILE="debug"
            shift
            ;;
        --release)
            BUILD_PROFILE="release"
            shift
            ;;
        --platform)
            if [[ $# -lt 2 ]]; then
                echo "--platform 需要一个参数（如 21）" >&2
                usage
                exit 1
            fi
            ANDROID_PLATFORM_LEVEL="$2"
            shift 2
            ;;
        --arch)
            if [[ $# -lt 2 ]]; then
                echo "--arch 需要一个参数（如 arm64-v8a,x86_64 或 all）" >&2
                usage
                exit 1
            fi
            parse_android_arches "$2"
            shift 2
            ;;
        jar|aar|all)
            MODE="$1"
            shift
            ;;
        *)
            echo "未知参数：$1" >&2
            usage >&2
            exit 1
            ;;
    esac
done

MODE="${MODE:-all}"

NEED_DESKTOP_NATIVE=false
NEED_ANDROID_NATIVE=false

case "${MODE}" in
    jar)
        NEED_DESKTOP_NATIVE=true
        ;;
    aar)
        NEED_ANDROID_NATIVE=true
        ;;
    all)
        NEED_DESKTOP_NATIVE=true
        NEED_ANDROID_NATIVE=true
        ;;
    *)
        usage >&2
        exit 1
        ;;
esac

if [[ "${NEED_ANDROID_NATIVE}" == true ]]; then
    validate_android_abis
fi

if [[ "${NEED_DESKTOP_NATIVE}" == true ]]; then
    detect_host_native_info
fi

mkdir -p "${DIST_DIR}"
mkdir -p "${TMP_NATIVE_DIR}"

cleanup() {
    rm -rf "${TMP_NATIVE_DIR}"
}
trap cleanup EXIT

copy_jar_outputs() {
    local main_jar="${ROOT_DIR}/java/build/libs/${CRATE_NAME}-${SDK_VERSION}.jar"
    if [[ -f "${main_jar}" ]]; then
        cp "${main_jar}" "${DIST_DIR}/${CRATE_NAME}-${SDK_VERSION}.jar"
    else
        echo "警告: 未找到 ${main_jar}，尝试复制全部生成的 JAR。" >&2
        cp "${ROOT_DIR}"/java/build/libs/*.jar "${DIST_DIR}/" 2>/dev/null || true
    fi

    for extra in "${ROOT_DIR}/java/build/libs/${CRATE_NAME}-${SDK_VERSION}-"*.jar; do
        [[ -f "${extra}" ]] || continue
        local extra_name
        extra_name="$(basename "${extra}")"
        cp "${extra}" "${DIST_DIR}/${extra_name}"
    done
}

copy_aar_output() {
    local candidates=()
    if [[ "${BUILD_PROFILE}" == "debug" ]]; then
        candidates+=("android-debug.aar")
    fi
    candidates+=("android-release.aar")

    local aar_src=""
    for candidate in "${candidates[@]}"; do
        if [[ -f "${ROOT_DIR}/android/build/outputs/aar/${candidate}" ]]; then
            aar_src="${ROOT_DIR}/android/build/outputs/aar/${candidate}"
            break
        fi
    done

    if [[ -n "${aar_src}" ]]; then
        cp "${aar_src}" "${DIST_DIR}/${CRATE_NAME}-${SDK_VERSION}.aar"
    else
        echo "警告: 未找到可复制的 AAR 文件。" >&2
    fi
}

find_native_lib() {
    local target="$1"
    local profile="$2"
    local file_name="$3"
    local candidate

    for base in "${TARGET_DIRS[@]}"; do
        if [[ -n "${target}" ]]; then
            candidate="${base}/${target}/${profile}/${file_name}"
            if [[ -f "${candidate}" ]]; then
                echo "${candidate}"
                return 0
            fi
        else
            candidate="${base}/${profile}/${file_name}"
            if [[ -f "${candidate}" ]]; then
                echo "${candidate}"
                return 0
            fi

            if [[ -n "${HOST_TRIPLE}" ]]; then
                candidate="${base}/${HOST_TRIPLE}/${profile}/${file_name}"
                if [[ -f "${candidate}" ]]; then
                    echo "${candidate}"
                    return 0
                fi
            fi
        fi
    done

    return 1
}

ensure_cargo_ndk() {
    if cargo ndk --version >/dev/null 2>&1; then
        return
    fi
    echo "未找到 cargo-ndk，请先执行 'cargo install cargo-ndk'。" >&2
    exit 1
}

build_host_native_lib() {
    local cmd=(cargo build)
    if [[ "${BUILD_PROFILE}" == "release" ]]; then
        cmd+=(--release)
    fi

    echo "开始构建宿主平台动态库：${cmd[*]}"
    (
        cd "${ROOT_DIR}"
        "${cmd[@]}"
    )
}

copy_host_native_lib() {
    local src
    if ! src="$(find_native_lib "" "${BUILD_PROFILE}" "${HOST_LIB_FILENAME}")"; then
        cat >&2 <<EOF
未找到宿主平台动态库 ${HOST_LIB_FILENAME}
请检查 cargo build 输出是否成功（target 目录：${TARGET_DIRS[*]}）
EOF
        exit 1
    fi

    mkdir -p "${JAR_NATIVE_DIR}/${HOST_JAR_SUBDIR}"
    cp "${src}" "${JAR_NATIVE_DIR}/${HOST_JAR_SUBDIR}/${HOST_LIB_FILENAME}"
}

build_android_native_libs() {
    if [[ "${#ANDROID_ABIS_SELECTED[@]}" -eq 0 ]]; then
        return
    fi

    ensure_cargo_ndk

    local cmd=(cargo ndk --platform "${ANDROID_PLATFORM_LEVEL}")
    local abi
    for abi in "${ANDROID_ABIS_SELECTED[@]}"; do
        cmd+=(-t "${abi}")
    done
    cmd+=(-o "${ROOT_DIR}/target/jniLibs" -- build)
    if [[ "${BUILD_PROFILE}" == "release" ]]; then
        cmd+=(--release)
    fi

    echo "开始构建 Android 动态库：${cmd[*]}"
    (
        cd "${ROOT_DIR}"
        "${cmd[@]}"
    )
}

copy_android_native_libs() {
    local abi target src
    for abi in "${ANDROID_ABIS_SELECTED[@]}"; do
        if ! target="$(android_target_for_abi "${abi}")"; then
            echo "跳过未知 ABI：${abi}" >&2
            continue
        fi

        if ! src="$(find_native_lib "${target}" "${BUILD_PROFILE}" "libdianyaapi_jni.so")"; then
            cat >&2 <<EOF
未找到 ABI=${abi} (${target}) 的动态库
请检查 cargo ndk 是否构建成功（target 目录：${TARGET_DIRS[*]}）
EOF
            exit 1
        fi

        mkdir -p "${ANDROID_NATIVE_DIR}/${abi}"
        cp "${src}" "${ANDROID_NATIVE_DIR}/${abi}/libdianyaapi_jni.so"

        if [[ "${NEED_DESKTOP_NATIVE}" == true ]]; then
            local jar_dir="${JAR_NATIVE_DIR}/android-${abi}"
            mkdir -p "${jar_dir}"
            cp "${src}" "${jar_dir}/libdianyaapi_jni.so"
        fi
    done
}

prepare_native_libs() {
    rm -rf "${TMP_NATIVE_DIR}"
    mkdir -p "${JAR_NATIVE_DIR}" "${ANDROID_NATIVE_DIR}"

    if [[ "${NEED_DESKTOP_NATIVE}" == true ]]; then
        build_host_native_lib
        copy_host_native_lib
    fi

    if [[ "${NEED_ANDROID_NATIVE}" == true ]]; then
        build_android_native_libs
        copy_android_native_libs
    fi
}

prepare_native_libs

case "${MODE}" in
    jar)
        "${GRADLE_CMD}" -p "${ROOT_DIR}" \
            -PjarNativeDir="${JAR_NATIVE_DIR}" \
            -PbuildProfile="${BUILD_PROFILE}" \
            :java:clean :java:jar
        copy_jar_outputs
        ;;
    aar)
        "${GRADLE_CMD}" -p "${ROOT_DIR}" \
            -PandroidNativeDir="${ANDROID_NATIVE_DIR}" \
            -PbuildProfile="${BUILD_PROFILE}" \
            :android:clean :android:assembleRelease
        copy_aar_output
        ;;
    all)
        "${GRADLE_CMD}" -p "${ROOT_DIR}" \
            -PjarNativeDir="${JAR_NATIVE_DIR}" \
            -PandroidNativeDir="${ANDROID_NATIVE_DIR}" \
            -PbuildProfile="${BUILD_PROFILE}" \
            :java:clean :java:jar :android:clean :android:assembleRelease
        copy_jar_outputs
        copy_aar_output
        ;;
    *)
        echo "未知目标：${MODE}，可选项为 jar / aar / all" >&2
        exit 1
        ;;
esac

echo "构建完成，产物请查看 ${DIST_DIR}"

