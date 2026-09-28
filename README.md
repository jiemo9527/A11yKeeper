# 无障碍保持 · A11yKeeper

面向 ColorOS 的 LSPosed 模块：选择需要保持开启的无障碍服务，对已锁定的服务检查关闭、未绑定和崩溃状态，必要时尝试恢复。未锁定的服务不会自动处理。

## 使用

1. 从 [Releases](https://github.com/jiemo9527/A11yKeeper/releases) 下载并安装 APK。
2. 在 LSPosed 中启用模块，作用域选择「系统框架」（`android`），然后重启手机。
3. 打开「无障碍保持」，确认顶部显示模块已生效，在服务页锁定需要保持的服务。
4. 服务页支持关键词搜索、已锁定/未锁定、已开启/未开启和异常筛选；系统服务默认隐藏，可展开。
5. 日志和设置分别位于独立页面。设置页可隐藏桌面图标；隐藏后从 LSPosed 管理器的模块设置入口打开。

**注意：** Android 包名从 v0.4.1 起为 `io.github.jiemo9527.a11ykeeper`，旧包 `com.jiemo.a11ykeeper` 不能直接覆盖升级。先在旧模块中解除锁定并停用旧模块，重启后再卸载旧包、安装并启用新版；`/data/system/a11ykeeper_pinned.txt` 是共用的旧配置，新版会继续读取，切换时请核对列表，避免两个模块同时守护。`jiemo9527.github.io` 是 GitHub Pages 网页地址，并非 APK 包名，也无需创建个人主页仓库来使用本模块。

这是基于系统内部实现的模块，ColorOS/Android 更新可能改变系统类和字段。自动修复不保证能对抗其他应用持续强制停止服务；异常状态会显示在列表并尝试弹出提示。v0.4.1 是 debug 签名的测试包，编译通过，尚未在装有本模块的实体设备上验证系统框架 Hook、强制停止保护和 4 秒提示。请先备份重要数据，确认 LSPosed 可恢复再测试。

## 本地构建

需要 JDK 17、Android SDK（platform 35、build-tools 35.0.0）和 Gradle 8.7。在工程根目录创建 `local.properties`，填写本机 `sdk.dir`，然后运行 `gradle assembleDebug`。输出位于 `app/build/outputs/apk/debug/app-debug.apk`。如需覆盖安装 Release 中的 debug APK，签名必须一致；不同机器生成的默认 debug 密钥可能不同。

## 原理

模块运行于 `system_server`，读取无障碍管理服务的内部状态，只为锁定的服务恢复开关；假死时尝试先关闭再重新开启。对反复被强制停止的服务有恢复限流。配置保存于 `/data/system/a11ykeeper_pinned.txt`。这是实验性实现，不保证持续运行或适配所有 ColorOS 版本。
