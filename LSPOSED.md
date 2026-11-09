# KDE Connect LSPosed

这是 KDE Connect Android 的个人 fork。一个 APK 同时提供 KDE Connect 应用和 LSPosed API 102 模块，不申请 `su` 权限，也不需要另装 WhiteClip。

## 1.37.2-lsposed.2 修复

修正 API 102 的系统服务作用域：`system_server` 必须使用 `system`，上一版仅写 `android` 会导致系统 hook 无法加载。升级后请重新确认模块作用域并重启；同时恢复 Android 普通剪贴板监听作为前台回退。

## 安装与设置

1. 安装本 fork 的 APK。包名为 `org.kde.kdeconnect_tp.lsposed`，可与官方版共存；配对和设置需要重新配置。调试包另有 `.debug` 后缀。
2. 在 LSPosed 中启用 **KDE Connect LSPosed**，作用域选 **系统服务（system）**，然后重启手机。模块不支持热重载，更新模块后也需要重启。
3. 打开应用，在设置中开启“通过 LSPosed 同步剪贴板”和“熄屏后继续同步”。重新进入设置页，确认“系统 hook 已连接”。
4. 允许应用的常驻通知、电池优化豁免，以及 ROM 要求的自启动或无限制后台运行。应用内“允许后台持续运行”可打开系统设置。熄屏保活会增加耗电。
5. 与电脑重新配对，为对应设备启用“共享剪贴板”。测试时暂停官方版的剪贴板同步，避免两个应用互相同步。

不再需要给 KDE Connect 配置 WhiteClip。如 WhiteClip 还用于其他应用，可保留它；请勿同时给本 fork 叠加多个剪贴板模块来判断兼容性。

## XposedSmsCode 验证码

保持 XposedSmsCode 正常启用，并开启它的“复制到剪贴板”。本 fork 不读取短信数据库，也不订阅其内部私有广播；在系统提交剪贴板写入后，直接接收写入的文本。因此，验证码在锁屏时写入也不需要 KDE Connect 再次调用受锁屏限制的 `getPrimaryClip()`。

已核对 XposedSmsCode 当前 beta 分支及其引用的 core 源码：复制路径直接调用 `ClipboardManager.setPrimaryClip()`，没有在这条路径上要求先点亮屏幕或解锁。手机上安装的其他版本仍需实测。

已配对设备的“跳过敏感剪贴板”仍生效。如果验证码被来源应用标为敏感内容，开启此选项会阻止同步。需要同步此类验证码时，在对应电脑的剪贴板插件设置中关闭它。新增通道不记录剪贴板文本或验证码日志，不将内容写入磁盘。

## 兼容范围与实现

- 系统模块需要 **Android 14+、LSPosed API 102**。较低版本仍可使用上游的普通 KDE Connect 功能，但不能启用此模块通道。
- 已核对 AOSP Android 14、15、16 及当前主线的剪贴板提交路径。按方法和字段结构发现接口，避免硬编码 `clipboardAccessAllowed` 的某个参数列表；兼容 `userId/deviceId` 及 `mUserId/mDeviceId` 字段命名。
- 当前仅处理 Android 主用户（user 0）及默认设备剪贴板（device 0）。工作资料、其他用户、Android Auto 或虚拟设备的独立剪贴板不会被强行合并；不修改系统的全局剪贴板隔离规则。
- 模块在系统完成写入后，将文本交给独立工作线程，再经仅接受系统 UID 的 Provider 推送给本应用。调用前核对目标 APK 与当前模块的签名。系统剪贴板锁内不发网络包、不跨进程调用应用 Provider。
- 文本仍经过 KDE Connect 原有的配对、加密和剪贴板插件设置。重复内容和本应用写入的回声会被过滤；系统清空剪贴板时清除本地缓存，避免重连时再次发送已清除的验证码。
- “熄屏后继续同步”在本地网络可用且模块同步开启时持有 CPU/Wi-Fi 锁；关闭开关、断开全部本地网络或停止后台服务会释放。不会点亮屏幕，也不全局关闭 Doze。
- OEM 修改了提交路径、后台服务被系统强停、网络断开或来源插件没有实际写入剪贴板时，不能保证同步。方法结构不支持时不放宽系统权限，设置页会显示通道未连接。

Android 17/API 37 构建与单元测试不等于 Android 17 真机兼容性认证。当前没有连接手机，锁屏短信、深度待机和 OEM ROM 场景仍需真机验收。

## 构建

使用 JDK 21、Android SDK 37 和仓库自带的 Gradle wrapper：

```sh
./gradlew assembleDebug testDebugUnitTest --tests 'org.kde.kdeconnect.plugins.clipboard.*'
```

`.github/workflows/lsposed-build.yml` 会构建调试 APK 并运行剪贴板相关测试。调试包用于开发测试；正式发布包应使用自己的私有签名，不能用仓库公开的上游调试密钥冒充正式签名。

本 fork 支持通过环境变量 `KDE_SIGNING_KEYSTORE`、`KDE_SIGNING_PASSWORD` 构建签名 release，密钥别名为 `kdeconnect-lsposed`。不要将密钥或密码提交到仓库。

```sh
./gradlew assembleRelease
```

## 真机验收

- 普通应用后台复制文本，电脑收到一次；电脑复制文本，手机收到且不回传循环。
- 手机锁屏熄屏，向其发送一条真实验证码短信，确认 XposedSmsCode 已复制，并在电脑核对验证码。
- 分别测试敏感内容过滤开启与关闭，确认设置生效。
- 熄屏待机至少 15 分钟后再次测试短信；测试 Wi-Fi 断开重连及手机重启后的模块状态。
- 清空手机剪贴板后重连电脑，确认不会重新上传已清除的验证码。

## 参考源码

- [KDE Connect Android](https://github.com/KDE/kdeconnect-android)
- [WhiteClip](https://github.com/entr0pia/xposed-clipboard-whitelist)：系统白名单机制的对照，本 fork 没有复制其源码。
- [XposedSmsCode CopyToClipboardAction](https://github.com/magisk317/XposedSmsCode/blob/beta/hook/src/main/kotlin/io/github/magisk317/smscode/xp/hook/code/action/impl/CopyToClipboardAction.kt)
- [其引用的 ClipboardUtils 源码](https://gitlab.com/magisk3171/shared/magisk-smscode-core/-/blob/7bb4ded3fcce497f76787ca6d8b98c22db68b409/runtime/src/main/java/io/github/magisk317/smscode/runtime/common/utils/ClipboardUtils.kt)
- [libxposed API](https://github.com/libxposed/api)
- [AOSP ClipboardService](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/clipboard/ClipboardService.java)
- [scrcpy ClipboardManager](https://github.com/Genymobile/scrcpy/blob/master/server/src/main/java/com/genymobile/scrcpy/wrappers/ClipboardManager.java)：后台调用与锁屏读取限制的对照。

上游许可证和版权声明保留不变；本 fork 新增模块代码采用 GPL-3.0-only。
