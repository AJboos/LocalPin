# 点位 LocalPin：安卓模拟定位与地图选点工具（免 Root）

作者：**AJ_boos** · 应用版本：**2.1**。

点位是一款支持地图选点的**安卓模拟定位工具**，可用于虚拟定位、GPS 模拟和 Android 应用定位测试。在 OpenStreetMap 地图上搜索或点击目标地点，即可通过系统模拟位置接口持续提交选点，无需手动查找和输入经纬度。

**无需 Root，需要在开发者选项中将“点位”选为模拟位置信息应用。** 最低支持 Android 12 / API 31；已有 Android 16 环境的历史测试记录。它适合学习 Android 定位接口、调试位置功能和进行地图选点实验。

LocalPin is a **no-root Android mock location app**, **GPS simulator** and **OpenStreetMap map picker**. Choose a location on the map to simulate GPS coordinates for location testing, without entering latitude and longitude manually. This fake GPS tool uses Android's standard mock-location APIs and requires selecting LocalPin as the mock location app in Developer options.

Source available for noncommercial use. Commercial use requires a separate paid written license.

**非商业用途免费；商业使用必须事先取得权利人的书面付费授权。** 源码可供查看和按许可修改，但采用非商业许可，不是 OSI 定义的开源软件。完整条款见 [LICENSE](LICENSE)，商业授权说明见 [COMMERCIAL.md](COMMERCIAL.md)。

## 功能与限制

- 拖动、双指缩放、双击或按钮缩放地图，点击确定目标位置。
- 同时向 Android 的 GPS、network 测试位置源持续提交位置；前台通知提供停止入口。
- 可调整刷新节奏、查看提交状态和本程序的系统位置回读。
- 停止时清理测试位置源和监听；运行期间使用带超时的 CPU 唤醒锁。
- 使用系统正式模拟位置接口，保留模拟位置标记；不修改真实基站、Wi-Fi、IP 或目标应用。

兼容性取决于手机系统和目标应用如何读取位置。历史 Android 16 实测中，正常情况下的实时位置共享由测试者确认跟随选点；
涉及：
过滤模拟位置：Android 会给测试位置添加模拟标记，应用可以通过 isMock() 识别。
采用其他定位结果：例如另一套定位服务，或结合 Wi-Fi、基站等信息计算的位置。
继续使用缓存：共享功能没有更新到新坐标。
的应用程序实时位置共享仍显示真实位置或旧位置，重新进入后也无法改善。**本版没有解决此类情况的实时位置共享。** 历史测试不能保证其他版本、设备或功能有效。

程序的“提交成功”与“核验匹配”只反映系统提交或本程序的回读，不能证明第三方应用已采用位置。本版没有接入 Google Play 融合定位模拟接口。

## 使用

1. 自行构建并安装，打开手机定位并授予点位所需的定位权限。
2. 在开发者选项的“选择模拟位置信息应用”中选择“点位”。不同厂商菜单名称可能不同。
3. 回到点位，加载地图，搜索或拖动到目标附近，轻点地图确定选点。
4. 点击“开始模拟”，保持前台服务正常运行，再在目标应用中实际核对位置。
5. 完成后点击“停止模拟”或通知中的停止按钮。

地图未加载时，可打开“核验”重试地图并查看错误；不能把空白地图上的图钉视为已准确选中建筑。地图服务能否访问取决于网络，本程序不会自动配置 VPN。

目标应用在停止后可能仍显示缓存。若清理报告异常，可将系统模拟位置应用改为“无”，必要时重启手机。系统强行终止进程时不保证执行本程序的清理回调。

## 构建

应用包名 `local.pin`，版本 `2.1`（versionCode 5），最低 Android 12 / API 31，编译与目标 API 36。无第三方运行时库。

### Windows PowerShell

准备 JDK 17、Android SDK Platform 36（`android.jar`）、Android SDK Build Tools 36.0.0：

```powershell
.\build-apk.ps1 `
  -JdkHome 'C:\path\to\jdk17' `
  -AndroidJar 'C:\path\to\android-36\android.jar' `
  -BuildTools 'C:\path\to\build-tools\36.0.0'
```

输出为 `dist/LocalPin-2.1.apk`。脚本不下载工具；首次执行会在本地生成 `.localpin-test.p12` 个人测试密钥，测试口令 `localpin-testing` 是公开值。此签名仅用于个人开发测试，不用于商店或正式产品发行。私钥和构建输出已在 `.gitignore` 中排除。

**原版 2.1 的签名私钥没有恢复。** 自行生成的证书与原版 APK 不同，通常不能直接覆盖已安装的原版。卸载会清除应用数据；需要保留数据时应先自行确认迁移方式。正式发行需另行配置自己妥善保管的发布签名。

### Android Studio / Gradle

用 Android Studio 打开项目目录。项目固定 Android Gradle Plugin 8.10.1；使用 JDK 17、Gradle 8.11.1、SDK Platform 36。未附 Gradle Wrapper，需自行安装 Gradle 或在 IDE 中配置。设置本机 SDK 路径后可运行：

```text
gradle :app:assembleDebug
```

`local.properties` 仅用于本机，不应提交。签名及产物可能与 PowerShell 方式不同，不能假定生成的 APK 与原版逐字节相同。兼容性要求见 [Android 官方说明](https://developer.android.com/build/releases/agp-8-10-0-release-notes)。

## 测试

三个测试使用纯 Java，不需要手机或 Android SDK：

```powershell
.\test.ps1 -JdkHome 'C:\path\to\jdk17'
```

覆盖地图投影与坐标有效性、位置回读判定、定位核心的启动/停止/刷新/失败清理与并发。测试平台接口是模拟实现，不能替代手机和目标应用的实际测试。本次恢复校验与运行结果见 [docs/VERIFICATION.md](docs/VERIFICATION.md)。

## 隐私与权限

没有账号、广告、分析 SDK、聊天读取或独立的选点上传接口。应用私有目录保存地图缓存、上次地图视野、一个选点及刷新设置，没有位置历史列表；清除应用存储可删除这些内容，应用备份已关闭。

地图使用 OpenStreetMap 的 WGS84 坐标。图块请求会向服务端暴露 IP、User-Agent 和图块编号，图块编号能反映浏览区域；地点搜索调用手机系统 Geocoder，搜索词可能发往系统配置的服务商。点“我附近”会请求当前位置，模拟期间该入口禁用。

权限为 `INTERNET`、`ACCESS_COARSE_LOCATION`、`ACCESS_FINE_LOCATION`、`ACCESS_MOCK_LOCATION`、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_LOCATION`、`POST_NOTIFICATIONS`、`WAKE_LOCK`。未申请后台定位权限；没有读取真实基站或 Wi-Fi 扫描列表的功能。持续定位和唤醒锁可能增加耗电。

地图数据 © [OpenStreetMap contributors](https://www.openstreetmap.org/copyright)。按需获取可见图块，保留 HTTPS、HTTP 缓存及署名，不进行区域批量下载。公共图块服务无可用性保证，大规模部署应自行评估服务商。第三方地图权利及政策见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## 源码来源与目录

本版从原开发记录中的源码补丁恢复，不是从第三方商业 APK 复制实现。用户提供的 2.1 APK 与原交付 APK 的 SHA-256 一致；应用代码、资源、配置、原构建脚本和测试共 18 个文件均与历史记录的文件校验值一致。当前文档、许可、忽略规则和测试入口是本次整理新增。

```text
app/src/main/java/local/pin/   应用与定位核心
app/src/main/res/              自绘图标与样式
tests/                        纯 Java 测试
build-apk.ps1                 原 Windows 个人测试构建入口
test.ps1                      纯 Java 测试入口
docs/                         恢复与验证说明
```

此源码包不包含 APK、签名私钥、手机数据、聊天记录、SDK/JDK 或后续的分身/Root 实验。商业授权请联系发布本项目的仓库所有者 AJ_boos，费用与范围另行书面约定。
