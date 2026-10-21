# 地图来源与国内服务配置

## 当前地图

默认使用 OpenStreetMap 的公共图块服务，不是谷歌地图。地图细节来自当地贡献数据，网络能否加载取决于设备与服务之间的连接；换一张底图不会改变目标应用是否接受模拟位置。

2.2.1-preview 新增“设置 → 地图来源”，可选择 OpenStreetMap，或填写自己的天地图服务 Key。没有 Key 时，默认来源仍是 OpenStreetMap，不会自动获得天地图、高德或百度授权，也没有承诺解决所有地区的网络访问问题。

## 天地图配置入口

1. 在“设置 → 地图来源”点击“申请天地图 Key”，打开[天地图控制台](https://console.tianditu.gov.cn/api/key)。
2. 按官方流程注册开发者、创建应用，并申请支持地图服务的 Key；是否获批、配额和可用范围由服务商决定。
3. 回到点位，选择天地图，粘贴自己的 Key 并保存。程序仅检查字符格式，格式正确不等于服务授权已生效。
4. 切换地图会重新建立独立的内存图块缓存，不改变已有路线和运动进度。失败时可以在“核验”查看错误并重试。

此入口不需要添加第三方地图 SDK。通过官方 HTTPS WMTS 请求 `vec_w` 矢量底图和 `cva_w` 中文注记两层，使用官方球面墨卡托图层。当前缩放配置为 2–18 级；尚未使用有效 Key 获取实际服务的能力文档来核验完整矩阵范围。

没有 Key 时不发送天地图请求，不使用其他项目的凭证、未公开瓦片接口或镜像来绕过授权。本版也没有接入高德或百度地图。

## 隐私与坐标

地图服务会收到 IP、请求图块对应的浏览区域及其所需的服务 Key。Key 保存在应用私有偏好设置中，HTTPS 缓存元数据也可能包含带 Key 的请求地址；清除应用存储会删除这些本地内容。备份关闭，核验信息和应用错误文案不输出 Key 或完整请求 URL。请勿将自己的 Key 提交到公开仓库。

OpenStreetMap 使用其 WGS84 / Web Mercator 地图；天地图使用其官方墨卡托图层。程序不把高德或百度的坐标直接当作系统 GPS 坐标，系统模拟输出仍采用 WGS84。地点搜索继续调用系统 Geocoder，没有因为换底图就获得天地图或高德的 POI 搜索功能。

地图展示数据的丰富程度、网络可用性、Key 权限和配额需要使用者实际核对。本次没有可用 Key，也没有进行天地图带 Key 的在线图块或真机渲染测试。

## 官方说明

- [天地图地图服务](https://lbs.tianditu.gov.cn/server/MapService.html)
- [天地图服务授权](https://lbs.tianditu.gov.cn/server/authorization.html)
- [天地图服务条款](https://www.tianditu.gov.cn/about/service)
- [天地图版权说明](https://www.tianditu.gov.cn/about/copyright)
- [OpenStreetMap 公共图块使用政策](https://operations.osmfoundation.org/policies/tiles/)
