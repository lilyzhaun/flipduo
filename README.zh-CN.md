# FlipDuo

[English](README.md)

FlipDuo 是面向小米 MIX Flip 系列外屏的 LSPosed 模块及配套设置应用。本仓库提供 **0.6.138（149）** 的生产源码与必要构建文件，应用现有界面文案保留。

## 功能

- 自定义外屏导航控件、状态指示及通知／活动显示。
- 支持应用旁侧的纯黑或实时模糊背景。
- 按应用设置全屏，以及自定义外屏最近任务。
- 可选通知全宽与带壳外屏截图。
- 可命名的本机外观方案，以及按Android用户选择已启用的外屏输入法。

具体功能取决于系统固件和已启用的模块作用域。全屏内容可能延伸至物理镜头遮挡区域。

## 兼容目标

目标环境为初代 MIX Flip（`ruyi`）HyperOS 3，以及 MIX Flip 2（`bixi`）HyperOS 3／4。固件相关行为需要在对应设备上验证，兼容目标不代表每项功能已在所有固件上通过验收。

本版本已获MIX Flip 2／HyperOS 4用户验收；初代HyperOS 3以ROM接口核对为依据，MIX Flip 2／HyperOS 3仍待独立固件及实机验证。实时模糊仍属试验功能，表现可能随视频内容和固件变化。

当前不包含微信输入法专项适配。旧微信选择保留，系统框架加载新版策略后交回原生或其他模块；仅更新APK不会替换旧宿主内已加载的Hook，也不会自动撤销框架已有作用域。

运行模块需要兼容的已 Root 设备，以及支持 libxposed API 101 或更新版本的 LSPosed 实现。管理应用使用 libxposed service API 102，并对需要较新框架的能力进行检查。

## 构建

需要JDK 17或随仓库Gradle版本支持的更高版本、Android SDK Platform 37（Android 17）、SDK Command-line Tools，以及Android Gradle插件要求的Build-Tools。首次构建需访问Google Maven、Maven Central和Gradle分发服务器。

将 `ANDROID_HOME` 指向自己的 Android SDK；也可以新建不提交的 `local.properties`，填写 `sdk.dir=<自己的SDK目录>`。

Gradle Wrapper固定使用9.6.1并校验分发包摘要；依赖版本见Gradle构建文件。

`lint.xml`仅对列明的LSPosed宿主接入类中有意使用的非SDK反射豁免`BlockedPrivateApi`检查，其他Lint检查继续启用。

```sh
./gradlew :app:assembleRelease --no-daemon --max-workers=1
```

生成的未签名APK位于：

```text
app/build/outputs/apk/release/app-release-unsigned.apk
```

安装前请在本地使用自己的密钥签名。使用不同签名的已有安装不能直接覆盖升级。

如需由本机调试密钥签名的开发包：

```sh
./gradlew :app:assembleDebug --no-daemon --max-workers=1
```

本源码发行不包含私有签名材料、测试源码、设备转储或开发日志；构建应用不需要这些文件。

## 目录

| 路径 | 内容 |
| --- | --- |
| `app/src/main/` | 管理应用、Android资源、清单及随包许可证 |
| `native-hook/src/` | Java模块实现及共享运行时代码 |
| `native-hook/resources/` | 模块入口与作用域声明 |
| `gradle/wrapper/` | 官方Gradle Wrapper与固定版本配置 |
| `licenses/` | 补充许可证全文 |

## 对应版本源码

请使用与收到的APK版本相对应的源码标签。本版本为 [`v0.6.138`](https://github.com/lilyzhaun/flipduo/tree/v0.6.138)，已签名APK见[对应Release](https://github.com/lilyzhaun/flipduo/releases/tag/v0.6.138)，此前源码标签继续保留。

公开构建配置移除了私有仪器测试依赖和本机SDK路径，提供构建该版本所需的生产源码；它不承诺逐字节复现发布者签名后的APK。

## 许可证与来源

本项目包含GPL-3.0-only代码，以及从 [parallelcc/MixFlipMod](https://github.com/parallelcc/MixFlipMod) 移植的AGPL-3.0-only代码。两部分保留各自许可证，按两份许可证第13条组合。完整条款和来源见 [LICENSE](LICENSE)、[AGPL-3.0-only](licenses/AGPL-3.0-only.txt) 和 [NOTICE.md](NOTICE.md)。

分发受这些许可证覆盖的APK，需要履行对应源码及版权声明义务；应提供修改后应用的对应源码，仅提供上游项目链接并不充分。
