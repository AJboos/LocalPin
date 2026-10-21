# 第三方材料与服务

应用源码没有第三方运行时库，没有附带其他应用的 APK、反编译代码或地图图块。Android/JDK 类型由构建和运行环境提供，本包不分发 SDK、JDK 或 Gradle 插件。

## OpenStreetMap

地图数据 © OpenStreetMap contributors。地图数据库按 ODbL 提供；数据和图块的权利不随点位程序代码采用本项目许可。应用中保留可见署名及版权链接。

- [版权与署名说明](https://www.openstreetmap.org/copyright)
- [Open Database License 1.0](https://opendatacommons.org/licenses/odbl/1-0/)
- [公共图块使用政策](https://operations.osmfoundation.org/policies/tiles/)

程序使用 `https://tile.openstreetmap.org/{z}/{x}/{y}.png` 获取当前视野需要的图块，附带应用 User-Agent 并使用 HTTP 缓存。不得据此进行区域预取、离线批量下载或分发公共服务的图块缓存；公共图块服务也不是可保证容量的商业地图服务。

## Android Geocoder

搜索调用 [Android Geocoder](https://developer.android.com/reference/android/location/Geocoder)，实际后端由手机系统配置，不能视为固定由 OpenStreetMap 或某个其他供应商提供。其可用性及数据处理取决于设备和相应服务。

## 许可范围

[LICENSE](LICENSE) 仅授予点位权利人有权许可的代码、图标和文档权利。第三方商标和服务名称仅用于说明兼容性或技术来源，不代表其认可、赞助或合作。
