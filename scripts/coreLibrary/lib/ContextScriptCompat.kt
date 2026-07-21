package coreLibrary.lib

/**
 * SA 3.4 将原 contextScript<T>() 的泛型约束收紧为 Script，
 * 老脚本依赖的 Kotlin 脚本生成类不再能直接通过类型约束。
 * 保留旧语义：按生成脚本类查找已加载依赖并转为对应API类型。
 */
@Suppress("UNCHECKED_CAST")
inline fun <reified T : Any> contextScript(): T {
    val method = Class.forName("cf.wayzer.scriptAgent.ScriptExtKt")
        .getMethod("getContextScript", Class::class.java)
    return method.invoke(null, T::class.java) as T
}
