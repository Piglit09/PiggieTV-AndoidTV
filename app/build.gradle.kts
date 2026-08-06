import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.piggie.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.piggie.tv"
        minSdk = 24
        targetSdk = 35
        versionCode = 5
        versionName = "0.8.6-beta.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("beta") {
            storeFile = project.rootProject.file(localProperties.getProperty("PTV_KEYSTORE_PATH") ?: "keystore.jks")
            storePassword = localProperties.getProperty("PTV_KEYSTORE_PASSWORD") ?: ""
            keyAlias = localProperties.getProperty("PTV_KEY_ALIAS") ?: ""
            keyPassword = localProperties.getProperty("PTV_KEY_PASSWORD") ?: ""
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            buildConfigField("boolean", "ENABLE_DIAGNOSTICS", "true")
            buildConfigField("boolean", "SHOW_PERFORMANCE_OVERLAY", "true")
            buildConfigField("boolean", "ENABLE_DISCOVERY_FAULT_INJECTION", "true")
        }
        create("beta") {
            initWith(getByName("release"))
            applicationIdSuffix = ".beta"
            versionNameSuffix = "-beta"
            signingConfig = signingConfigs.getByName("beta")
            buildConfigField("boolean", "ENABLE_DIAGNOSTICS", "true")
            buildConfigField("boolean", "SHOW_PERFORMANCE_OVERLAY", "false")
            buildConfigField("boolean", "ENABLE_DISCOVERY_FAULT_INJECTION", "false")
            isDebuggable = false
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("beta")
            buildConfigField("boolean", "ENABLE_DIAGNOSTICS", "false")
            buildConfigField("boolean", "SHOW_PERFORMANCE_OVERLAY", "false")
            buildConfigField("boolean", "ENABLE_DISCOVERY_FAULT_INJECTION", "false")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.leanback:leanback:1.2.0")
    implementation("io.coil-kt:coil:2.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    
    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.fragment:fragment-ktx:1.8.5")

    // Media3
    val media3Version = "1.5.1"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")
    implementation("androidx.media3:media3-common:$media3Version")
    implementation("androidx.media3:media3-session:$media3Version")
    implementation("androidx.media3:media3-exoplayer-hls:$media3Version")
    implementation("androidx.media3:media3-datasource-okhttp:$media3Version")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}
