@file:Depends("coreLibrary")
@file:Import("org.jetbrains.exposed:exposed-core:0.59.0", mavenDepends = true)
@file:Import("org.jetbrains.exposed:exposed-dao:0.59.0", mavenDepends = true)
@file:Import("org.jetbrains.exposed:exposed-java-time:0.59.0", mavenDepends = true)
@file:Import("org.jetbrains.exposed:exposed-jdbc:0.59.0", mavenDepends = true)

// SA 3.4 将数据库 API 放入独立模块，避免脚本类加载器隔离导致服务对象被复制。
