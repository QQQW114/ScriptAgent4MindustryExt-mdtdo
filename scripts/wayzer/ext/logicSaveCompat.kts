package wayzer.ext

import arc.struct.Seq
import mindustry.logic.LVar
import mindustry.world.blocks.logic.LogicBlock

name = "逻辑块存档兼容修复"

/**
 * Mindustry v8/157.4 当前服务端的 TypeIO.writeObject 不接受 arc.struct.Seq。
 * 某些地图的逻辑块运行一段时间后，逻辑变量可能暂存 Seq，导致自动存档失败：
 *
 *   Unknown object type: class arc.struct.Seq
 *   at mindustry.world.blocks.logic.LogicBlock$LogicBuild.write
 *
 * 新版上游 TypeIO 已把 Seq 当 null 写入；这里在 SaveWriteEvent 前主动清理这类临时变量，
 * 只影响逻辑块运行时变量缓存，不修改逻辑代码本身。
 */

private fun cleanVar(v: LVar, build: LogicBlock.LogicBuild, cleanedNames: MutableList<String>): Boolean {
    val value = if (v.isobj) v.objval else null
    if (value !is Seq<*>) return false

    cleanedNames += "${v.name}@(${build.tile.x},${build.tile.y})"
    v.objval = null
    v.isobj = true
    return true
}

fun sanitizeLogicRuntimeVars(reason: String = "manual"): Int {
    var cleaned = 0
    val names = mutableListOf<String>()

    Groups.build.forEach { build ->
        val logic = build as? LogicBlock.LogicBuild ?: return@forEach
        val executor = logic.executor ?: return@forEach

        executor.unit?.let {
            if (cleanVar(it, logic, names)) cleaned++
        }
        executor.vars.forEach { v ->
            if (cleanVar(v, logic, names)) cleaned++
        }
    }

    if (cleaned > 0) {
        logger.warning(
            "清理逻辑块不可存档变量: $cleaned 个, reason=$reason, vars=${
                names.take(12).joinToString()
            }${if (names.size > 12) ", ..." else ""}"
        )
    }
    return cleaned
}

export(::sanitizeLogicRuntimeVars)

listen<EventType.SaveWriteEvent> {
    sanitizeLogicRuntimeVars("SaveWriteEvent")
}

command("logicSaveCheck", "检查并清理逻辑块中会导致存档失败的运行时变量") {
    permission = "wayzer.admin.logicSaveCompat"
    body {
        val cleaned = sanitizeLogicRuntimeVars("command:${player?.uuid() ?: "console"}")
        reply("[green]逻辑块存档兼容检查完成，清理变量数: [accent]{count}".with("count" to cleaned))
    }
}

PermissionApi.registerDefault("wayzer.admin.logicSaveCompat", group = "@admin")
