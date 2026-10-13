# ADB 常亮 / ADB Awake

一个用于无线 ADB 调试的 Android 常亮工具，包含 Android APK、手机端 ADB shell 辅助进程和 macOS 自动启动工具。

## 功能

- 实际无线 ADB 连接时保持屏幕点亮，断开约 3 秒后释放常亮。
- 自动、强制常亮、关闭三种模式，支持快捷设置磁贴。
- 开发者选项一键启用与打开；1.1 版通过一次性设置授权独立工作。
- 无线调试设置入口，适配 ColorOS 17 的非导出详情页面。
- 尊重电源键主动休眠，不解锁手机。
- 没有广告、统计或云服务；APK 的网络通信只连接本机辅助进程。

**实机验证：OPPO PMX110，Android 17 / ColorOS 17。其他系统需要适配和实测。**

## 安装和授权

下载仓库中的 [ADB-Awake.apk](ADB-Awake.apk)，或从 Releases 下载 1.1 版。

```sh
adb install -r ADB-Awake.apk
adb shell pm grant cn.local.adbawake android.permission.WRITE_SECURE_SETTINGS
```

设置权限用于 APK 自行开启开发者开关，避免关闭开发者选项后辅助进程不可用时入口失效。
普通更新、手机重启保留授权；卸载重装需重新授权。部分 ColorOS 版本会拒绝 ADB 授权，需在手机设置中临时允许授权，完成后恢复权限监控，不能假定所有设备都能直接授予。

不依赖 root。APK 声明 INTERNET 和 WRITE_SECURE_SETTINGS；后者需上述一次性授权。

## 启动手机辅助进程

先通过无线 ADB 配对并连接手机，然后：

```sh
adb push ADB-Awake.apk /data/local/tmp/adbawake.apk
adb shell 'CLASSPATH=/data/local/tmp/adbawake.apk nohup app_process /system/bin --nice-name=adbawake.helper cn.local.adbawake.Daemon >/data/local/tmp/adbawake-daemon.log 2>&1 </dev/null &'
```

辅助进程独立于界面工作，读取实际配对设备的无线连接标记。它通过亮屏锁和仅对已亮屏幕执行的 PowerManager.userActivity 调用保持屏幕点亮。断开后恢复系统正常熄屏规则，不修改 screen_off_timeout。

手机重启会结束辅助进程；再次连接后需重新启动。Mac 工具可以自动完成这一步。

## macOS 自动启动

需要 Python 3 和 Android SDK platform-tools。首先确定目标手机序列号：

```sh
adb shell getprop ro.serialno
python3 tools/watch.py --device YOUR_DEVICE_SERIAL --install
```

序列号保存到本地忽略文件 `device.json`。公共源码不绑定任何个人手机。

工具安装到 `~/Library/Application Support/ADB-Awake`，登录后每 10 秒检查目标手机是否已经通过无线 ADB 连接，必要时启动辅助进程。它不执行主动连接，但 ADB 自身的 mDNS 自动重连仍可能生效。可以使用 `ADB` 环境变量指定 adb 路径。

```sh
python3 tools/watch.py --uninstall
```

关闭电脑自动启动不会关闭手机当前的常亮，请在应用中关闭。日志在 `~/Library/Logs/ADB-Awake`。更新工具或安装包后重新执行 `--install`。

## 编译

需要 Android SDK platform 36、build-tools 36.0.0，以及 Java（已使用 Android Studio 内置 Java 验证）。macOS 默认读取 `~/Library/Android/sdk` 和 Android Studio 的 Java；其他环境设置 `ANDROID_SDK_ROOT` 与 `JAVA_HOME`。

```sh
bash tools/build.sh
```

构建缓存和新生成的本地签名密钥位于 `.build/`，不会进入版本控制。仓库内 APK 使用原始发布签名；自行构建会生成不同的本地签名，不能直接覆盖安装已有发布版。原始发布私钥没有上传。

## 验证与范围

见 [验证记录](VALIDATION.md)。1.1 修复了辅助进程不运行时开发者入口无法启用开关的问题。

“一键复制配对码和地址”目前只有可行性评估，尚未实现。配对端口与连接端口不同；初次尚未连接 ADB 的读取场景需要额外评估无障碍方案。

## 安全与清理

辅助进程控制端口只绑定 127.0.0.1，并使用随机令牌认证。启动提供者只接受 shell 或应用自身 UID。锁屏恢复缓存只允许 shell 读取，并绑定同一次安装；不保存配对码。

卸载前先关闭常亮，再关闭电脑自动启动，最后卸载应用。此工具使用系统隐藏接口及厂商设置页面，系统升级后可能需要适配。
