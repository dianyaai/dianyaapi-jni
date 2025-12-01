plugins {
    `java-library`
}

group = "com.dianya"
version = "0.2.1"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.google.code.gson", "gson", "2.11.0")
    compileOnly("org.jetbrains", "annotations", "24.1.0")
}

private val jarNativeDirProvider = providers.gradleProperty("jarNativeDir")

tasks.jar {
    archiveBaseName.set("dianyaapi-jni")
    description = "打包 Java 层 SDK"

    jarNativeDirProvider.orNull?.let { path ->
        val dir = file(path)
        if (dir.exists()) {
            from(dir) {
                into("META-INF/lib")
            }
        } else {
            logger.warn("native 库目录不存在: {}", dir.absolutePath)
        }
    }
}

