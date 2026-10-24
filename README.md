# ZPocket — ZCode 手机远程遥控（安卓 APP）

把 ZCode 桌面客户端官方的「手机连接远程操控」装进一个原生安卓 APP：
不用再打开手机浏览器，点开图标 → 扫码 → 直接在手机上操控/监督电脑端的 ZCode 干活。

## 下载安装

从 [Releases](https://github.com/Spark-Howard/ZPocket/releases) 下载最新 APK
直接安装（Android 8.0+，允许未知来源后覆盖安装会保留登录态和连接历史）。

## 工作原理

ZCode 官方手机远程是**云端中继**架构：电脑端和手机各自连到 `zcode.z.ai` 的 WebSocket
中继服务，扫码只是让手机知道该配对哪台电脑。手机浏览器打开的其实是官方网页
`https://zcode.z.ai/remote/v3?...`。

因此 ZPocket 采用 **WebView 壳 + 原生增强**：

- 远程界面 = 官方网页（界面和功能永远与官方同步，协议升级无需更新 APP）
- 原生部分负责：应用内相机扫码、连接历史、全屏沉浸体验、Cookie 持久登录

## 安装

1. 把 `app/build/outputs/apk/release/app-release.apk`（或 debug 版）传到手机
   （微信/网盘/数据线均可）。
2. 在手机上点开 APK，按提示允许「安装未知应用」。
3. 需要 Android 8.0（API 26）及以上。

## 使用

1. 电脑端 ZCode 打开远程控制，屏幕上出现二维码。
2. 手机打开 ZPocket → 点「扫码连接」→ 对准二维码。
   - 支持正色/反色二维码（深色主题下白码黑底也能扫）、任意方向；
   - 实在扫不出来时，扫码页底部有「粘贴链接连接」兜底入口（把二维码链接复制到剪贴板后点它）。
3. 首次使用会在 APP 内打开 zcode.z.ai 登录页，登录一次即可（之后 Cookie 记住登录状态）。
4. 之后不再需要扫码：
   - 默认**打开 APP 直接自动重连上次的设备**（主页有开关可关）；
   - 也可以在主页「连接历史」里点任意设备直连。
5. 重连生效的前提（官方云端中继架构决定）：电脑端 ZCode 在线且远程控制开启、
   手机登录态有效。桌面端二维码里的设备 ID 跨重启复用，历史记录长期有效；
   只有切换电脑端登录账号后才需要重新扫码。

远程页右上角菜单：刷新页面 / 重新扫码 / 清除登录数据 / 删除连接记录。
页面里触发的文件下载会交给系统下载管理器（保存到「下载」目录，自动带上登录态）。

## 项目结构

```
app/src/main/java/com/zpocket/app/
├── MainActivity.kt          # 入口 + 主题 + 外部链接(VIEW intent)接入
├── data/ConnectionStore.kt  # 连接历史存储（SharedPreferences + JSON）
└── ui/
    ├── AppRoot.kt           # 三个页面间的导航
    ├── HomeScreen.kt        # 主页：扫码入口 + 连接历史
    ├── ScannerScreen.kt     # 扫码页：CameraX 预览 + ZXing 离线识别 + 手电筒
    ├── RemoteScreen.kt      # 远程页：WebView 壳（UA 伪装 Chrome、Cookie 持久、返回键、外链跳转）
    └── AppIcons.kt          # 自绘矢量图标（避免引入超大 icons-extended 包）
```

## 命令行构建（无需 Android Studio）

环境要求：JDK 17+、Android SDK（`local.properties` 已指向 `~/Library/Android/sdk`）。

```bash
./gradlew assembleDebug    # 调试包 → app/build/outputs/apk/debug/
./gradlew assembleRelease  # 签名正式包 → app/build/outputs/apk/release/
```

签名：`zpocket-release.keystore`（alias `zpocket`，密码见 `gradle.properties`，个人自用）。

## 隐私与安全说明

- 二维码链接（含设备 sid/mid）与登录 Cookie 只保存在手机本地，APP 不上传任何数据。
- 扫码为 ZXing 本地识别，不依赖谷歌服务，无 GMS 手机（如华为）可用。
- 相机权限仅用于识别二维码，不做其他用途。

## 已知边界

- 远程功能依赖电脑端 ZCode 在线并保持远程控制开启（与官方网页版相同）。
- 若官方网页未来出现针对 WebView 的限制，可尝试更新手机系统 WebView（Chrome 内核）；
  APP 的 UA 已伪装为标准 Chrome 移动版，正常情况下不会被拦截。
