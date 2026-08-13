# HyperSettingsTileExt

一个面向小米 HyperOS 的 Xposed 模块，用于扩展控制中心“设置”磁贴的长按功能。

## 技术信息

- Android Gradle Plugin 9.3.1
- compileSdk / targetSdk 37
- minSdk 34
- Java 11 字节码
- libxposed API 102
- Xposed 最低 API 101

## 风险提示

本模块会加载到 SystemUI 进程。错误的兼容性判断可能导致控制中心异常或 SystemUI 反复重启，使用前请确保你了解如何在故障时停用 Xposed 模块。建议在升级 HyperOS 后先确认兼容性，再继续启用。

本项目与小米、Redmi 或 HyperOS 官方无关。

## 许可证

本项目基于 [Apache License 2.0](LICENSE) 开源。
