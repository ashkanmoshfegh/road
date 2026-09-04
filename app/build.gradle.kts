plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")                       // no version – inherited from root classpath
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"  // ← explicit version!
    id("kotlin-kapt")
    id("com.google.dagger.hilt.android") version "2.55"
}

android {
    namespace = "com.example.road"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.example.road"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        compose = true
    }

    // Add this packaging block to resolve the duplicate file error
    packaging {
        resources {
            merges += "META-INF/LICENSE.md"
            merges += "META-INF/NOTICE.md"   // add this line

        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    configurations.all {
        resolutionStrategy {
            force("com.squareup:javapoet:1.13.0")
        }
    }
}

dependencies {
    // Core
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("org.osmdroid:osmdroid-android:6.1.18")


    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")

    // GraphHopper
    implementation("com.graphhopper:graphhopper-core:6.0")
    implementation("org.slf4j:slf4j-simple:1.7.36")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
    // Hilt
    implementation("com.google.dagger:hilt-android:2.55")
    kapt("com.google.dagger:hilt-compiler:2.55")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    kapt("com.squareup:javapoet:1.13.0")
    implementation("javax.annotation:javax.annotation-api:1.3.2")
    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    implementation("androidx.preference:preference-ktx:1.2.1")
}

kapt {
    correctErrorTypes = true
}