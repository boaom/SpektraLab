#!/bin/bash
# Build spektralab-host for Android aarch64 with the official NDK (CI, x86_64 host).
# Mirrors the proven local recipe (see ANDROID-PORT-NOTES.md):
#   * LLVM libunwind built with _LIBUNWIND_IS_NATIVE_ONLY (PAC-signed return
#     addresses authenticate instead of erroring; NDK r28 no longer ships one)
#   * static libc++ (-nostdlib++ -lc++_static -lc++abi)
#   * jthread/stop_token via -D_LIBCPP_ENABLE_EXPERIMENTAL
#   * stub archives so Alpine-style -lssp_nonshared / FindThreads -lpthreads
#     never break the link (bionic carries both in libc)
set -euo pipefail
: "${ANDROID_NDK:?set ANDROID_NDK to the extracted NDK root}"
REPO="$(cd "$(dirname "$0")/.." && pwd)"        # the SpektraLab checkout
PORT="$(cd "$(dirname "$0")" && pwd)"
API=30
NDKBIN="$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
SYSROOT="$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot"
B="$REPO/build/android-ci"
mkdir -p "$B"

# --- 1. LLVM libunwind for android ---
UW="$PORT/libunwind-llvm"
UWOUT="$B/libunwind"
mkdir -p "$UWOUT"; rm -f "$UWOUT"/*.o "$UWOUT"/libunwind.a
UWCF="--target=aarch64-linux-android$API -O2 -I $UW/include -I $UW/src -D_LIBUNWIND_IS_NATIVE_ONLY"
clang() { "$NDKBIN/clang" "$@"; }
clangxx() { "$NDKBIN/clang++" "$@"; }
(
  cd "$UW/src"
  clangxx $UWCF -std=c++17 -c libunwind.cpp -o "$UWOUT/libunwind.o"
  clangxx $UWCF -std=c++17 -c Unwind-EHABI.cpp -o "$UWOUT/EHABI.o"
  clang   $UWCF -std=c99  -c UnwindLevel1.c -o "$UWOUT/L1d.o"
  clang   $UWCF -std=c99  -c UnwindLevel1-gcc-ext.c -o "$UWOUT/L1g.o"
  clang   $UWCF -c UnwindRegistersSave.S -o "$UWOUT/Save.o"
  clang   $UWCF -c UnwindRegistersRestore.S -o "$UWOUT/Restore.o"
)
"$NDKBIN/llvm-ar" rcs "$UWOUT/libunwind.a" \
    "$UWOUT/libunwind.o" "$UWOUT/EHABI.o" "$UWOUT/L1d.o" "$UWOUT/L1g.o" "$UWOUT/Save.o" "$UWOUT/Restore.o"
cp "$UWOUT/libunwind.a" "$SYSROOT/usr/lib/aarch64-linux-android/libunwind.a"

# --- 2. stub archives (harmless when unreferenced) ---
echo 'void __spk_stub(void){}' > "$B/stub.c"
"$NDKBIN/clang" --target=aarch64-linux-android$API --sysroot="$SYSROOT" -c "$B/stub.c" -o "$B/stub.o"
for l in ssp_nonshared pthread pthreads; do
  "$NDKBIN/llvm-ar" rcs "$B/lib$l.a" "$B/stub.o"
  cp "$B/lib$l.a" "$SYSROOT/usr/lib/aarch64-linux-android/lib$l.a"
done

# --- 3. engine + host ---
HOST_VERSION="${SPEKTRALAB_HOST_VERSION:-0.1.0+android}"
cmake -S "$REPO/engine" -B "$B/build" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$PORT/android-toolchain-ndk.cmake" \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_CXX_FLAGS="-D_LIBCPP_ENABLE_EXPERIMENTAL" \
  -DSPEKTRALAB_BUILD_NATIVE_RAW=ON -DSPEKTRALAB_BUILD_HOST=ON \
  -DSPEKTRALAB_BUILD_DESKTOP=OFF -DSPEKTRALAB_REQUIRE_VULKAN=ON \
  -DSPEKTRALAB_BUILD_TEST_DLL=OFF -DBUILD_TESTING=OFF \
  -DSPEKTRALAB_HOST_VERSION="$HOST_VERSION" -Wno-dev
ninja -C "$B/build" host/spektralab-host
"$NDKBIN/llvm-strip" "$B/build/host/spektralab-host"

# --- 4. stage ---
rm -rf "$B/stage"
mkdir -p "$B/stage"
cp "$B/build/host/spektralab-host" "$B/stage/"
cp -R "$B/build/resources" "$B/stage/engine"
ls "$B/stage/engine/vulkan/"*.spv >/dev/null
ls "$B/stage/engine/io/sRGB.icc" >/dev/null
echo "staged $B/stage"
