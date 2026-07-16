# Tips 小提示系统

## 脚本路径

```text
mdtserver/config/scripts/wayzer/user/tips.kts
```

## 功能

- 每隔一段时间随机向在线玩家在聊天栏广播一条服务器小提示，方便玩家注意到和回看。
- 默认导入 `C:\Users\qw114\Desktop\临时tips.txt` 中整理的 48 条预设内容。
- `[tips]` 标签的提示默认权重为 `5`，普通想法类提示使用 `[idea]` 标签，默认权重为 `1`，因此规则类 Tips 出现概率更高。
- 随机选择时会记住最近 5 条已发送 Tips，并优先从未在近期出现过的内容中抽取，以降低连续重复消息的概率。
- 提示内容会持久化到数据库 `MdtSettings` 中，重启后仍会保留。

## 玩家指令

- `/tips`、`/tip`、`/小提示`
  - 立即查看一条随机 Tips。

## 管理指令

权限：`wayzer.admin.tips`，默认注册给 `@admin`。

- `/tipadmin list [页码]`
  - 查看 Tips 列表、ID、标签、权重、启用状态。
- `/tipadmin add <标签> [权重] <内容>`
  - 添加 Tips。
  - 不填权重时，`tips` 标签默认 `5`，其他标签默认 `1`。
- `/tipadmin set <id> <标签> [权重] <内容>`
  - 修改指定 Tips。
- `/tipadmin remove <id>`
  - 删除指定 Tips。
- `/tipadmin enable <id>` / `/tipadmin disable <id>`
  - 启用/停用指定 Tips。
- `/tipadmin send [id]`
  - 立即广播指定 Tips；不填 ID 则随机发送。
- `/tipadmin seed`
  - 重新导入默认 Tips，只会补充不存在的内容。

## 配置项

- `tipsInterval`：自动轮播间隔，默认 `10` 分钟，参考柠檬开源插件 `wayzer/ext/alert.kts` 的公告间隔。
- Tips 显示方式固定为聊天栏消息，不走 `InfoToast`。

## 存储

- `MdtSettings.tips.items`
  - 保存全部 Tips 条目。
- `MdtSettings.tips.seeded.v1`
  - 标记默认 Tips 是否已导入。

备注：默认 Tips 只会自动导入一次；如果管理员删除了默认 Tips，不会在下次重启自动恢复，除非手动执行 `/tipadmin seed`。
