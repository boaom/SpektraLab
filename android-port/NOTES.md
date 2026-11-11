# SpektraLab Android 移植笔记（2026-11-11）

## 交付物
- `build/stage-android/` — 可部署 bundle：`spektralab-host`(3.3MB, aarch64, API 30, 静态 libc++/LibRaw/engine，仅依赖 libm/libdl/libc) + `engine/`(baked 常量、28 组胶片/相纸 profile、34 个 vulkan/*.spv、io/sRGB.icc) + licenses/
- 设备部署位置：`/data/local/tmp/spektralab`（root/shizuku 运行）
- 运行：`./spektralab-host --resources /data/local/tmp/spektralab/engine`，stdin/stdout 二进制帧协议见 desktop/HOST-PROTOCOL.md（u32 LE header_len + u32 LE payload_len + JSON）

## 实测（Redmi K60 / Android 13 / Adreno 730）
- engine up 30-175ms；hello 24 个方法
- probe 50MP JPEG：9248×6944，EXIF 全解析
- 4MP(1736×1303) full render + Portra400/Endura：**1.4s**，出图正常
- 8.6MP(3600×2703) full render：11.5s（Dekker 路径超线性）
- 50MP：open OK（decode 1.1s），render 拒绝 —— sep_fir_acc 缓冲 W×H×12B > Adreno maxStorageBufferRange(128MiB)。**全特效全渲染上限 ≈11MP**（128MiB/12B）
- 胶片选择实测传播：portra400(暖柔) vs ektar100(饱和冷峻) 出图肉眼可辨
- 不支持特性（非 Apple 后端一致）：digital_intermediate / scene_latitude / contrast_mask / overscan / date_imprint / scan_hdr

## 工具链配方（build/build-android.sh 可复现）
- **Alpine clang19(上游 LLVM 19, musl) + NDK r28b bionic sysroot**。官方 NDK 无 linux-aarch64 host 版；sysroot/runtimes 与 host 无关，zip 里只解 sysroot + lib/clang/*/lib/linux/*android*
- clang 资源目录放 `libclang_rt.builtins-aarch64-android.a`
- sysroot usr/lib/aarch64-linux-android/ 补：`libunwind.a`(自编) + 空壳 `libssp_nonshared.a`/`libpthread.a`/`libpthreads.a`（Alpine clang 链接时无条件 -lssp_nonshared；FindThreads 要 -lpthreads；bionic 自带）
- toolchain 文件：CMAKE_SYSTEM_NAME=Linux + `--target=aarch64-linux-android30` + `-nostdlib++` 链接 `-lc++_static -lc++abi`；CXX 加 `-D_LIBCPP_ENABLE_EXPERIMENTAL`（jthread/stop_token 被门在 experimental 后，全内联，无需额外库）
- API level 30：cam16.cpp 用 std::jthread

## 🔴 坑位记录
1. **C++ 异常必崩，除非 libunwind 编译带 `-D_LIBUNWIND_IS_NATIVE_ONLY`** —— 缺它时 PAC 签名返回地址（Adreno/bionic/NDK 编译的 libc++abi）直接 return UNW_ECROSSRASIGNING(-6550)，`failed_throw`→SIGABRT。上游 cmake 默认定义该宏。症状：最小 throw/catch 程序也 134
2. **llvm-ar rcs 是追加** —— 归档已存在时不删旧成员，陈旧 .o 抢符号（_Unwind_RaiseException 走了无插桩旧版本）。重建归档必须先 rm
3. **Adreno 的 GLSL.std.450 Fma 降级为 mul+add**（两次舍入，残差恒 0）→ 引擎数学探针默认拒 GPU。已 patch vulkan_gpu.cpp：zero-residual 时 GPU 也接受，走 Dekker 精确乘积特化路径（lavapipe 同款），math_mode 如实上报。见 build/android-port-vulkan-gpu.patch
4. **host 的 stdin EOF 即退出**（reader quit 不等 worker 队列清空）——文件重定向喂帧时最后一帧响应丢失。测试手法：远端 `sh -c "cat frames.bin; sleep N" | host ...`。真实 UI（APK ProcessBuilder 常开管道）无此问题
5. **Shizuku exec 不透传 stdin** —— 用远端文件重定向
6. **cmake 重跑不刷 _INIT 缓存值** —— 改 toolchain 后要显式 -DCMAKE_*_FLAGS 或删 build 目录
7. wire 参数是**扁平键名**（params_schema 的 name：film_stock / print_stock / halation_active / preview_long_edge），不是嵌套路径
8. session id 每次进程从 "s1" 起（g.next_session=1）
9. Android 上 `std::filesystem`、`/proc/self/exe` 正常；LibRaw 0.22.2 编译零修改

## 下一步（APK 阶段）
- aapt2 需要 glibc（Debian PRoot 内跑），d8/apksigner 走 Alpine openjdk；UI 建议宿主 Activity + ProcessBuilder 常开管道驱动 host
- 大分辨率策略：preview_long_edge 默认 2048~2560（秒级），全渲染按 11MP 上限自动降采样或提示关 halation
