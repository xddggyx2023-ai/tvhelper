# 电视助手 (TvHelper)

手机端管理智能电视的小工具。不 root、不装电脑端 adb，手机连同一个 Wi-Fi 就能跟电视的 ADB 服务直接通信：装应用、看应用列表、禁用/恢复预装应用。

面向不懂技术的用户：重点是"带路"（这台电视怎么开 ADB、每一步该看到什么、失败了怎么办），而不是再做一个 ADB 命令行。

## 当前状态：v0.1.0 技术验证版

| 功能 | 状态 |
|---|---|
| 读本机 Wi-Fi 地址 | 已验证（模拟器） |
| 扫描局域网 5555 端口、列出设备 | 已验证（模拟器） |
| 连接重试 + 等待电视端"允许调试"引导 | 界面已验证，真电视未验证 |
| 自动读电视品牌/型号/系统版本 | 未验证（需真电视） |
| 装 APK 到电视 | 未验证（需真电视） |
| 应用列表 / 禁用 / 恢复 / 卸载 | 未验证（需真电视） |
| 换桌面、故障诊断、本地知识库、智能引导 | 未做 |

没在真电视上跑过的功能，都不当作可用。

## 技术栈

- Kotlin + Jetpack Compose，minSdk 24（Android 7），targetSdk 35
- ADB 客户端：[Kadb](https://github.com/flyfishxu/Kadb) 2.1.1（Apache-2.0）
- 发现方式：局域网 5555 端口并发扫描（老电视不广播 mDNS）
- 权限只有 4 个：INTERNET / ACCESS_NETWORK_STATE / ACCESS_WIFI_STATE / CHANGE_WIFI_MULTICAST_STATE

## 编译

需要 JDK 17 + Android SDK（platform 36、build-tools 36）+ Gradle 8.11.1。

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
gradle assembleDebug
```

`local.properties` 里写 `sdk.dir=<你的 sdk 路径>`（用正斜杠）。

已知坑：

1. Kadb 2.1.2+ 要求 compileSdk 37，AGP 8.x 最高支持 36 → 锁在 2.1.1。
2. Kadb 2.1.1 用 Kotlin 2.3 编译，Kotlin 插件必须是 2.3.x，否则 metadata 不兼容。
3. Kadb 依赖 `com.github.Flyfish233:spake2-java`，只在 JitPack → 仓库要加 `https://jitpack.io`。
4. BouncyCastle/jspecify 有重复 `META-INF/versions/9/OSGI-INF/MANIFEST.MF` → 打包需 pickFirst。

## 安全边界

- 不做后台静默执行，所有 ADB 操作都由用户点按触发
- 系统关键包（桌面、SystemUI、设置等）不提供禁用按钮
- 禁用前二次确认，禁用后回读状态，失败要报失败
- 只禁用、不卸载系统应用；提供恢复
- ADB 密钥持久化在应用私有目录，避免每次启动重新弹授权

## 说明

本项目与小米、海信、TCL、创维、索尼等厂商无隶属关系，品牌名仅用于说明操作路径。
