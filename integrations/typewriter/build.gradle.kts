plugins {
    kotlin("jvm") version "2.3.20"
    id("com.typewritermc.module-plugin") version "2.2.0"
}

group = "org.fetarute"
version = "0.1.0"

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    // FetaruteTCAddon 公开 API；运行时由服务器上的 FetaruteTCAddon 提供，不打进扩展
    compileOnly("org.fetarute:fetarute-api:${property("fetaruteApiVersion")}")
}

typewriter {
    namespace = "fetarute"

    extension {
        name = "FetaruteTC"
        shortDescription = "Give players FetaruteTC driving tasks from Typewriter."
        description = """
            |The FetaruteTC extension connects Typewriter to the FetaruteTCAddon dispatcher.
            |Give players a scheduled trip to drive (from a station, optionally only up to a given station),
            |react when they take over, stop at a station or finish the task, and read their driving state,
            |last score and completed task count as facts for quests and dialogue criteria.
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
}
