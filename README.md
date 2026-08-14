# HyperSettingsTileExt

一个面向小米 HyperOS 的 Xposed 模块，用于扩展控制中心“设置”磁贴的长按功能。

A Xposed module for Xiaomi HyperOS that extends the Control Center Settings tile's long-press action.

## 何意味

原因很简单：米系统里有些日常需要用到但藏得又很深的功能，临时开关一次要在设置里点好几层，于是就有了这个模块。

## 功能

目前已实现的快捷开关：

| 分类    | 开关        | 功能                       | 长按入口           | 显示/可用条件                         |
|-------|-----------|--------------------------|----------------|---------------------------------|
| 服务    | Google 服务 | 启用或停用 Google 核心服务及相关系统组件 | Google 服务设置    | 仅受支持且核心组件完整的国内版 HyperOS         |
| 连接    | USB OTG   | 切换 USB OTG               | OTG 设置         | 设备和 HyperOS 私有接口支持时显示           |
| 连接    | USB 网络共享  | 启动或停止 USB 网络共享           | 网络共享设置         | 仅主用户可用；开启需要连接 USB，且不能受用户或企业策略限制 |
| 显示    | 保持亮屏      | 阻止屏幕自动熄灭                 | 无              | 需要设备已解锁，且不能受设备管理策略限制            |
| 开发者选项 | USB 调试    | 切换 USB 调试                | 开发者选项中的 USB 调试 | 需要开启开发者选项；部分状态要求设备已解锁           |
| 开发者选项 | 无线调试      | 切换无线调试，并在开启后显示连接地址       | 无线调试设置         | 需要开发者选项及设备支持；开启需要 Wi-Fi 和设备解锁   |

具体条目会根据设备硬件、HyperOS 版本、当前用户、系统权限及管理策略动态隐藏或禁用，因此不同设备上显示的开关可能不同。

> “Google 服务”会同时影响 Play 商店、Google Play 服务、Google 服务框架及相关系统组件，并可能同步修改第二空间中的对应组件。关闭前请确认你了解其影响。

USB 网络共享以系统实际报告的共享状态为准，并保留系统的运营商授权或资费验证流程。

如果当前没有任何可用开关，模块会执行系统原本的“设置”磁贴长按行为。对话框中的“设置”按钮也会进入该原始系统入口。

## 安装与使用

1. 从 [LSPosed 仓库](https://github.com/Xposed-Modules-Repo/cn.buffcow.hyperste/releases) 下载 APK 并安装。
2. 在 LSPosed 中启用模块。
3. 将模块作用域设置为“系统界面”（`com.android.systemui`）。
4. **重启设备。**
5. 下拉控制中心，长按“设置”磁贴进入快捷控制。

## 环境

- Android Gradle Plugin 9
- compileSdk / targetSdk 37
- minSdk 34 (Android14+)
- JDK 17
- libxposed API 101+

## 构建

使用仓库内的 Gradle Wrapper：

```powershell
.\gradlew.bat assembleDebug
```

构建产物位于 `app/build/outputs/apk/debug/`。

## 免责声明

本模块会加载到 SystemUI 进程。错误的兼容性判断可能导致控制中心异常或 SystemUI 反复重启，使用前请确保你了解如何在故障时停用 Xposed
模块。建议在升级 HyperOS 后先确认兼容性，再继续启用。

本项目与小米、Redmi 或 HyperOS 官方无关。

## 许可证

本项目基于 [Apache License 2.0](LICENSE) 开源。
