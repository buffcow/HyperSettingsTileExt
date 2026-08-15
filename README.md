# HyperSettingsTileExt

一个面向小米 HyperOS 的 Xposed 模块，用于扩展控制中心“设置”磁贴的长按功能。

A Xposed module for Xiaomi HyperOS that extends the Control Center Settings tile's long-press action.

## 何意味

- 米系统里有些日常需要用到但藏得又很深的功能，临时开关一次要在设置里点好几层
- 偶尔需要用到的磁贴但又不想其单独占用多一个控制中心磁贴位置

故写了这个模块，将上述开关或功能类聚到长按 “设置” 磁贴的 Dialog 里

## 功能

目前已实现的快捷开关：

| 分类    | 开关        | 功能                       | 长按入口           | 显示/可用条件                         |
|-------|-----------|--------------------------|----------------|---------------------------------|
| 服务    | Google 服务 | 启用或停用 Google 核心服务及相关系统组件 | Google 服务设置    | 仅受支持且核心组件完整的国内版 HyperOS         |
| 服务    | 隐身模式      | 暂停所有应用的相机、麦克风和定位权限       | 隐身模式设置         | 手机管家声明支持，且 SystemUI 磁贴接口兼容时显示   |
| 连接    | 小米互传      | 模拟原系统磁贴启用或停用小米互传         | 小米互传设置         | 安装并启用小米互传，且 SystemUI 磁贴接口兼容时显示  |
| 连接    | USB OTG   | 切换 USB OTG               | OTG 设置         | 设备和 HyperOS 私有接口支持时显示           |
| 连接    | USB 网络共享  | 启动或停止 USB 网络共享           | 网络共享设置         | 仅主用户可用；开启需要连接 USB，且不能受用户或企业策略限制 |
| 电池    | 无线反向充电    | 为支持的设备开启或关闭无线反向充电        | 无线反向充电设置       | 设备和 HyperOS 私有充电接口支持时显示         |
| 显示    | 保持亮屏      | 阻止屏幕自动熄灭                 | 无              | 需要设备已解锁，且不能受设备管理策略限制            |
| 显示    | 晕车缓解      | 启用或停用系统晕车缓解视觉效果          | 晕车缓解设置         | 仅系统与手机管家声明支持时显示                 |
| 开发者选项 | USB 调试    | 切换 USB 调试                | 开发者选项中的 USB 调试 | 需要开启开发者选项；部分状态要求设备已解锁           |
| 开发者选项 | 无线调试      | 切换无线调试，并在开启后显示连接地址       | 无线调试设置         | 需要开发者选项及设备支持；开启需要 Wi-Fi 和设备解锁   |

具体条目会根据设备硬件、HyperOS 版本、当前用户、系统权限及管理策略动态隐藏或禁用，因此不同设备上显示的开关可能不同。

> “Google 服务”会同时影响 Play 商店、Google Play 服务、Google 服务框架及相关系统组件，并可能同步修改第二空间中的对应组件。关闭前请确认你了解其影响。

小米互传通过原系统磁贴执行切换，首次开启时仍可能进入系统授权或 Wi-Fi 处理流程。

隐身模式通过手机管家的原系统磁贴执行切换，开启后会暂停所有应用的相机、麦克风和定位权限。

USB 网络共享以系统实际报告的共享状态为准，并保留系统的运营商授权或资费验证流程。

无线反向充电沿用 HyperOS 的低电量、充电冲突、保护壳及省电模式检查，首次开启时仍会显示原系统提示。

即使当前没有可用或已启用的开关，快捷控制对话框仍会保留入口。“设置”按钮短按执行系统原本的磁贴长按行为，长按则进入模块设置。

## 安装与使用

1. 从 [LSPosed 仓库](https://github.com/Xposed-Modules-Repo/cn.buffcow.hyperste/releases) 下载 APK 并安装。
2. 在 LSPosed 中启用模块。
3. 将模块作用域设置为“系统界面”（`com.android.systemui`）。
4. **重启设备。**
5. 下拉控制中心，长按“设置”磁贴进入快捷控制。
6. 如需调整模块行为或显示的功能，长按快捷控制对话框底部的“设置”按钮。

## 环境

- Android Gradle Plugin 9
- compileSdk / targetSdk 37
- minSdk 34 (Android14+)
- JDK 17
- libxposed API 101+

## 构建

### 本地构建

使用仓库内的 Gradle Wrapper：

```powershell
# 构建 Debug 包
.\gradlew.bat assembleDebug

# 构建 Release 包（需配置签名属性）
.\gradlew.bat assembleRelease -PandroidStoreFile="<path-to-keystore>" -PandroidStorePassword="<password>" -PandroidKeyAlias="<alias>"
```

构建产物位于 `app/build/outputs/apk/`。

### CI 自动构建

项目配置了 GitHub Actions 工作流：

- 推送至 `dev` 分支或手动触发工作流时，会自动触发 Release APK 构建。
- 在 GitHub Actions 中签名需在仓库配置以下 Repository secrets：
  - `KEYSTORE_BASE64`：Keystore 文件的 Base64 编码
  - `KEYSTORE_PASSWORD`：密钥库密码 / 密钥密码
  - `KEY_ALIAS`：密钥别名
- 构建成功后可在对应 Workflow Run 的 **Artifacts** 处下载 `app-release`（产物有效期保留 14 天）。

## 免责声明

本模块会加载到 SystemUI 进程。错误的兼容性判断可能导致控制中心异常或 SystemUI 反复重启，使用前请确保你了解如何在故障时停用 Xposed
模块。建议在升级 HyperOS 后先确认兼容性，再继续启用。

本项目与小米、Redmi 或 HyperOS 官方无关。

## 许可证

本项目基于 [Apache License 2.0](LICENSE) 开源。
