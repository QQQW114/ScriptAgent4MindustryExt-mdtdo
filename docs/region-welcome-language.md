# 地区欢迎与自动语言

## 脚本路径

```text
mdtserver/config/scripts/wayzer/ext/ipRegion.kts
mdtserver/config/scripts/wayzer/ext/welcomeMsg.kts
mdtserver/config/scripts/wayzer/ext/regionAutoLang.kts
```

## 功能

- 玩家加入时，欢迎消息会显示“来自 xxx 的玩家加入服务器”。
- `ipRegion.kts` 会优先读取：
  - `config/scripts/data/ip2region.xdb`
  - `config/scripts/data/ip2region_v4.xdb`
- 如果没有放置 xdb，脚本默认会尝试自动下载 `ip2region_v4.xdb`；下载失败时不会卸载，地区会回退为“未知地区”。
- 本地/内网IP显示为“本地网络”。
- 欢迎消息的地区显示规则：国内玩家显示省份，海外玩家显示国家。
- `regionAutoLang.kts` 会根据地区和客户端 `locale` 自动选择玩家菜单语言：
  - 中国/中文客户端：`zh`
  - 其他地区/非中文客户端：`en`
  - 默认不覆盖玩家手动 `/lang` 设置。

## 管理指令

- `/ipregion [玩家名/三位ID]`
  - 权限：`wayzer.admin.ipregion`，默认 `@admin`。
  - 查看指定玩家的IP地区识别结果。
- `/ipregion status`
  - 查看数据库是否已加载。
- `/ipregion reload`
  - 重新加载/重新尝试下载 xdb。
- `/ipregion ip <IP>`
  - 直接查询指定 IP。

## 维护备注

- 柠檬参考项目的 `autoTranslate.kts` 是“服务器脚本文本自动补翻译”的工具，不是聊天实时翻译。
- 本轮未默认接入外部机器翻译接口，避免网络不可用、Google接口不稳定或后台翻译造成额外性能/隐私风险。
- 如后续需要自动补全 `lang.ini`，建议单独做可开关的翻译脚本，并限制并发与调用频率。
