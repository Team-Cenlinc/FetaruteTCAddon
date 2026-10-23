plugins {
    kotlin("jvm") version "2.3.20"
    id("com.typewritermc.module-plugin") version "2.2.0"
}

group = "org.fetarute"
version = "0.3.0"

base {
    archivesName = "FetaruteTCAddonExtension"
}

repositories {
    mavenCentral()
}

dependencies {
    // FetaruteTCAddon 公开 API（主工程的 apiJar）；运行时由服务器上的 FetaruteTCAddon 提供，不打进扩展
    compileOnly(project(path = ":", configuration = "fetaruteApi"))
}

typewriter {
    namespace = "fetarute"

    extension {
        name = "FetaruteTCAddon"
        shortDescription = "用 Typewriter 给玩家派 FTCA 驾驶任务与车掌任务。"
        description = """
            |FetaruteTC 扩展把 Typewriter 接到 FetaruteTCAddon 调度系统上。
            |给玩家派一趟表定车次去驾驶（从某站接班，可以只开到某个交班站），在玩家接班、每站停车、任务结束时触发剧情，
            |并把玩家是否在驾驶、任务进行到哪一步、驾驶记录作为事实，用于剧情目标与对话条件。
            |车掌任务同样可以派发，可以跟进每站作业、发车信号、异常报告与每趟成绩，并读取值乘状态与车掌记录。
            |需要 FetaruteTCAddon 1.14.0 或更新版本。
        """.trimMargin()
        engineVersion = property("typewriterEngineVersion") as String
        channel = com.typewritermc.moduleplugin.ReleaseChannel.BETA

        paper {
            dependency("FetaruteTCAddon")
        }
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        moduleName = "FetaruteTCAddonExtension"
    }
}
