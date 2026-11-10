# 桌面源码已迁移

图片剪贴板接收已直接合入 [KDE Connect 桌面 fork](https://github.com/finalpi/kdeconnect-kde) 的 `plugins/clipboard` 源码。以后使用[桌面 fork 的完整 Windows 安装包](https://github.com/finalpi/kdeconnect-kde/releases)，不再单独分发或安装 DLL。

构建方式、兼容范围与源码说明见[桌面说明](https://github.com/finalpi/kdeconnect-kde/blob/codex/native-image-clipboard/NATIVE-CLIPBOARD.md)。当前完整安装包重新编译该 fork 的原生剪贴板组件，并复用固定版本的官方客户端与依赖。

早期 DLL 安装方式及对应源码保留在 Android 仓库的 [`v1.37.2-lsposed.4` 标签](https://github.com/finalpi/kdeconnect-android-lsposed/tree/v1.37.2-lsposed.4/desktop/native-clipboard) 中。
