# SpektraLab for Android (`android-port` branch)

The SpektraLab engine on-device: a fork branch that builds `spektralab-host`
(the engine + LibRaw + resources, the same process the Linux/Windows desktop
app drives over `desktop/HOST-PROTOCOL.md`) for **Android aarch64**, packages
it into a self-contained APK, and renders film/print simulations on the phone's
Vulkan GPU. No Python, no server, nothing at run time but the bundled binary
and its 28 measured film profiles.

GitHub Actions builds the APK on every push to this branch: **Actions →
android-apk → artifact `spektralab-mobile-<sha>`**.

## What works (tested on Redmi K60 / Android 13 / Adreno 730)

- pick any image (HEIC/JPEG/PNG), pick a film and a paper, render at
  preview (2560px long edge, ~1-2 s) or full resolution
- 28 films × 9 print papers, defaults auto-paired per each profile's
  `info.target_print`
- save the render to `Pictures/SpektraLab` as JPEG
- the engine process runs as the app's sidecar over the stdin/stdout frame
  protocol; a crashed host is restarted transparently

## Constraints of the Adreno compute path

- the halation/glare blur (separable FIR) needs two W×H×12 B staging buffers;
  Adreno's `maxStorageBufferRange` is 128 MiB, so **full-render tops out at
  ~11 MP** — the app keeps picks under 10 MP and preview handles anything
- Qualcomm's Vulkan driver lowers GLSL.std.450 `fma` to mul+add (two roundings);
  `android-port/vulkan-gpu-unfused-fma.patch` accepts that and switches the
  double-float kernels to an exact Dekker product (the same path Mesa's
  lavapipe takes), reported as `math_mode`, never silent
- features the non-Apple engine refuses everywhere also refuse here:
  digital intermediate, scene latitude, contrast mask, overscan,
  date imprint, scan HDR

## How the build fits together

`android-port/build-host.sh` (in CI, NDK r28b):

1. builds **LLVM libunwind** for android from the vendored source
   (`android-port/libunwind-llvm`) with `-D_LIBUNWIND_IS_NATIVE_ONLY` —
   NDK r28 ships no `libunwind.a`, and without that macro every C++ exception
   aborts on ARMv8.3 PAC devices (the unwinder returns
   `UNW_ECROSSRASIGNING` instead of running `autia1716`)
2. drops it plus empty `libssp_nonshared`/`libpthread*` stubs into the NDK
   sysroot
3. builds `engine/` with `android-port/android-toolchain-ndk.cmake`
   (explicit `--target=aarch64-linux-android30`, static libc++ via
   `-nostdlib++ -lc++_static -lc++abi`, `-D_LIBCPP_ENABLE_EXPERIMENTAL`
   because `std::jthread` is gated behind it), LibRaw 0.22.2 from
   `engine/setup-deps.sh`, kernels compiled to SPIR-V by `glslangValidator`
4. stages the binary + baked resources the APK then carries as
   `lib/arm64-v8a/libspektralab_host.so` + `assets/engine/`

`android-port/build-apk.sh` assembles the APK without gradle:
`javac → d8 → aapt2 link → zip → zipalign → apksigner` (ephemeral keystore;
sign your own before distributing).

The app itself (`android-port/apk/java/.../MainActivity.java`) is a single
dependency-free Java activity: protocol framing in `ByteBuffer` little-endian,
JSON parsed by a ~60-line recursive-descent reader, catalog read from the
staged engine resources at run time.

## Install

Open the Actions run, download the artifact, install the APK (Android 8.0+;
the binary targets API 30). First launch unpacks the 23 MB engine assets to
app storage once.

## Notes and traps

Full port log with the five traps that cost real time (PAC/unwinder,
`llvm-ar rcs` append semantics, Adreno fma lowering, host EOF semantics,
cmake cache refresh): `android-port/NOTES.md`.

Licence: GPL-3.0-or-later, same as SpektraLab; film/paper profiles CC BY-SA 4.0.
