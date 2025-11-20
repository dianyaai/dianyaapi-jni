plugins {
    id("com.android.library")
}

group = "com.dianya"
version = "0.1.0"

private val androidNativeDirProvider = providers.gradleProperty("androidNativeDir")

android {
    namespace = "com.dianya.api"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
    }

    sourceSets["main"].java.srcDir("../java/src/main/java")
    sourceSets["main"].manifest.srcFile("src/main/AndroidManifest.xml")

    androidNativeDirProvider.orNull?.let { nativePath ->
        sourceSets["main"].jniLibs.srcDirs(nativePath)
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    packaging {
        jniLibs {
            keepDebugSymbols += listOf("**/libdianyaapi_jni.so")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("com.google.code.gson", "gson", "2.11.0")
    compileOnly("org.jetbrains", "annotations", "24.1.0")
}

