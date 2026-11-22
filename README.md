# EchoMusic TV

EchoMusic TV 是面向 Android TV 的音乐播放器。遥控器可浏览首页、发现、排行榜、搜索和资料库，并操作播放队列、同步歌词、音质和 MV。r54 增加了可打断的焦点与滚动过渡，并改善了歌词和沉浸模式切换。

## 项目结构

- `tv-ui/` 提供 Vue 3 电视界面、焦点导航、Bridge v1 契约测试和前端构建。
- `tv-client/` 提供 Kotlin Android 应用、Media3 后台播放、酷狗概念版扫码登录，以及 JVM 和 Android TV 测试。

Android 应用构建时将界面打包到本地 WebView，通过 Bridge 调用原生功能。访问在线内容由 Android 客户端处理。账号凭据由 Android 私有存储及 Keystore 管理。

## 构建

需要 JDK 17、Android SDK 35、Node.js 22.14 或更高版本和 pnpm 10。从 Git 克隆后，在 Android Studio 中打开 `tv-client/`，或配置 `ANDROID_HOME` 指向本机 SDK。

```bash
cd tv-ui
pnpm install --frozen-lockfile
pnpm test:tv

cd ../tv-client
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Windows PowerShell 使用 `./gradlew.bat`。Debug APK 位于 `tv-client/app/build/outputs/apk/debug/`。如需运行遥控器和后台恢复回归，连接 Android TV 模拟器后执行 `./gradlew :app:connectedDebugAndroidTest`。

Gradle 默认读取相邻的 `tv-ui/` 目录。也可通过 `-PechoMusicDir=/path/to/tv-ui` 指定其他 Git 工作树。开发构建使用本机 Android 调试证书，覆盖安装已发布包需要与原包相同的签名。

## 来源和许可

电视界面基于 hoowhoami 的 EchoMusic，来源代码基线为 `0fa10a3578f242b6d291f882156d07fa24f22367`，并包含后续 TV 开发修改。保留 GPL-3.0 许可证及 KuGouMusicApi 的 MIT 许可。Gradle Wrapper、应用资源和自动化测试夹具随源码保留。

- [EchoMusic 上游](https://github.com/hoowhoami/EchoMusic)
- [KuGouMusicApi 上游](https://github.com/MakcRe/KuGouMusicApi)
- [GPL-3.0](LICENSE) · [KuGouMusicApi MIT](LICENSE-KuGouMusicApi)
