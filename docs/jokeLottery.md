# MDT 抽奖系统设计

状态：PR #10 / `feat/joke-lottery`，单插件实现，尚未完成真实客户端全流程验证。

## 1. 目标与边界

抽奖系统只负责“花 MDC 抽奖、发奖、存奖券、记录结果”，不引入个人背包或物品栏：

- 冷笑话：立即显示，不进入背包。
- MDC：立即增减，和抽奖记录在同一事务内。
- 单位：立即在玩家附近生成；没有当前单位时在玩家位置生成。
- 服务器音效：立即全服播放；缺少音频资产时返回明确失败。
- buff：存为奖券，不设过期时间；只允许附身普通单位时使用。
- 头奖抽奖券：普通抽奖以 0.5% 概率产出，存为背包物品，使用后从独立头奖池抽一次。
- debuff：立即永久生效，不进入背包；当前单位是核心机时也直接施加。

冷笑话题库和普通奖池都在外部文件，插件不内置 5000 条正文。

## 2. 价格与头奖

| 项目 | 规则 |
|---|---|
| 单抽 | 33 MDC |
| 十连 | 264 MDC（原价 330，八折） |
| 头奖抽奖券 | 每次普通抽奖独立 0.5% 概率 |
| 保底 | 第 100 抽必得头奖抽奖券；抽到后计数清零 |
| 普通池 grand | 加载时忽略旧 `rarity=grand` 配置，避免继续直接出头奖 |
| PvP | 默认禁止抽奖 |
| 余额不足 | 不扣费、不发奖 |

抽奖前不在事务外做余额/奖券数量预检查；最终判断在数据库事务内完成。

### 2.1 头奖池

头奖抽奖券在背包中使用后，从以下 **13 项等概率**头奖中随机抽取：

| 头奖 | 内容 |
|---|---|
| 王座 | 生成 1 个 `reign` |
| 死星 | 生成 1 个 `corvus` |
| 天蝎 | 生成 1 个 `toxopid` |
| 领主 | 生成 1 个 `conquer` |
| 天帝 | 生成 1 个 `collaris` |
| 日蚀 | 生成 1 个 `eclipse` |
| 要塞 | 生成 1 个 `oct` |
| disrupt（悲怆） | 生成 1 个 `disrupt` |
| 发散 | 生成 1 个 `emanate` |
| 起飞的海神 | 生成 1 个 `omura`，并设置 `elevation=1f` |
| 起飞的龙王 | 生成 1 个 `navanax`，并设置 `elevation=1f` |
| 1000 MDC | 直接加 1000 MDC |
| 无限时长全增益 buff 兑换券 | 存入背包，使用时对普通单位施加快速/超速永久增益 |

- 每个头奖概率为 `1/13`，不按权重、不再区分 `grand` 稀有度。
- 头奖单位生成后直接生效；飞行单位复用 `skillsLevel2.kts` 的 `elevation=1f; update()` 逻辑。
- 头奖券不设堆叠上限，不占普通 buff/全增益券的 **100** 张额度；头奖券本身不设过期时间。
- 普通抽奖第 100 抽保底一张头奖券；抽到任意头奖券后 `pityCounter` 清零。
- 头奖券使用不要求附身普通单位，核心机也可直接使用；抽到单位头奖时在玩家位置生成。
- 头奖结果写入 `LotteryDraws` 并标记 `isHead=true`。
- 旧 `pool.json` 中的 `grand` 项会被忽略；要使用新头奖池无需保留旧 grand 配置。

## 3. 奖券与满额折算

- 正面 buff 进入背包；奖券保存 `effect` 与 `duration` 快照，不依赖奖池后续改动。
- 奖券不设过期时间，未使用会一直保留。
- 普通 buff + 全增益券合计上限 **100** 张；头奖抽奖券不设上限，不占这 100 张额度。
- 相同奖券在背包中堆叠显示：只显示不同项，并标注 `×数量`；点击使用 1 张。
- 奖券使用条件：`player.unit()` 存在、有效、未死亡，且不是核心机。
- 护盾 buff 已从奖池移除；全增益券为快速、超速永久状态。
- 核心机/无单位时使用普通 buff 或全增益券：提示并保留奖券，不消耗。
- 满 100 后抽到的 buff **不再新增奖券**，而是在同一事务内折算为 MDC：

| buff 时长 | 折算 |
|---|---|
| 3 分钟 | +11 MDC |
| 8 分钟 | +22 MDC |
| 永久 | +66 MDC |

折算后的记录奖品类型为 `mdc`，名称标记“奖券已满，折算 +N MDC”，不会生成 `unused` 状态。

## 4. 冷笑话题库

- 文件：`config/lottery/jokes/vN.jsonl`，每行一个 JSON 对象，含 `id`、`text`。
- 每个玩家有独立轮次；轮次保存题库版本、随机 offset、与题库总数互质的 stride、cursor。
- 取题索引：`floorMod(offset + cursor * stride, total)`，用 Long 计算避免大题库乘加溢出。
- 同一轮内不重复；cursor 达到 total 后标记 completed，下次抽奖自动开新一轮。
- 活跃轮次锁定创建时的题库版本；更新题库后，新轮次使用新版本，旧轮次继续用旧版本直到结束。
- 测试题库当前为 `v1.jsonl` 的“测试1-10”；若启动时仍是自动生成的测试题库，`onEnable` 会打 WARN，提醒上线前替换。

## 5. 数据模型与事务边界

三张表由插件通过 `DBApi.registerTable` 注册：

| 表 | 作用 | 关键列 |
|---|---|---|
| `LotteryProfiles` | 玩家总抽数、保底计数 | `uid`（唯一）、`totalDraws`、`pityCounter` |
| `LotteryRounds` | 每玩家题库轮次 | `uid`、`roundNo`、`libraryVersion`、`offset`、`stride`、`cursor`、`total`、`status` |
| `LotteryDraws` | 每次抽奖/奖券/头奖记录 | `uid`、`batchId`、`prizeId/Name/Type`、`effectJson`、`status`、`isHead`、`cost` |

索引：

- `LotteryProfiles.uid` 唯一索引。
- `LotteryRounds`：`uid` 普通索引；`(uid, roundNo)` 唯一索引。
- `LotteryDraws`：`uid` 普通索引；`(uid, status)` 组合索引。

`prepareBatch` 在**一个**数据库事务中完成：

1. 事务内扣 MDC；
2. 分别读取未使用头奖券数量、buff/全增益券数量；
3. 循环生成 N 次抽奖记录、推进题库 cursor；
4. 普通 MDC 奖品在同一事务内加/减；
5. 任一异常整体回滚。

头奖抽券与头奖结果在另一个单事务中完成：抢占头奖券 `unused→used`、写入头奖记录、头奖 MDC 入账；失败整体回滚。事务提交后才执行单位生成、状态施加、音效和广播；外部效果失败会把记录标记为 `delivery_failed` 并提示玩家联系管理员。

## 6. 并发与幂等

- 奖券使用：先条件更新 `status='unused' → 'used'` 抢占，再施加状态；施加失败回滚为 `unused`。
- 头奖抽奖券：抢占与头奖记录写入同事务，避免一张券抽出多次。
- 题库 cursor：条件更新 `(id, cursor)`，更新 0 行则重试，最多 5 次；避免并发抽奖重复取同一题。
- 轮次唯一约束 `(uid, roundNo)` 阻止并发创建重复轮次。
- 抽奖批次没有客户端请求幂等键；快速重复点击仍由服务端事务串行化，可能产生多个独立批次。后续如需严格幂等，应增加请求 ID。

## 7. 权限、数据库开关与删号

- 入口权限：`/lottery` 仅信任等级 **3 级及以上**；游客按 0 级处理。
- 管理权限：`/lotteryadmin` 需要 `wayzer.admin.lottery`（默认 `@admin`）。
- 数据库开关：`DatabaseFeature.Lottery`（`serverFeatures.database.lottery.enabled`）；关闭后玩家抽奖页、抽奖、使用奖券均拒绝。
- `/lotteryadmin` 是维护入口，故意不受数据库开关限制：关闭后管理员仍可 `status`/`reload`，便于停用玩家入口时维护内容；它仍受 `wayzer.admin.lottery` 权限保护。
- 删号清理：插件在 `onEnable` 向 `MdtStorage.registerSubjectCleanupHook` 注册清理器，在 `deleteSubjectBusinessDataInTx` 的同一事务中删除三张表中该 UID 的数据；`onDisable` 注销。
- 删号 hook 是 lib 的通用扩展点，不把插件表定义搬进 lib；如果插件被禁用，删号时不会执行该 hook。

## 8. 配置与指令

| 配置 | 默认 | 说明 |
|---|---|---|
| `config/lottery/pool.json` | 自动生成测试奖池（20 项） | 普通奖池；grand 与旧护盾 buff 会被忽略 |
| `config/lottery/jokes` | 自动生成 v1 | 题库目录 |
| 单抽价格 | 33 | MDC |
| 十连价格 | 264 | MDC |
| buff/全增益券上限 | 100 | 硬编码；两类合计 |
| 头奖抽奖券概率 | 0.5% | 硬编码 |
| 保底 | 100 抽 | 第 100 抽必得头奖券 |
| 头奖池 | 13 项等概率 | 硬编码 |

指令：

- `/lottery`（别名 `/抽奖`、`/jokelottery`、`/lc`）：打开主页面。
- `/lotteryadmin status`：查看奖池/题库/价格/头奖池数量。
- `/lotteryadmin reload`：重新加载奖池与题库；失败保留旧配置。

## 9. 文档与测试

- 实机测试清单：`docs/jokeLottery-test-plan.md`。
- 奖池/题库格式与更新流程见本文第 4、8 节。
- 本轮改动涉及 `MdtStorage.kt`、`DatabaseFeatureSettings.kt`、`trustPoint.kts`，合并前需维护者按 lib 红线流程批准，并冷启动验证。

## 10. 已知风险

- 外部效果（单位/音效/状态）在事务提交后执行，失败只标记 `delivery_failed`，不自动退款；是否需要补偿需产品决定。
- 头奖抽奖券概率、头奖 13 项等概率是硬编码；后续调整需同步测试清单。
- 快速重复点击没有请求幂等键，可能产生多个批次。
- 奖券满额折算固定 11/22/66，奖池新增其他 buff 时长时需要同步调整 `voucherCompensation`。
- 5000 条题库的真实性能、加载时间和文件体积尚未实测。
