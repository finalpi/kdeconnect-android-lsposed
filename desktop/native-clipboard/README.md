# 原生图片剪贴板插件

这是 KDE Connect 原有 `kdeconnect_clipboard.dll` 的源码修改，不增加独立程序、后台进程或启动项。

基于 KDE Connect `v26.08.2` 的剪贴板插件，复用 `kdeconnect.clipboard.file` 数据包。Android 为自动截图发送 `isImage=true`；本插件将图片解码后写入系统图像剪贴板，并清理自己的临时传输文件。普通文件剪贴板行为保留。

图片传输期间若用户又复制了其他内容，旧图片不会覆盖新剪贴板；多张图片并行到达时仅最新请求可以更新剪贴板。发送方或图片无效、尺寸过大、传输失败时不会把图片路径写入剪贴板。

## 已验证运行时

- KDE 官方 `26.08.2` Windows 安装包，其注册表版本标记为 `26.08.1`
- Qt `6.11.1`、KDE Frameworks `6.30.0`、MSVC 2022 x64
- `runtime-manifest.json` 固定对应的 `kdeconnectcore.dll` SHA-256，防止将这个插件直接替换到 ABI 不匹配的客户端。

## 构建

使用匹配版本的 Qt 开发包、KCoreAddons/KGuiAddons 等公共头文件和 KDE Connect `v26.08.2` core 源码。在 x64 MSVC 开发者终端运行：

```text
python build_plugin.py --qt <Qt根目录> --frameworks <Frameworks源码父目录> --kde-source <KDE源码目录> --output <输出目录>
```

脚本从当前安装的 DLL 导出符号生成导入库，只编译现有剪贴板插件。Qt、Frameworks 的头文件须与目标运行时匹配。构建后先验证插件可加载，再覆盖部署。

## 安装与恢复

将编译出的 `kdeconnect_clipboard.dll` 与 `install-plugin.ps1`、`runtime-manifest.json` 放在一起，用管理员 STA PowerShell 运行一次：

```powershell
powershell.exe -NoProfile -STA -File .\install-plugin.ps1
```

安装脚本先核对 core 哈希，再备份旧插件并短暂重启 KDE Connect。配对证书和配置不会被替换。恢复时可将备份的 DLL 放回原路径。官方客户端再次升级可能覆盖此插件；运行时改变后须重新构建，不应绕过 ABI 检查。

原插件版权及 GPL 许可声明保留。新增修改按 GPL-3.0-only 发布。
