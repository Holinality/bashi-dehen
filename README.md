# 巴士得很

一个没有开屏广告和信息流的 Android 公交查询应用。查附近站点、确认乘车方向、看公交还有多久到站，也能查询地铁线网和规划换乘。

## 下载

日常使用可下载维护者发布的 [Android 安装包](https://bus.holinality.com/download)。

这里公开的是源代码及可单独分发的基础数据。维护者发行包的资料集合、签名与本仓库默认构建不同，不能把这个下载链接理解为数据授权，也不能声称默认源码构建能逐字节复现该发行包。

## 能做什么

- 按城市、线路或站名查询公交，查看方向、站序、到站信息和车辆位置；车来了为主要通道，掌上公交作为补充。
- 根据定位查附近站点，保存常用线路和候车站。拒绝定位后仍可手动选城市、搜索。
- 查看地铁站点、换乘关系和线网，使用本地拓扑规划路线。
- 自行配置高德 Key 后使用相应的地点搜索、地图及联网路线规划。
- 保留城际和市域铁路查询代码、行程步骤、乘车码跳转、浅色与深色主题等能力；实际可用范围取决于资料和相关服务。

到站分钟数和车辆位置依赖上游数据；没有返回数据不一定等于没有车。第三方公交接口不是本项目能够保证长期稳定的开放服务，本仓库没有出示这些服务商的授权文件，也不替使用者取得相应使用权。

## 公开版的内置地铁资料

本仓库附带 **北京、上海、广佛和深圳的 OSM 基础拓扑**，可以离线查站和规划连通路径。分岔与资料缺口会分段显示，不假装成运营方的贯通交路。

这套公开数据**没有运营时刻表和实时列车位置**。不能把拓扑推算当成真实发车时间；具备有权使用的时刻资料时，程序才可据此展示相应估算。其他城市的完整内置快照、官方线网图片及精确描图没有随源码公开。

资料按 ODbL 1.0 单独提供，Java 代码和本项目原创图标采用 MIT。具体来源、覆盖范围、缺失项及本地资料覆盖方式见 [数据说明](docs/DATA_SOURCES.md)。

## 自己构建

这是 Java Android Views 工程，使用 Android 官方构建工具，不依赖 Gradle 或捆绑 SDK。

需要：

- JDK 17，配置 `JAVA_HOME`。
- Android SDK Platform 35 和 Build Tools 35.0.0，配置 `ANDROID_SDK_ROOT` 或 `ANDROID_HOME`。
- PowerShell 7（`pwsh`）。Linux/macOS 可使用 Bash 包装脚本，仍需安装 PowerShell 7。应用最低支持 Android 5.0 / API 21。

可以复制 `local.properties.example` 为 `local.properties`，填写本机路径。环境变量优先，实际配置文件不会进入 Git。

Windows：

```powershell
$env:JAVA_HOME = 'C:/path/to/jdk-17'
$env:ANDROID_SDK_ROOT = 'C:/path/to/android-sdk'
./build.ps1
```

Linux / macOS：

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_SDK_ROOT=/path/to/android-sdk
bash build.sh
```

构建会先运行回归测试，再生成 `build/bashi-dehen-<版本>-signed.apk` 和 `.sha256` 摘要文件。之后可以单独运行 `./test.ps1` 或 `bash test.sh`。重新生成公开地铁目录可运行：

```bash
python data/rebuild_osm_catalog.py
```

### 签名和应用标识

默认应用标识是 `com.holi.prdbus.opensource`，可以与维护者发行版并存。未提供发布签名时，构建脚本会在本机生成开发签名；它不能覆盖其他人签名的安装包。

正式分发请在自己的私有构建环境提供 `RELEASE_STORE_FILE`、`RELEASE_STORE_PASSWORD`、`RELEASE_KEY_ALIAS`、`RELEASE_KEY_PASSWORD`。四项需要一起设置。保管好签名库；换签名后，旧的自签安装通常不能直接覆盖升级。不要提交签名库、密码、Cookie 或含个人位置的诊断材料。

### 高德配置

仓库没有共享的高德 Key。使用者在应用设置中填写自己的 Key，并自行确认平台类型、授权范围及配额。没有 Key 时，公交基础查询和已有离线地铁资料仍可使用，相应高德能力会不可用或隐藏。免费额度不是无限调用，也不是数据再分发授权。

需要额外资料时，可用 `APP_ASSETS_OVERLAY` 指向本机有权使用的兼容资产目录。该目录不会自动上传，不是从私有工程暗中获取资料的通道。格式和许可范围见 [数据说明](docs/DATA_SOURCES.md)。

## 工程结构

```text
src/       页面、数据源、规划、定位、绘图与更新逻辑
res/       Android XML 资源及原创蓝色公交图标
assets/    随公开构建提供的基础数据库与许可通知
data/      OSM 目录生成脚本、来源与连续性检查记录
tests/     回归测试
tools/     构建环境与必要检查工具
docs/      数据来源等说明
```

## 隐私与反馈

应用使用定位来查附近站点和显示行程进度。查询时可能向对应服务商发送城市、站点、关键词或坐标；自建下载站只用于更新及下载，不提供 GPS 上报接口。首次运行的用途说明和系统权限申请分开处理，可在设置中再次阅读。[隐私说明](PRIVACY.md)

提交问题时请写明城市、线路、方向和复现步骤，截图先遮住个人信息。安全问题请按 [安全说明](SECURITY.md) 处理，不要在 Issue 中贴密钥或会话内容。

## 许可与致谢

原创代码及蓝色公交图标：[MIT](LICENSE)。第三方代码和数据分别遵循自己的许可，详见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

部分城市归类、掌上公交字段及乘车码跳转参考了 [Trackr](https://github.com/5151561/Trackr)；原 MIT 版权声明已保留。地图基础数据感谢 [OpenStreetMap contributors](https://www.openstreetmap.org/copyright) 和 [Geofabrik](https://download.geofabrik.de/asia/china.html)。
