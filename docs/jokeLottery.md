# MDT 抽奖系统设计

状态：PR #10 / `feat/joke-lottery`，单插件实现，尚未完成真实客户端全流程验证。

## 1. 目标与边界

抽奖系统只负责“花 MDC 抽奖、发奖、存奖券、记录结果”，不引入个人背包或物品栏：

- 冷笑话：立即显示，不进入背包。
- MDC：立即增减，和抽奖记录在同一事务内。
- 单位：立即在玩家附近生成；没有当前单位时在玩家位置生成。
- 服务器音效：立即全服播放；缺少音频资产时返回明确失败。
- buff：存为奖券，28 天有效；只允许附身普通单位时使用。
- debuff：立即永久生效，不进入背包；当前单位是核心机时也直接施加。

冷笑话题库和奖池都在外部文件，插件不内置 5000 条正文。

## 2. 价格与保底

| 项目 | 规则 |
|---|---|
| 单抽 | 33 MDC |
| 十连 | 264 MDC（原价 330，八折） |
| 保底 | 每名玩家独立计数，第 100 抽强制 `grand` |
| 自然出头奖 | 保底计数清零 |
| 十连 | 按 10 次顺序结算，保底可落在任意一抽 |
| 未配置 grand | 禁止抽奖，避免保底静默失效 |
| PvP | 默认禁止抽奖 |
| 余额不足 | 不扣费、不发奖 |

抽奖前不再在事务外做余额/奖券数量预检查；最终判断在数据库事务内完成，避免“预检查通过、真正扣费时已变化”。

## 3. 奖券与满额折算

- 只有正面 buff 进入背包；奖券保存 `effect` 与 `duration` 快照，不依赖奖池后续改动。
- 奖券使用条件：`player.unit()` 存在、有效、未死亡，且不是核心机。
- 核心机/无单位时使用奖券：提示并保留奖券，不消耗。
- 未使用奖券上限 100。
- 满 100 后抽到的 buff **不再新增奖券**，而是在同一事务内折算为 MDC：

| buff 时长 | 折算 |
|---|---|
| 3 分钟 | +11 MDC |
| 8 分钟 | +22 MDC |
| 永久 | +30 MDC |

折算后的记录奖品类型为 `mdc`，名称标记“奖券已满，折算 +N MDC”，不会生成 `unused` 状态。

## 4. 冷笑话题库

- 文件：`config/lottery/jokes/vN.jsonl`，每行一个 JSON 对象，含 `id`、`text`。
- 每个玩家有独立轮次；轮次保存题库版本、随机 offset、与题库总数互质的 stride、cursor。
- 取题索引：`floorMod(offset + cursor * stride, total)`，用 Long 计算避免大题库乘加溢出。
- 同一轮内不重复；cursor 达到 total 后标记 completed，下次抽奖自动开新一轮。
- 活跃轮次锁定创建时的题库版本；更新题库后，新轮次使用新版本，旧轮次继续用旧版本直到结束。
- 测试题库当前为 `v1.jsonl` 的“测试1-10”。

## 5. 数据模型与事务边界

三张表由插件通过 `DBApi.registerTable` 注册：

| 表 | 作用 | 关键列 |
|---|---|---|
| `LotteryProfiles` | 玩家保底/总抽数 | `uid`（唯一）、`pityCounter`、`totalDraws` |
| `LotteryRounds` | 每玩家题库轮次 | `uid`、`roundNo`、`libraryVersion`、`offset`、`stride`、`cursor`、`total`、`status` |
| `LotteryDraws` | 每次抽奖/奖券记录 | `uid`、`batchId`、`prizeId/Name/Type`、`effectJson`、`status`、`cost`、`expiresAt` |

索引：

- `LotteryProfiles.uid` 唯一索引。
- `LotteryRounds`：`uid` 普通索引；`(uid, roundNo)` 唯一索引。
- `LotteryDraws`：`uid` 普通索引；`(uid, status)` 组合索引。

`prepareBatch` 在**一个**数据库事务中完成：

1. 事务内扣 MDC；
2. 读取当前奖券数；
3. 循环生成 N 次抽奖记录、更新保底、推进题库 cursor；
4. MDC 奖品在同一事务内加/减；
5. 任一异常整体回滚。

事务提交后才执行单位生成、状态施加、音效和广播。外部效果失败不会回滚已提交的抽奖记录，而是把记录标记为 `delivery_failed` 并提示玩家联系管理员。

## 6. 并发与幂等

- 奖券使用：先条件更新 `status='unused' → 'used'` 抢占，再施加状态；施加失败回滚为 `unused`。
- 题库 cursor：条件更新 `(id, cursor)`，更新 0 行则重试，最多 5 次；避免并发抽奖重复取同一题。
- 轮次唯一约束 `(uid, roundNo)` 阻止并发创建重复轮次。
- 抽奖批次没有客户端请求幂等键；快速重复点击仍由服务端事务串行化，可能产生多个独立批次。后续如需严格幂等，应增加请求 ID。

## 7. 权限、数据库开关与删号

- 入口权限：`/lottery` 仅信任等级 **3 级及以上**；游客按 0 级处理。
- 管理权限：`/lotteryadmin` 需要 `wayzer.admin.lottery`（默认 `@admin`）。
- 数据库开关：`DatabaseFeature.Lottery`（`serverFeatures.database.lottery.enabled`）；关闭后抽奖页、抽奖、使用奖券均拒绝。
- 删号清理：插件在 `onEnable` 向 `MdtStorage.registerSubjectCleanupHook` 注册清理器，在 `deleteSubjectBusinessDataInTx` 的同一事务中删除三张表中该 UID 的数据；`onDisable` 注销。
- 删号 hook 是 lib 的通用扩展点，不把插件表定义搬进 lib；如果插件被禁用，删号时不会执行该 hook。

## 8. 配置与指令

| 配置 | 默认 | 说明 |
|---|---|---|
| `config/lottery/pool.json` | 自动生成测试奖池 | 奖池 |
| `config/lottery/jokes` | 自动生成 v1 | 题库目录 |
| 单抽价格 | 33 | MDC |
| 十连价格 | 264 | MDC |
| 奖券有效天数 | 28 | 天 |
| 未使用奖券上限 | 100 | 硬编码 |
| 保底 | 100 | 硬编码 |

指令：

- `/lottery`（别名 `/抽奖`、`/jokelottery`、`/lc`）：打开主页面。
- `/lotteryadmin status`：查看奖池/题库/价格/保底。
- `/lotteryadmin reload`：重新加载奖池与题库；失败保留旧配置。

## 9. 文档与测试

- 实机测试清单：`docs/jokeLottery-test-plan.md`。
- 奖池/题库格式与更新流程见本文第 4、8 节。
- 本轮改动涉及 `MdtStorage.kt`、`DatabaseFeatureSettings.kt`、`trustPoint.kts`，合并前需维护者按 lib 红线流程批准，并冷启动验证。

## 10. 已知风险

- 外部效果（单位/音效/状态）在事务提交后执行，失败只标记 `delivery_failed`，不自动退款；是否需要补偿需产品决定。
- 快速重复点击没有请求幂等键，可能产生多个批次。
- 奖券满额折算目前是固定 11/22/30，奖池新增其他 buff 时长时需要同步调整 `voucherCompensation`。
- 5000 条题库的真实性能、加载时间和文件体积尚未实测。
