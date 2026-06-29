plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.bockmedia.console"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.bockmedia.console"
        minSdk = 26
        targetSdk = 35
        versionCode = 58
        versionName = "2.6.47"
        // Bock Media server endpoints (override via local.properties if needed)
        val localProps = rootProject.file("local.properties")
        fun prop(name: String) = if (localProps.exists()) {
            localProps.readLines()
                .firstOrNull { it.startsWith("$name=") }
                ?.substringAfter("=")
                ?.trim()
                ?: ""
        } else ""
        fun configMobileApiToken(): String {
            val config = rootProject.file("../config.json")
            if (!config.exists()) return ""
            return Regex(""""token"\s*:\s*"([^"]+)"""")
                .find(config.readText())
                ?.groupValues
                ?.get(1)
                ?.takeIf { it.isNotBlank() && !it.startsWith("SET_") && !it.startsWith("GENERATE_") }
                ?: ""
        }
        val localServerUrl = prop("bockmedia.localServerUrl")
            .ifBlank { "http://192.168.1.187:3001" }
        val externalServerUrl = prop("bockmedia.externalServerUrl")
            .ifBlank { "http://142.56.8.193:3001" }
        val mobileApiToken = prop("bockmedia.mobileApiToken").ifBlank { configMobileApiToken() }
        val adminUser = prop("bockmedia.adminUser")
        val adminPassword = prop("bockmedia.adminPassword")
        buildConfigField("String", "DEFAULT_LOCAL_SERVER_URL", "\"$localServerUrl\"")
        buildConfigField("String", "DEFAULT_EXTERNAL_SERVER_URL", "\"$externalServerUrl\"")
        buildConfigField("String", "DEFAULT_SERVER_URL", "\"$localServerUrl\"")
        buildConfigField("String", "DEFAULT_MOBILE_API_TOKEN", "\"$mobileApiToken\"")
        buildConfigField("String", "DEFAULT_ADMIN_USER", "\"$adminUser\"")
        buildConfigField("String", "DEFAULT_ADMIN_PASSWORD", "\"$adminPassword\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val keystorePropsFile = rootProject.file("keystore.properties")
            if (keystorePropsFile.exists()) {
                val props = keystorePropsFile.readLines()
                    .filter { it.contains("=") && !it.trimStart().startsWith("#") }
                    .associate { line ->
                        val (k, v) = line.split("=", limit = 2)
                        k.trim() to v.trim()
                    }
                val store = props["storeFile"]?.takeIf { it.isNotBlank() }
                if (store != null) {
                    storeFile = rootProject.file(store)
                    storePassword = props["storePassword"]
                    keyAlias = props["keyAlias"]
                    keyPassword = props["keyPassword"]
                }
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (rootProject.file("keystore.properties").exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        /** Full-size APK for NAS /app sideload (no R8 shrink — ~25 MB vs ~5 MB release). */
        create("sideload") {
            initWith(getByName("release"))
            isMinifyEnabled = false
            isShrinkResources = false
            matchingFallbacks += listOf("release")
            // Must be signed — unsigned APKs fail INSTALL_PARSE_FAILED_NO_CERTIFICATES on device.
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
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
        buildConfig = true
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.jakewharton.retrofit:retrofit2-kotlinx-serialization-converter:1.0.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.browser:browser:1.8.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.media:media:1.7.0")
    val media3 = "1.5.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-session:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    // Charts (Analytics uses Canvas — no Vico)

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
