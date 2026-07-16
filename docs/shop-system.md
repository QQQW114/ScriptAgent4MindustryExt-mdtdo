# 商店系统说明

本文档记录通用商店入口、称号商店、技能商店、商品管理方式与后续扩展边界。

## 脚本路径

- `mdtserver/config/scripts/wayzer/user/shopList.kts`：商店列表入口 `/shop`。
- `mdtserver/config/scripts/wayzer/user/shopCore.kts`：通用商店核心校验、扣MDC、购买统计。
- `mdtserver/config/scripts/wayzer/user/titleShop.kts`：称号商店。
- `mdtserver/config/scripts/wayzer/user/skillShop.kts`：技能商店。
- `mdtserver/config/scripts/wayzer/lib/MdtStorage.kt`：商品配置和购买统计持久化。

## 玩家指令

- `/shop`、`/商店`、`/shops`：打开商店列表。
- `/titleshop`、`/称号商店`：直接打开称号商店。
- `/skillshop`、`/技能商店`：直接打开技能商店。

称号商店购买规则：

- 商品会按商品 ID 的数字顺序排序显示。
- 购买前会检查：当前MDC、最低信任等级、最低被认可数。
- 固定称号如果已经拥有，不会重复扣MDC。
- 自定义称号商品使用 `custom:<长度>` 配置；玩家输入合法称号后才会扣MDC并发放称号。
- 自定义称号当前校验：不能为空、不能包含 `|`、原始长度不超过 `80`、去除颜色后的可见字符数不超过商品限制。

## 管理指令

权限：`wayzer.admin.titleShop`，默认注册给 `@admin`，因此 4 级在线玩家可使用。

- `/titleshopadmin list`：查看称号商店商品。
- `/titleshopadmin set <商品id> <称号内容|custom:长度> <售价> <等级要求> [认可要求]`：新增或更新商品。
- `/titleshopadmin del <商品id>`：删除商品。
- `/titleshopadmin seed`：写入/覆盖当前预设商品。

别名：

- `/tshopadmin`
- `/称号商店管理`

示例：

```text
/titleshopadmin set 9 大冤种 100 2
/titleshopadmin set 10 custom:7 50 3+
/titleshopadmin set 11 指导顾问 10 2 10
/titleshopadmin del 9
```

## 当前预设商品

| ID | 商品 | 售价 | 要求 |
|---|---|---:|---|
| `1` | 自定义称号（限制7字符） | `50` MDC | `3+` 级 |
| `2` | 自定义称号（限制20字符） | `500` MDC | `3+` 级 |
| `3` | 富可敌国 | `1000` MDC | `2` 级 |
| `4` | 百尺竿头 | `100` MDC | `1` 级 |
| `5` | 指导顾问 | `10` MDC | `2` 级，被认可 `10` 次 |
| `6` | 受人敬仰 | `10` MDC | `2` 级，被认可 `20` 次 |
| `7` | 无所不知 | `1` MDC | `3` 级，被认可 `30` 次 |
| `8` | 元气满满 | `1` MDC | `3` 级，被认可 `20` 次 |

> 备注：原需求文本里部分编号重复，当前实现整理为 `1..8`，方便排序和维护。

## 技能商店

技能商店当前商品在 `skillShop.kts` 内预设，购买后会写入 `MdtPlayerSkills`，并在 `/skill` -> `特殊/商店技能` 中显示可用技能。

| ID | 技能code | 商品 | 售价 | 要求 |
|---|---|---|---:|---|
| `1` | `radar` | 雷达 | `60` MDC | 最低 `1` 级 |
| `2` | `fluid` | 随机液体 | `60` MDC | 最低 `1` 级 |
| `3` | `randomore` | 随机矿 | `60` MDC | 最低 `1` 级 |
| `4` | `wallkiller` | 粉碎墙壁 | `100` MDC | 最低 `1` 级 |
| `5` | `corezone4` | 核心区4x4 | `120` MDC | 最低 `1` 级 |
| `6` | `betray` | 叛变 | `120` MDC | 最低 `1` 级 |
| `7` | `banme` | BAN自己5分钟 | `1` MDC | 最低 `1` 级 |
| `8` | `reptile` | 爬爬盲盒 | `60` MDC | 最低 `1` 级 |
| `9` | `teleportation` | 传送 | `60` MDC | 最低 `1` 级 |
| `10` | `rain` | 唤雨 | `10` MDC | 最低 `1` 级 |
| `11` | `kickmebutoct` | 踢自己送oct | `60` MDC | 最低 `1` 级 |
| `12` | `imcute` | 我很可爱 | `1` MDC | 最低 `1` 级 |
| `13` | `lottery` | 抽奖 | `20` MDC | 最低 `1` 级 |
| `14` | `randomunit` | 随机单位 | `60` MDC | 最低 `1` 级 |
| `15` | `ultirandom` | 终极随机 | `200` MDC | 最低 `1` 级 |
| `16` | `missilestorm` | 导弹风暴 | `100` MDC | 最低 `1` 级 |
| `17` | `fishonlyyou` | 此生只属鱼你 | `10` MDC | 最低 `2` 级 |

具体使用消耗、冷却和限制见 `docs/skill-system.md`。

## 持久化表

- `MdtTitleShopItems`：称号商店商品配置。
- `MdtShopPurchaseStats`：玩家购买次数统计。
- `MdtPlayerSkills`：玩家已购买/解锁的技能。

## 后续扩展商店建议

新增商店时建议：

1. 新建独立脚本，例如 `skillShop.kts`，不要把技能商店逻辑写进 `shopList.kts`。
2. 依赖 `shopList.kts` 并调用 `registerShop(code, name, description, command)` 注册入口。
3. 依赖 `shopCore.kts` 使用通用的等级/认可/MDC校验、扣MDC和购买统计。
4. 具体发放逻辑仍放在各自商店脚本里，例如称号商店调用 `playerTitle.grantTitle`，技能商店以后调用技能系统接口。
5. 如果商品需要动态管理，优先在 `MdtStorage.kt` 增加独立商品表，避免硬编码在菜单脚本里。
