# 基因杂交运行时抽取与炮塔实现记录（2026-06-22）

> 当前方向：基因杂交不再依赖逐单位/逐炮塔手写模板，而是从当前服务器已加载、已被地图 CP 修改后的 UnitType / Turret 对象里随机抽取一个真实武器或能力，序列化为临时 CP。脚本只改 UnitType，不做持续维护。下表保留为风险分级与测试清单。

## 分级口径

- **A 批：第一版优先**：普通子弹或简单能量武器，容易转成单位武器，风险低。
- **B 批：可扩展**：可做，但需单独测试弹药、范围、连续/溅射/分裂等表现。
- **C 批：高风险/可能失败**：点防、修理、牵引、工厂类或过于特殊；运行时序列化失败会拒绝并回补 MDC。

## 炮塔表

| 批次 | 炮塔ID | 显示名 | 类型 | 建议基因效果 | 子弹/能力来源 | 坐标策略 | 风险 | 备注 |
|---|---|---|---|---|---|---|---|---|
| A | `duo` | 双管 | ItemTurret | 中心双管机枪 | 选铜/石墨弹的简化 BasicBullet | `x=0,y=0,shootY≈4` | 低 | 最适合作为首个模板 |
| A | `scatter` | 分裂 | ItemTurret | 近程防空散弹 | 选铅/废料弹的 flak 简化版 | 中心，轻微散射 | 低-中 | 注意是否允许对地，第一版可保留防空倾向 |
| A | `hail` | 冰雹 | ItemTurret | 远程小炮 | 选石墨/硅弹 artillery 简化版 | 中心炮口 | 低 | 比较适合陆军单位 |
| A | `arc` | 电弧 | PowerTurret | 近程电击 | LightningBullet | 中心 | 低 | 贴图简单，功能明显 |
| A | `lancer` | 蓝瑟 | PowerTurret | 蓄力激光 | LaserBullet 简化版 | 中心 | 中 | 需要测试客户端显示与伤害 |
| A | `salvo` | 齐射 | ItemTurret | 多发机炮 | 选钛/钍弹 BasicBullet | 中心多发 | 中 | 注意强度 |
| B | `scorch` | 火焰 | ItemTurret | 火焰喷射 | FlameBullet/短射程 | 中心 | 中 | 单位移动时表现需测 |
| B | `wave` | 波浪 | LiquidTurret | 水/液体喷射 | 水弹/冷冻液弹 | 中心 | 中 | 液体弹可控，但用途偏功能 |
| B | `tsunami` | 海啸 | LiquidTurret | 大型液体喷射 | 水弹/冷冻液弹 | 中心 | 中 | 比 wave 更强，需限制 |
| B | `swarmer` | 蜂群 | ItemTurret | 导弹齐射 | MissileBullet 简化版 | 中心 | 中 | 注意导弹数量与性能 |
| B | `ripple` | 浪涌 | ItemTurret | 远程炮击 | ArtilleryBullet | 中心 | 中 | 射程/溅射需压制 |
| B | `fuse` | 雷光 | ItemTurret | 近程高爆霰弹 | Shrapnel/短程 | 中心 | 中-高 | 伤害爆炸，需压强度 |
| B | `cyclone` | 气旋 | ItemTurret | 高速多弹 | Flak/BasicBullet | 中心 | 中-高 | 子弹数量多，注意性能 |
| B | `foreshadow` | 厄兆 | ItemTurret | 低频重炮/狙击 | Rail/Basic重弹简化 | 中心 | 高 | 强度很高，建议后续再开 |
| B | `spectre` | 幽灵 | ItemTurret | 高级机枪 | BasicBullet | 中心 | 中-高 | 强但易模板化 |
| B | `meltdown` | 熔毁 | LaserTurret | 持续激光 | ContinuousLaser/Laser 简化 | 中心 | 高 | 连续武器需专测 |
| B | `breach` | 裂解 | ItemTurret | 埃星机炮 | BasicBullet | 中心 | 中 | Erekir T1炮塔，适合早期扩展 |
| B | `diffuse` | 扩散 | ItemTurret | 近程散射 | Shrapnel/Basic | 中心 | 中 | 近程炮塔，需测命中 |
| B | `titan` | 泰坦 | ItemTurret | 重炮 | ArtilleryBullet | 中心 | 高 | 强度高，需测试后谨慎使用 |
| B | `disperse` | 驱散 | ItemTurret | 防空散射 | FlakBullet | 中心 | 中 | 可作为高阶防空基因 |
| B | `afflict` | 苦痛 | PowerTurret | 高阶能量炮 | 高伤能量弹简化 | 中心 | 高 | 强度高，后续测试 |
| B | `lustre` | 光辉 | ContinuousTurret | 连续光束 | Continuous/Laser 简化 | 中心 | 高 | 连续武器需专测 |
| B | `scathe` | 创伤 | ItemTurret | 超远程导弹 | Missile/爆炸简化 | 中心 | 很高 | 极高强度，建议管理开关后再开 |
| B | `smite` | 天谴 | ItemTurret | 高阶雷电/炮击 | 雷电/高爆简化 | 中心 | 高 | 需测试 |
| B | `malign` | 魔灵 | PowerTurret | 终局能量炮 | 高阶能量弹简化 | 中心 | 很高 | 终局炮塔，建议最后开放 |
| C | `parallax` | 差扰 | TractorBeamTurret | 暂不支持 | 牵引逻辑 | - | 高 | 非普通武器，排除 |
| C | `segment` | 裂片 | PointDefenseTurret | 暂不支持 | 点防逻辑 | - | 高 | 需持续拦截逻辑，排除 |
| C | `repair-point` | 修理点 | RepairTurret | 暂不支持 | 修理塔逻辑 | - | 高 | 非武器，排除 |
| C | `repair-turret` | 修理塔 | RepairTurret | 暂不支持 | 修理塔逻辑 | - | 高 | 非武器，排除 |
| C | `sublimate` | 升华 | ContinuousLiquidTurret | 暂缓 | 连续液体喷射 | - | 高 | 连续液体特殊，后续再评估 |

## 历史第一版建议实际开放

> 注意：当前实现已经取消“只允许列表内炮塔/单位”的准入白名单；下列清单仅作为历史第一版测试顺序与风险参考。

第一批曾建议仅优先测试：

```text
duo, scatter, hail, arc, lancer, salvo
```

原因：

- 都能明确转成“单位中心武器”；
- 强度可控；
- 出错时容易定位；
- 贴图/子弹表现相对常规。

## 基因杂交流程约束

- 发起者必须附身普通单位；炮塔不能发起基因杂交。
- 目标玩家可附身普通单位或控制炮塔；系统会从目标当前实际武器/能力中随机抽取一个可序列化基因。
- 发起者单位类型在当前地图内最多：
  - 与 1 个单位基因杂交一次；
  - 与 1 个炮塔基因杂交一次。
- 基因杂交消耗 25 MDC，20% 玩法失败概率；玩法失败消耗 MDC。
- CP 应用失败、模板不存在、目标不合法等属于系统失败：必须回补 MDC。
- 成功后只声明“新生成单位生效”。广播格式：

```text
[accent]基因杂交已应用：新生成的 尖刀 将获得 双管 基因。若客户端显示异常，请手动执行 /sync 或重进服务器。
```

## 同步策略

- 使用 `$hybrid-*` 作为临时 CP 名，避免写入地图 tag，回档/换图/结束后消失。
- 原版/MindustryX 现有网络接口中，运行时 CP 只随完整世界数据流下发：`NetworkIO.writeWorld/readWorld` 会写入/读取 content header 与 content patches；`Call.setRules`/普通实体快照不会携带运行时 CP。
- 自动完整世界同步默认开启（`hybridAutoWorldSync=true`），保证运行时 CP 尽量立即显示/生效；同步会触发玩家侧“重载地图”。若之后更重视体验，可在配置中关闭，改为显示异常时让玩家手动 `/sync` 或重进。
- 应用 CP 后会重建所有已基因杂交单位类型的现有单位 `mounts/abilities`，避免 DataPatcher 重放补丁后现有单位仍引用旧 Weapon/Ability 实例。
- 不再在杂交 CP 应用后额外 `Call.setRules(Vars.state.rules)`；该调用不能下发 CP，反而会同步完整 Rules，可能覆盖客户端本地显示类设置。
- Erekir 多部件炮塔作为单位武器时使用单张主体区域映射，例如 `smite -> smite-mid`、`malign -> malign-main`、`scathe -> scathe-mid`，避免普通 `<id>` 区域不存在导致不渲染。
- 子弹运行时序列化会跳过依赖原 `Effect` 对象的拖尾特效字段，尤其是 `trailRotation`；否则在原特效被跳过后，默认拖尾可能把旋转角当大小参数渲染，出现随角度变大/缩小的错觉。
- 子弹序列化深度当前为 14，会尽量保留嵌套 `fragBullet`、`intervalBullet`、`spawnBullets`、`spawnUnit/despawnUnit`、溅射/穿透/雷电等行为字段；这类字段是泰坦爆炸、天帝分裂、创伤导弹、雷霆轰炸与 Erekir 飞船导弹能否继承的关键。
- 已知 `Fx.*` 特效与 `Sounds.*` 音效会以字段名写入 CP；未知运行时自定义 Effect 仍跳过。炮塔杂交会尽量继承 `shootSound`，但持续循环音效不做脚本维护。
- 基因/自选基因杂交会记录源武器与应用后新武器的复杂子弹摘要；若 `fragBullet`、溅射、`intervalBullet`、`spawnBullets`、导弹单位等关键链路在 CP 应用后丢失，会回滚并退款，避免玩家得到“显示成功但复杂爆破无效”的基因。
- 激光/轨道/霰弹类复杂线性武器会显式保留 `fragOnHit`、`fragOnDespawn`、`fragOnAbsorb`、`delayFrags`、`pierceFragCap`、地面/地板碰撞等开关，尽量兼容“穿透线伤害 + 阻挡点散射爆破”的地图 CP 武器。

## 后续扩充方式

每新增一个炮塔模板，必须记录：

1. 炮塔 ID 与显示名；
2. 选用弹药/子弹；
3. 单位武器坐标；
4. reload / 射程 / 伤害压制；
5. 测试结果：服务端是否报错、客户端是否显示、是否需要 `/sync`。
