# 杂交单位玩法开发用单位分类表

> 目的：给后续“杂交单位”插件/玩法提供一个可直接参考的单位池与分类口径。当前脚本里只有零散单位列表（如 `wayzer/user/ext/skills.kts` 的 `randomT1Units`~`randomT4Units`、`serverPressureActions.kts` 的单位强度表、部分地图脚本的单位数组），没有统一、完整、可复用的单位分类表。因此先在文档中整理一份建议口径。

## 现有代码中可复用但不完整的列表

- `mdtserver/config/scripts/wayzer/user/ext/skills.kts`
  - `randomT1Units` / `randomT2Units` / `randomT3Units` / `randomT4Units`：用于“贴贴”等随机生成单位。
  - 优点：已经混合了塞普罗/埃里克尔、陆/空/海部分单位。
  - 缺点：只到 T4，缺少 T5；只是随机池，不区分陆辅/空辅/海辅/坦克/机甲/核心机/导弹。
- `mdtserver/config/scripts/wayzer/map/serverPressureActions.kts`
  - 有一份单位到强度等级的映射，用于压力优化判断。
  - 优点：适合性能/压力权重参考。
  - 缺点：不是玩法分类表，不保证完整。
- `mdtserver/config/scripts/wayzer/user/skillShop.kts`
  - `missileStormUnitNames`：`scathe-missile*` 系列导弹单位。
  - 适合复用到导弹/召唤类玩法。

建议后续正式开发时，把下面文档表转成一个共享脚本/库，例如：
`mdtserver/config/scripts/wayzer/lib/HybridUnitCatalog.kt` 或 `wayzer/user/ext/hybridUnits.lib.kt`，避免每个玩法脚本各自维护一份。

---

## 1. 按阶级/科技线分组

### 1.1 塞普罗 - 陆军突击线（Ground Assault）

| 阶级 | 变量名 | 单位ID | 中文常用名 | 说明 |
|---|---|---|---|---|
| T1 | `UnitTypes.dagger` | `dagger` | 尖刀 | 基础陆军，轻型射击 |
| T2 | `UnitTypes.mace` | `mace` | 战锤 | 火焰/近中距，高血量 |
| T3 | `UnitTypes.fortress` | `fortress` | 堡垒 | 炮击/范围伤害 |
| T4 | `UnitTypes.scepter` | `scepter` | 权杖 | 高级陆军，多武器 |
| T5 | `UnitTypes.reign` | `reign` | 王座 | 顶级陆军，高火力 |

### 1.2 塞普罗 - 陆辅/修复线（Ground Support）

| 阶级 | 变量名 | 单位ID | 中文常用名 | 说明 |
|---|---|---|---|---|
| T1 | `UnitTypes.nova` | `nova` | 新星 | 地面治疗/辅助入门 |
| T2 | `UnitTypes.pulsar` | `pulsar` | 脉冲星 | 修复/护盾/闪电，常用陆辅 |
| T3 | `UnitTypes.quasar` | `quasar` | 耀星 | 护盾/辅助能力更强 |
| T4 | `UnitTypes.vela` | `vela` | 灾星 | 高级修复/光束支援 |
| T5 | `UnitTypes.corvus` | `corvus` | 灾厄 | 顶级陆辅/激光，悬浮 |

### 1.3 塞普罗 - 爬虫/蜘蛛线（Crawler / Spider）

| 阶级 | 变量名 | 单位ID | 中文常用名 | 说明 |
|---|---|---|---|---|
| T1 | `UnitTypes.crawler` | `crawler` | 爬虫 | 自爆/低阶虫 |
| T2 | `UnitTypes.atrax` | `atrax` | 毒蛛 | 熔渣/液体攻击，悬浮 |
| T3 | `UnitTypes.spiroct` | `spiroct` | 血蛭 | 吸血/激光，悬浮 |
| T4 | `UnitTypes.arkyid` | `arkyid` | 毒蛊 | 高级蜘蛛，悬浮 |
| T5 | `UnitTypes.toxopid` | `toxopid` | 天蝎 | 顶级蜘蛛/炮击，悬浮 |

### 1.4 塞普罗 - 空军攻击线（Air Assault）

| 阶级 | 变量名 | 单位ID | 中文常用名 | 说明 |
|---|---|---|---|---|
| T1 | `UnitTypes.flare` | `flare` | 星辉 | 基础空军 |
| T2 | `UnitTypes.horizon` | `horizon` | 天垠 | 轰炸/空对地 |
| T3 | `UnitTypes.zenith` | `zenith` | 苍穹 | 中高级空军 |
| T4 | `UnitTypes.antumbra` | `antumbra` | 月影 | 高级空军，多武器 |
| T5 | `UnitTypes.eclipse` | `eclipse` | 日蚀 | 顶级空军 |

### 1.5 塞普罗 - 空辅/建造线（Air Support / Builder）

| 阶级 | 变量名 | 单位ID | 中文常用名 | 说明 |
|---|---|---|---|---|
| T1 | `UnitTypes.mono` | `mono` | 独影 | 基础空中采矿/建造 |
| T2 | `UnitTypes.poly` | `poly` | 幻型 | 空中建造/修复 |
| T3 | `UnitTypes.mega` | `mega` | 巨像 | 更强建造/修复 |
| T4 | `UnitTypes.quad` | `quad` | 四轴 | 运输/轰炸/辅助 |
| T5 | `UnitTypes.oct` | `oct` | 八翼 | 顶级护盾/运输/辅助 |

### 1.6 塞普罗 - 海战攻击线（Naval Assault）

| 阶级 | 变量名 | 单位ID | 中文常用名 | 说明 |
|---|---|---|---|---|
| T1 | `UnitTypes.risso` | `risso` | 梭鱼 | 基础海军攻击 |
| T2 | `UnitTypes.minke` | `minke` | 飞鲨 | 中阶海军攻击 |
| T3 | `UnitTypes.bryde` | `bryde` | 戟鲸 | 中高级海军炮击 |
| T4 | `UnitTypes.sei` | `sei` | 蛟龙 | 高级海军 |
| T5 | `UnitTypes.omura` | `omura` | 海神 | 顶级海军，长程炮击 |

### 1.7 塞普罗 - 海辅/海战支援线（Naval Support）

| 阶级 | 变量名 | 单位ID | 中文常用名 | 说明 |
|---|---|---|---|---|
| T1 | `UnitTypes.retusa` | `retusa` | 潜螺 | 基础海辅/修复 |
| T2 | `UnitTypes.oxynoe` | `oxynoe` | 电鳗 | 中阶海辅/电击 |
| T3 | `UnitTypes.cyerce` | `cyerce` | 江豚 | 高级海辅/修复 |
| T4 | `UnitTypes.aegires` | `aegires` | 盾舰 | 高级海辅/护盾 |
| T5 | `UnitTypes.navanax` | `navanax` | 战舰 | 顶级海战支援/输出 |

### 1.8 埃里克尔 - 坦克线（Erekir Tank）

| 阶级 | 变量名 | 单位ID | 中文常用名 | 说明 |
|---|---|---|---|---|
| T1 | `UnitTypes.stell` | `stell` | 星辉坦克 | 埃星基础坦克 |
| T2 | `UnitTypes.locus` | `locus` | 径迹 | 中阶坦克 |
| T3 | `UnitTypes.precept` | `precept` | 准则 | 中高级坦克 |
| T4 | `UnitTypes.vanquish` | `vanquish` | 征服 | 高级坦克 |
| T5 | `UnitTypes.conquer` | `conquer` | 统御 | 顶级坦克 |

### 1.9 埃里克尔 - 机甲/悬浮线（Erekir Mech / Hover）

| 阶级 | 变量名 | 单位ID | 中文常用名 | 说明 |
|---|---|---|---|---|
| T1 | `UnitTypes.merui` | `merui` | 明灭 | 埃星基础机甲/悬浮 |
| T2 | `UnitTypes.cleroi` | `cleroi` | 辉烁 | 中阶机甲 |
| T3 | `UnitTypes.anthicus` | `anthicus` | 天守 | 中高级机甲，可生成导弹 |
| T4 | `UnitTypes.tecta` | `tecta` | 天卫 | 高级护盾/支援机甲 |
| T5 | `UnitTypes.collaris` | `collaris` | 天理 | 顶级机甲/长程输出 |

### 1.10 埃里克尔 - 空军线（Erekir Air）

| 阶级 | 变量名 | 单位ID | 中文常用名 | 说明 |
|---|---|---|---|---|
| T1 | `UnitTypes.elude` | `elude` | 规避 | 埃星基础空军/悬浮类 |
| T2 | `UnitTypes.avert` | `avert` | 避让 | 中阶空军 |
| T3 | `UnitTypes.obviate` | `obviate` | 消解 | 中高级空军 |
| T4 | `UnitTypes.quell` | `quell` | 遏止 | 高级空军，可发射导弹 |
| T5 | `UnitTypes.disrupt` | `disrupt` | 扰动 | 顶级空军，可发射导弹 |

### 1.11 核心机/玩家默认机（Core Units）

| 阶级/核心 | 变量名 | 单位ID | 说明 |
|---|---|---|---|
| 塞普罗 T1核心机 | `UnitTypes.alpha` | `alpha` | shard核心默认机 |
| 塞普罗 T2核心机 | `UnitTypes.beta` | `beta` | foundation核心默认机 |
| 塞普罗 T3核心机 | `UnitTypes.gamma` | `gamma` | nucleus核心默认机 |
| 埃里克尔 T1核心机 | `UnitTypes.evoke` | `evoke` | 埃星核心机，飞行，采矿等级较高 |
| 埃里克尔 T2核心机 | `UnitTypes.incite` | `incite` | 埃星高级核心机 |
| 埃里克尔 T3核心机 | `UnitTypes.emanate` | `emanate` | 埃星顶级核心机 |

### 1.12 特殊/内部/导弹单位

| 类型 | 变量名或ID | 说明 |
|---|---|---|
| 新生物 | `UnitTypes.renale` / `UnitTypes.latum` | Neoplasm 单位，通常不建议直接混入普通单位池，除非玩法明确需要 |
| 内部/方块占位 | `UnitTypes.block` | 内部单位，不建议作为玩家/杂交结果 |
| 载荷/组装相关 | `UnitTypes.manifold` / `UnitTypes.assemblyDrone` | 特殊用途单位，建议单独白名单使用 |
| 天守导弹 | `anthicus-missile` | 源码中为 `MissileUnitType`，通常作为武器生成物 |
| 遏止导弹 | `quell-missile` | 源码中为 `MissileUnitType` |
| 扰动导弹 | `disrupt-missile` | 源码中为 `MissileUnitType` |
| 创伤导弹系列 | `scathe-missile`、`scathe-missile-phase`、`scathe-missile-surge`、`scathe-missile-surge-split` | MindustryX/扩展环境可用；当前技能中已有引用，使用前建议 `Vars.content.getByName<UnitType>(ContentType.unit, id)` 判空 |

---

## 2. 按玩法标签建议的单位池

> 以下是后续杂交单位插件可直接转成 `List<UnitType>` 的建议标签。一个单位可同时属于多个标签。

### 2.1 低阶/T1 池

```kotlin
listOf(
    UnitTypes.dagger, UnitTypes.nova, UnitTypes.crawler, UnitTypes.flare, UnitTypes.mono,
    UnitTypes.risso, UnitTypes.retusa,
    UnitTypes.stell, UnitTypes.merui, UnitTypes.elude,
    UnitTypes.alpha, UnitTypes.evoke,
)
```

### 2.2 中阶/T2 池

```kotlin
listOf(
    UnitTypes.mace, UnitTypes.pulsar, UnitTypes.atrax, UnitTypes.horizon, UnitTypes.poly,
    UnitTypes.minke, UnitTypes.oxynoe,
    UnitTypes.locus, UnitTypes.cleroi, UnitTypes.avert,
    UnitTypes.beta, UnitTypes.incite,
)
```

### 2.3 T3 池

```kotlin
listOf(
    UnitTypes.fortress, UnitTypes.quasar, UnitTypes.spiroct, UnitTypes.zenith, UnitTypes.mega,
    UnitTypes.bryde, UnitTypes.cyerce,
    UnitTypes.precept, UnitTypes.anthicus, UnitTypes.obviate,
    UnitTypes.gamma, UnitTypes.emanate,
)
```

### 2.4 T4 池

```kotlin
listOf(
    UnitTypes.scepter, UnitTypes.vela, UnitTypes.arkyid, UnitTypes.antumbra, UnitTypes.quad,
    UnitTypes.sei, UnitTypes.aegires,
    UnitTypes.vanquish, UnitTypes.tecta, UnitTypes.quell,
)
```

### 2.5 T5/Boss 池

```kotlin
listOf(
    UnitTypes.reign, UnitTypes.corvus, UnitTypes.toxopid, UnitTypes.eclipse, UnitTypes.oct,
    UnitTypes.omura, UnitTypes.navanax,
    UnitTypes.conquer, UnitTypes.collaris, UnitTypes.disrupt,
)
```

### 2.6 陆战池（不含海军/纯空军）

```kotlin
listOf(
    UnitTypes.dagger, UnitTypes.mace, UnitTypes.fortress, UnitTypes.scepter, UnitTypes.reign,
    UnitTypes.nova, UnitTypes.pulsar, UnitTypes.quasar, UnitTypes.vela, UnitTypes.corvus,
    UnitTypes.crawler, UnitTypes.atrax, UnitTypes.spiroct, UnitTypes.arkyid, UnitTypes.toxopid,
    UnitTypes.stell, UnitTypes.locus, UnitTypes.precept, UnitTypes.vanquish, UnitTypes.conquer,
    UnitTypes.merui, UnitTypes.cleroi, UnitTypes.anthicus, UnitTypes.tecta, UnitTypes.collaris,
)
```

### 2.7 空战池

```kotlin
listOf(
    UnitTypes.flare, UnitTypes.horizon, UnitTypes.zenith, UnitTypes.antumbra, UnitTypes.eclipse,
    UnitTypes.elude, UnitTypes.avert, UnitTypes.obviate, UnitTypes.quell, UnitTypes.disrupt,
)
```

### 2.8 空辅/建造/核心机池

```kotlin
listOf(
    UnitTypes.mono, UnitTypes.poly, UnitTypes.mega, UnitTypes.quad, UnitTypes.oct,
    UnitTypes.alpha, UnitTypes.beta, UnitTypes.gamma,
    UnitTypes.evoke, UnitTypes.incite, UnitTypes.emanate,
)
```

### 2.9 海战池

```kotlin
listOf(
    UnitTypes.risso, UnitTypes.minke, UnitTypes.bryde, UnitTypes.sei, UnitTypes.omura,
    UnitTypes.retusa, UnitTypes.oxynoe, UnitTypes.cyerce, UnitTypes.aegires, UnitTypes.navanax,
)
```

### 2.10 辅助/治疗/护盾倾向池

```kotlin
listOf(
    UnitTypes.nova, UnitTypes.pulsar, UnitTypes.quasar, UnitTypes.vela,
    UnitTypes.mono, UnitTypes.poly, UnitTypes.mega, UnitTypes.oct,
    UnitTypes.retusa, UnitTypes.oxynoe, UnitTypes.cyerce, UnitTypes.aegires,
    UnitTypes.tecta,
)
```

### 2.11 高风险/建议单独开关的单位池

```kotlin
listOf(
    UnitTypes.reign, UnitTypes.corvus, UnitTypes.toxopid, UnitTypes.eclipse, UnitTypes.oct,
    UnitTypes.omura, UnitTypes.navanax, UnitTypes.conquer, UnitTypes.collaris, UnitTypes.disrupt,
)
```

建议：这类单位用于杂交结果时应有更高消耗、冷却、概率限制或数量限制。

---

## 3. 后续实现建议

1. **不要直接依赖中文名**：脚本中统一使用 `UnitTypes.xxx` 或单位 ID 字符串。
2. **MindustryX/扩展导弹单位要判空**：例如 `scathe-missile-phase` 不是原版 `UnitTypes` 静态字段，应通过：
   ```kotlin
   Vars.content.getByName<UnitType>(ContentType.unit, "scathe-missile-phase")
   ```
3. **杂交结果建议拆成“基础体 + 武器/能力/数值”三层**：
   - 基础体：移动方式、碰撞、是否飞行/海军。
   - 武器/能力：从另一个单位借武器、状态、护盾/修复等。
   - 数值：血量、速度、命中体积、建造/采矿能力。
4. **先排除内部单位**：`block`、`manifold`、`assembly-drone`、各种 `*-missile` 默认不要进入普通随机池。
5. **海军与飞行单位单独处理**：海军在无水地图可能体验很差；飞行单位若给予陆战武器要注意射程/目标类型。
6. **性能限制**：T5、导弹、持续生成单位、带护盾/修复光环的单位建议加入数量上限。
