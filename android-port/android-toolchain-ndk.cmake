# SpektraLab engine toolchain for the Android CI (official NDK r28b, x86_64 host).
# Same structure as the local Alpine recipe: explicit --target/--sysroot, static
# libc++, no cmake Android magic -- so local and CI builds behave identically.
set(ANDROID_NDK "$ENV{ANDROID_NDK}" CACHE PATH "" FORCE)
set(NDK_BIN "${ANDROID_NDK}/toolchains/llvm/prebuilt/linux-x86_64/bin")
set(NDK_SYSROOT "${NDK_BIN}/../sysroot" CACHE PATH "" FORCE)

set(CMAKE_SYSTEM_NAME Linux)
set(CMAKE_SYSTEM_PROCESSOR aarch64)
set(CMAKE_SYSROOT "${NDK_SYSROOT}")

set(CMAKE_C_COMPILER   "${NDK_BIN}/clang")
set(CMAKE_CXX_COMPILER "${NDK_BIN}/clang++")

set(CMAKE_C_COMPILER_TARGET   aarch64-linux-android30)
set(CMAKE_CXX_COMPILER_TARGET aarch64-linux-android30)

set(CMAKE_TRY_COMPILE_TARGET_TYPE STATIC_LIBRARY)

set(_spk_link "-fuse-ld=lld -nostdlib++")

set(CMAKE_C_FLAGS_INIT   "")
set(CMAKE_CXX_FLAGS_INIT "")
set(CMAKE_EXE_LINKER_FLAGS_INIT    "${_spk_link}")
set(CMAKE_SHARED_LINKER_FLAGS_INIT "${_spk_link}")
set(CMAKE_MODULE_LINKER_FLAGS_INIT "${_spk_link}")

# Static libc++ + LLVM libunwind (built by build-host.sh into the sysroot).
set(CMAKE_CXX_STANDARD_LIBRARIES "-lc++_static -lc++abi -l:libunwind.a" CACHE STRING "" FORCE)

# bionic has pthreads in libc; no separate libpthread.
set(THREADS_PREFER_PTHREAD_FLAG OFF CACHE BOOL "" FORCE)
