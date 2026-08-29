// Top-level build file
buildscript {
    repositories {
        maven {
            url = uri("https://maven.aliyun.com/repository/google")
        }
        maven {
            url = uri("https://maven.aliyun.com/repository/gradle-plugin")
        }
        mavenCentral()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.6.0")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.21")
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.buildDir)
}