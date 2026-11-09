# 第三方代码和数据

项目根目录的 MIT 许可只适用于本项目自有代码、文档和原创蓝色公交图标。以下来源保持各自的权利和许可，不因放进仓库而改为 MIT。

## Trackr

- 项目：[5151561/Trackr](https://github.com/5151561/Trackr)
- 许可：[MIT License](https://github.com/5151561/Trackr/blob/main/LICENSE)
- 版权：Copyright (c) 2026 busonroad contributors
- 仓内许可全文：`assets/Trackr-LICENSE.txt`

参考位置：`NationalCities.java` 的省市归类、`MygolbsDataSource.java` 的接口字段与站序规则，以及 `RideCode.java` 的乘车码 deep link 目标。保留这些文件中的来源说明。参考代码的许可不表示 Trackr 或本项目取得了数据接口提供方的授权。

## OpenStreetMap 和 Geofabrik

- 署名：© OpenStreetMap contributors
- 许可：Open Data Commons Open Database License，ODbL 1.0
- [版权与署名说明](https://www.openstreetmap.org/copyright)
- [ODbL 1.0 正文](https://opendatacommons.org/licenses/odbl/1-0/)
- [Geofabrik 区域提取](https://download.geofabrik.de/asia/china.html)

`assets/metro-geography-*.json`、`assets/metro-districts-*.json`、派生的 `assets/metro_data.json` 和相应 OSM 数据记录作为数据库按 ODbL 提供。数据库使用、修改与再分发应遵守相应署名、许可通知和共享义务。来源网址、原始摘要、节点 ID、缺失区间及生成过程见 [DATA_SOURCES.md](docs/DATA_SOURCES.md)。

公开仓库不附带官方地铁或城际原图、精确描图、高德城市快照和来源授权未核实的运营时刻表。不能把代码的 MIT 许可延伸到这些未附带的内容。

## 在线服务

车来了、掌上公交、高德及相关公交、轨道查询服务的名称、标识、接口和返回资料仍属于各自提供方。代码中的协议适配不授予这些服务或数据的额外使用权；可用城市、调用额度、访问限制和个人信息处理取决于对应提供方。不得以本项目名义宣称其合作、赞助、认可或稳定性保证。

## 系统字体和工具链

应用使用 Android 系统 `sans-serif` 字体，不附带 TTF/OTF 字体文件。构建所需的 JDK 和 Android SDK 由开发者另行安装，本仓库不再分发它们；使用这些工具应遵守其各自条款。

## 原创图标和设计参考

`res/drawable/ic_sayu.xml`、`ic_launcher.xml` 是本项目新绘制的公交 Vector 图标，按 MIT 提供。兼容性的资源文件名不表示使用原角色图像。

应用设计曾参考 taste-skill、impeccable 和 Emil Kowalski 的设计指导。公开仓库不附带这些项目的参考文档副本或前端运行库；具体复用第三方文件时，应另外保留其对应版本的许可。
