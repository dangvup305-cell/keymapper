// 顶层构建脚本：统一声明插件版本（子 module 只 apply，不再写版本）
// AGP 8.2.2 / Kotlin 1.9.22（Kotlin Gradle Plugin 与 stdlib 版本保持一致）
plugins {
    id("com.android.application") version "8.2.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.22" apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.buildDir)
}
