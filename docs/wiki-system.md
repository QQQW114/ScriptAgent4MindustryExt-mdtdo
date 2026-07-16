# Wiki 系统

## 脚本路径

```text
mdtserver/config/scripts/wayzer/user/wiki.kts
```

## 玩家入口

- `/wiki`：打开 Wiki 列表。
- `/wiki <id>`：直接打开指定 Wiki 页面。
- `/wiki share <id>` / `/wiki 分享 <id>`：将指定 Wiki 分享到聊天栏，其他玩家可输入 `/wiki <id>` 快速打开。
- `/百科`、`/规则`、`/wiki列表`：`/wiki` 的别名。

当前预置第一条 Wiki：

- ID：`linuxdo-guidelines`
- 标题：`linux do社区准则节选`
- 来源：用户提供的 `C:\Users\qw114\Desktop\新建 文本文档 (3).txt`

Wiki 正文使用分页弹窗显示，每页只保留少量按钮：

- 上一页
- 页码
- 下一页
- 返回列表
- 分享到聊天
- 最近修改
- 关闭

这套显示方式用于减少长文本菜单对客户端的渲染压力，也可作为后续服务器规则/wiki页面的基础。

Wiki 列表中的正文预览限制为单行短预览，默认最多约 16 字，避免菜单选项过高覆盖其他按钮。

Wiki 页面内提供“最近修改”与“分享到聊天”入口；分享按钮会在聊天栏广播 Wiki 标题与 `/wiki <id>` 快捷打开方式。

Wiki 正文支持 MDT 轻量格式：`#`/`##` 标题、`-`/`*` 列表、`>` 引用、`**重点**`、反引号代码、`---` 分割线、`[文字](链接)`。图片不会内嵌显示，只会转成图片链接提示。输入框中可用 `\n`、`|` 或全角 `｜` 快速换行。

性能/懒加载说明：

- Wiki 列表与 Wiki 管理列表只读取当前页的轻量摘要，不再为列表一次性读取所有正文。
- 摘要字段会落盘保存；旧数据第一次进入列表时才会从正文回填摘要，之后列表不再预读正文。
- 详情页、历史页、编辑页需要正文时才读取正文；数据库读取放在 `Dispatchers.IO` 中，避免长文本读取卡住游戏主线程。

## 管理与编辑

3+级玩家与管理员可以在游戏内编辑 Wiki：

- `/wiki admin`：打开 Wiki 管理菜单。
- `/wikiadmin`：打开 Wiki 管理菜单。
- `/wikiadmin add`：新增 Wiki。
- `/wikiadmin edit <id>`：编辑指定 Wiki。
- `/wikiadmin protect <id>`：4级/admin 设置 Wiki 保护锁。
- `/wikiadmin unprotect <id>`：4级/admin 解除 Wiki 保护锁。
- `/wikiadmin trash`：4级/admin 打开 Wiki 回收站。
- `/wikiadmin restore <id>`：4级/admin 从回收站恢复 Wiki。
- `/wikiadmin purge <id>`：4级/admin 彻底删除回收站内 Wiki。
- `/help` → 管理指令：已加入 **Wiki管理 / Wiki回收站 / Wiki保护锁** 快捷入口；4级/admin打开 Wiki 页面或 Wiki 管理页后，会看到独立的 **设置保护锁（4）/解除保护锁（4）** 按钮。

管理菜单支持：

- 新增 Wiki
- 修改标题
- 修改正文
- 删除页面到回收站
- 查看页面
- 查看最近修改记录
- 4级/admin 设置/解除保护锁
- 4级/admin 回收站恢复/彻底删除

## 权限规则

- 普通玩家：只能查看 Wiki。
- 3+级玩家：可以新增、编辑、删除 Wiki。
- 4级/admin：可以新增、编辑、删除 Wiki，并可设置保护锁、管理回收站。
- 被保护锁锁定的 Wiki，4级/admin 以外的玩家无法编辑或删除。
- 非4级/admin 删除 Wiki 时需要填写理由，并受到删除频率限制，避免3+级玩家短时间批量删除 Wiki。

## 编辑超时

- Wiki 菜单的服务端等待时间已延长到约 30 分钟。
- Wiki 文本输入框的服务端等待时间已延长到约 30 分钟，便于在游戏内编辑长文本。
- 备注：这里能控制的是脚本/服务端等待玩家响应的超时。如果 Mindustry 客户端自身在长时间无操作后硬编码关闭输入框或弹窗，服务端脚本无法强制覆盖；这种情况需要接受客户端限制或改用外部文件/管理工具编辑。

## 存储方式

当前不新增数据库表，直接复用 `MdtStorage` 的 `MdtSettings`：

- `wiki.index`：Wiki ID 列表。
- `wiki.trash.index`：回收站 Wiki ID 列表。
- `wiki.protectedIds`：4级/admin 保护锁 Wiki ID 列表。
- `wiki.page.<id>.title`：页面标题。
- `wiki.page.<id>.body`：页面正文。
- `wiki.page.<id>.preview`：列表单行预览。
- `wiki.page.<id>.bodyLength`：正文长度，用于管理页显示，避免为列表读取正文。
- `wiki.page.<id>.updatedBy`：最后编辑者。
- `wiki.page.<id>.history`：最近 10 次新增/编辑记录，包含时间、编辑者、动作。
- `wiki.page.<id>.deletedBy`：删除者。
- `wiki.page.<id>.deleteReason`：删除理由。
- `wiki.page.<id>.deletedAt`：删除时间戳。
- `wiki.seeded.v1`：预置 Wiki 是否已导入。

备注：预置 Wiki 只会自动导入一次；如果后续在游戏内删除，不会在下一次重启时反复恢复。
普通删除 Wiki 时只会从 `wiki.index` 移入 `wiki.trash.index`，保留标题、正文、历史和删除元数据；4级/admin 在回收站执行彻底删除时才会清理该页面的标题、正文、摘要、最近修改记录和删除记录。

## 维护建议

- Wiki ID 仅允许英文小写、数字、`_`、`-`，最长 32 字符。
- 标题最长 60 字符。
- 正文最长 6000 字符；保存时会将 `\n`、`|`、`｜` 转为真实换行。
- 当前最近修改记录只保存“谁在什么时候做了什么动作”，不保存正文差异与历史正文。
- 若后续需要回滚版本、对比差异或审计删除操作，再考虑独立版本记录表。
