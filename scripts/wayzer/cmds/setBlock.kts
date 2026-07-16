package wayzer.cmds

import mindustry.Vars
import mindustry.ctype.ContentType
import mindustry.content.Blocks
import mindustry.game.Team
import mindustry.gen.Player
import mindustry.world.Block
import mindustry.world.blocks.defense.turrets.ContinuousTurret
import mindustry.world.blocks.defense.turrets.ItemTurret
import mindustry.world.blocks.defense.turrets.LiquidTurret
import mindustry.world.blocks.defense.turrets.PowerTurret
import mindustry.world.blocks.defense.turrets.Turret

name = "MDT管理员设置脚下方块"

val fillMaxTiles by config.key(10_000, "fill指令单次允许处理的最大格数")

private fun findBlock(input: String): Block? {
    val key = input.trim()
    if (key.isBlank()) return null
    return content.getByName<Block>(ContentType.block, key)
        ?: content.blocks().firstOrNull {
            it.name.equals(key, ignoreCase = true) || it.localizedName.equals(key, ignoreCase = true)
        }
}

private fun suggestBlocks(input: String, limit: Int = 10): String {
    val key = input.trim().lowercase()
    if (key.isBlank()) return ""
    return content.blocks()
        .filter {
            it.name.lowercase().contains(key) ||
                    it.localizedName.lowercase().contains(key)
        }
        .take(limit)
        .joinToString(" ") { "[yellow]${it.name}[gray](${it.localizedName})" }
}

private fun blockName(block: Block): String = "[yellow]${block.name}[gray](${block.localizedName})"

private fun teamName(team: Team): String = "[${team.color}]${team.name}[gray](id=${team.id})"

private fun findTeam(input: String): Team? {
    val key = input.trim()
    if (key.isBlank()) return null
    key.toIntOrNull()?.let { id ->
        if (id in Team.all.indices) return Team.all[id]
    }
    return Team.all.firstOrNull { it.name.equals(key, ignoreCase = true) }
}

private fun suggestTeams(input: String, limit: Int = 12): String {
    val key = input.trim().lowercase()
    val candidates = if (key.isBlank()) {
        Team.baseTeams.toList()
    } else {
        Team.all.filter { it.name.lowercase().contains(key) || it.id.toString() == key }
    }
    return candidates
        .take(limit)
        .joinToString(" ") { "[yellow]${it.id}[gray]=[${it.color}]${it.name}[]" }
}

private fun unsafeTurretReason(block: Block): String? {
    val turret = block as? Turret ?: return null
    val hasValidAmmo = when (turret) {
        is ItemTurret -> turret.ammoTypes.entries().any { it.value != null }
        is LiquidTurret -> turret.ammoTypes.entries().any { it.value != null }
        is PowerTurret -> turret.shootType != null
        is ContinuousTurret -> turret.shootType != null
        else -> true
    }
    return if (hasValidAmmo) null else "该炮塔当前没有有效弹药/子弹配置，可能是地图CP加载异常或该炮塔被CP改坏，已拒绝放置以避免崩服。"
}

command("setBlock", "管理指令：设置脚下方块") {
    usage = "<方块ID> [队伍ID/队伍名]"
    permission = "wayzer.admin.setBlock"
    aliases = listOf("setblock", "settile", "设置方块", "方块")
    attr(ClientOnly)
    body {
        val sender = player!!
        val blockId = arg.getOrNull(0)?.trim().orEmpty()
        if (blockId.isBlank()) returnReply("[red]用法：/setBlock <方块ID> [队伍ID/队伍名]，例如 /setBlock power-node crux；队伍留空默认当前队伍。".with())

        val block = findBlock(blockId) ?: run {
            val suggestions = suggestBlocks(blockId)
            val suffix = if (suggestions.isBlank()) "" else "\n[gray]相近方块：$suggestions"
            returnReply("[red]未找到方块ID：[white]$blockId$suffix".with())
        }
        unsafeTurretReason(block)?.let { returnReply("[red]无法放置 [white]${block.name}[]：$it".with()) }
        val teamArg = arg.getOrNull(1)?.trim().orEmpty()
        val targetTeam = if (teamArg.isBlank()) sender.team() else findTeam(teamArg) ?: run {
            val suggestions = suggestTeams(teamArg)
            val suffix = if (suggestions.isBlank()) "" else "\n[gray]可用/相近队伍：$suggestions"
            returnReply("[red]未找到队伍：[white]$teamArg$suffix".with())
        }

        val tile = sender.unit().tileOn() ?: returnReply("[red]你当前不在地图内，无法设置脚下方块。".with())
        val oldBlock = tile.block()
        if (block == Blocks.air) {
            tile.setNet(Blocks.air)
        } else {
            tile.setNet(block, targetTeam, 0)
        }

        val teamText = if (block == Blocks.air) "" else "[green]，队伍：${teamName(targetTeam)}"
        reply(
            "[green]已将脚下 ([white]${tile.x},${tile.y}[green]) 的方块从 ${blockName(oldBlock)} [green]设置为 ${blockName(block)}$teamText".with()
        )
        logger.info("${sender.plainName()} setBlock at (${tile.x},${tile.y}): ${oldBlock.name} -> ${block.name}, team=${if (block == Blocks.air) "none" else targetTeam.name}")
    }
}

command("setFloor", "管理指令：设置脚下地板") {
    usage = "<地板ID>"
    permission = "wayzer.admin.setBlock"
    aliases = listOf("setfloor", "设置地板", "地板")
    attr(ClientOnly)
    body {
        val sender = player!!
        val floorId = arg.getOrNull(0)?.trim().orEmpty()
        if (floorId.isBlank()) returnReply("[red]用法：/setFloor <地板ID>，例如 /setFloor sand".with())

        val block = findBlock(floorId) ?: run {
            val suggestions = suggestBlocks(floorId)
            val suffix = if (suggestions.isBlank()) "" else "\n[gray]相近方块：$suggestions"
            returnReply("[red]未找到地板ID：[white]$floorId$suffix".with())
        }
        if (!block.isFloor) {
            returnReply("[red]目标不是地板：[white]$floorId\n[gray]请使用地板类ID，例如 sand / grass / water / core-zone".with())
        }

        val tile = sender.unit().tileOn() ?: returnReply("[red]你当前不在地图内，无法设置脚下地板。".with())
        val oldFloor = tile.floor()
        tile.setFloorNet(block)

        reply(
            "[green]已将脚下 ([white]${tile.x},${tile.y}[green]) 的地板从 ${blockName(oldFloor)} [green]设置为 ${blockName(block)}".with()
        )
        logger.info("${sender.plainName()} setFloor at (${tile.x},${tile.y}): ${oldFloor.name} -> ${block.name}")
    }
}

private fun parseFillMode(input: String): String? = when (input.trim().lowercase()) {
    "block", "blocks", "方块", "建筑", "building" -> "block"
    "floor", "floors", "地板", "地形", "地块" -> "floor"
    else -> null
}

private fun parseFillPolicy(input: String?): String? = when (input?.trim()?.lowercase() ?: "-cover") {
    "-cover", "cover", "replace", "-replace", "覆盖" -> "cover"
    "-keep", "keep", "保留" -> "keep"
    else -> null
}

private fun playerTileCoordinate(player: Player, axis: Char): Int? {
    val unit = runCatching { player.unit() }.getOrNull()?.takeIf { it.isValid && !it.dead } ?: return null
    return if (axis == 'x') unit.tileX() else unit.tileY()
}

private fun parseFillCoordinate(input: String, axis: Char, player: Player): Int? {
    val text = input.trim()
    if (text == "~") return playerTileCoordinate(player, axis)
    return text.toIntOrNull()
}

// Mindustry 多格建筑的锚点不是简单左下角：Tile.setBlock 内部使用 offset = -(size - 1) / 2。
// size=2 覆盖 anchor..anchor+1；size=3 覆盖 anchor-1..anchor+1；size=4 覆盖 anchor-1..anchor+2。
private fun multiBlockOffset(block: Block): Int = -(block.size - 1) / 2

private fun footprintCoords(anchorX: Int, anchorY: Int, block: Block): List<Pair<Int, Int>> {
    val offset = multiBlockOffset(block)
    val result = ArrayList<Pair<Int, Int>>(block.size * block.size)
    for (dx in 0 until block.size) for (dy in 0 until block.size) {
        result += anchorX + offset + dx to anchorY + offset + dy
    }
    return result
}

private fun fillUsage(): String =
    "[red]用法：/fill <block|floor> <x1> <y1> <x2> <y2> <目标方块/地形> [-cover|-keep]\n" +
            "[gray]坐标可用 ~ 表示自己当前单位所在格；示例：/fill block ~ ~ 10 5 copper-wall -keep；/fill block ~ ~ ~ ~ air；/fill floor 0 0 10 5 sand -cover"

command("fill", "管理指令：批量填充方块/地板") {
    usage = "<block|floor> <x1> <y1> <x2> <y2> <目标方块/地形> [-cover|-keep]"
    permission = "wayzer.admin.fill"
    aliases = listOf("填充")
    attr(ClientOnly)
    body {
        if (arg.size < 6) returnReply(fillUsage().with())
        val sender = player!!
        val mode = parseFillMode(arg[0]) ?: returnReply("[red]未知填充类型：[white]${arg[0]}[red]，只能是 block 或 floor。".with())
        val x1 = parseFillCoordinate(arg[1], 'x', sender) ?: returnReply("[red]x1 必须是整数或 ~。".with())
        val y1 = parseFillCoordinate(arg[2], 'y', sender) ?: returnReply("[red]y1 必须是整数或 ~。".with())
        val x2 = parseFillCoordinate(arg[3], 'x', sender) ?: returnReply("[red]x2 必须是整数或 ~。".with())
        val y2 = parseFillCoordinate(arg[4], 'y', sender) ?: returnReply("[red]y2 必须是整数或 ~。".with())
        val targetId = arg[5].trim()
        val policy = parseFillPolicy(arg.getOrNull(6)) ?: returnReply("[red]未知填充策略：[white]${arg.getOrNull(6)}[red]，只能是 -cover 或 -keep。".with())

        val minX = minOf(x1, x2)
        val maxX = maxOf(x1, x2)
        val minY = minOf(y1, y2)
        val maxY = maxOf(y1, y2)
        val width = maxX - minX + 1
        val height = maxY - minY + 1
        val total = width * height
        if (total <= 0) returnReply("[red]填充区域无效。".with())
        if (total > fillMaxTiles) {
            returnReply("[red]填充区域过大：[white]${total}[red] 格，当前单次上限为 [white]${fillMaxTiles}[red] 格。".with())
        }

        val target = findBlock(targetId) ?: run {
            val suggestions = suggestBlocks(targetId)
            val suffix = if (suggestions.isBlank()) "" else "\n[gray]相近方块：$suggestions"
            returnReply("[red]未找到目标方块/地形：[white]$targetId$suffix".with())
        }

        if (mode == "block") {
            if (target != Blocks.air && target.isFloor) returnReply("[red]目标是地板/地形：[white]$targetId[red]，请使用 /fill floor。".with())
            unsafeTurretReason(target)?.let { returnReply("[red]无法填充 [white]${target.name}[]：$it".with()) }
        } else {
            if (target == Blocks.air) returnReply("[red]air 只能用于 /fill block 清理建筑层，不能作为地板/地形填充。".with())
            if (!target.isFloor) returnReply("[red]目标不是地板/地形：[white]$targetId[red]，请使用 sand / grass / water / core-zone 等地形ID。".with())
        }

        var changed = 0
        var skipped = 0
        var outOfWorld = 0
        var placed = 0
        if (mode == "block" && target != Blocks.air && target.size > 1) {
            val offset = multiBlockOffset(target)
            val maxRelative = offset + target.size - 1
            val startAnchorX = minX - offset
            val endAnchorX = maxX - maxRelative
            val startAnchorY = minY - offset
            val endAnchorY = maxY - maxRelative

            if (startAnchorX <= endAnchorX && startAnchorY <= endAnchorY) {
                for (anchorX in startAnchorX..endAnchorX step target.size) for (anchorY in startAnchorY..endAnchorY step target.size) {
                    val coords = footprintCoords(anchorX, anchorY, target)
                    val tiles = coords.mapNotNull { (x, y) -> Vars.world.tile(x, y) }
                    if (tiles.size != coords.size) {
                        outOfWorld += coords.size - tiles.size
                        continue
                    }
                    if (policy == "keep" && tiles.any { it.block() != Blocks.air }) {
                        skipped += tiles.size
                        continue
                    }
                    val anchorTile = Vars.world.tile(anchorX, anchorY)
                    if (anchorTile == null) {
                        outOfWorld += tiles.size
                        continue
                    }
                    anchorTile.setNet(target, sender.team(), 0)
                    placed++
                    changed += tiles.size
                }
            }
        } else {
            for (x in minX..maxX) for (y in minY..maxY) {
                val tile = Vars.world.tile(x, y)
                if (tile == null) {
                    outOfWorld++
                    continue
                }
                if (mode == "block") {
                    if (policy == "keep" && tile.block() != Blocks.air) {
                        skipped++
                        continue
                    }
                    if (target == Blocks.air) tile.setNet(Blocks.air)
                    else tile.setNet(target, sender.team(), 0)
                    changed++
                } else {
                    if (policy == "keep" && tile.block() != Blocks.air) {
                        skipped++
                        continue
                    }
                    tile.setFloorNet(target)
                    changed++
                }
            }
        }

        val modeText = if (mode == "block") "建筑层" else "地板层"
        val policyText = if (policy == "cover") "覆盖" else "保留已有建筑"
        val placedText = if (placed > 0) "[green]，放置 [gold]$placed[green] 个 ${target.size}x${target.size} 建筑" else ""
        reply(
            "[green]已填充 $modeText [white]($minX,$minY)-($maxX,$maxY)[green] 为 ${blockName(target)}[green]，策略：[white]$policyText[green]；修改 [gold]$changed[green] 格$placedText[green]，跳过 [yellow]$skipped[green] 格，越界 [scarlet]$outOfWorld[green] 格。".with()
        )
        logger.info("${sender.plainName()} fill $mode ($minX,$minY)-($maxX,$maxY) -> ${target.name}, policy=$policy, changed=$changed, placed=$placed, skipped=$skipped, outOfWorld=$outOfWorld")
    }
}

PermissionApi.registerDefault("wayzer.admin.setBlock", group = "@admin")
PermissionApi.registerDefault("wayzer.admin.fill", group = "@admin")
