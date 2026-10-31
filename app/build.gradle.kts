plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.maxime.photodream"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.maxime.photodream"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    // Clé de signature fournie avec le projet : chaque build (PC, GitHub…) produit
    // un APK signé pareil, ce qui permet d'installer les mises à jour par-dessus.
    signingConfigs {
        create("photodream") {
            storeFile = file("photodream.keystore")
            storePassword = "photodream"
            keyAlias = "photodream"
            keyPassword = "photodream"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("photodream")
        }
        debug {
            signingConfig = signingConfigs.getByName("photodream")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/versions/**",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.kotlin_module",
            )
        }
    }

    testOptions {
        unitTests {
            // Robolectric : vrai Android (Bitmap, EXIF, préférences) sur la JVM, sans appareil
            isIncludeAndroidResources = true
            // hors Robolectric, les appels Android renvoient des valeurs neutres au lieu de planter
            isReturnDefaultValues = true
        }
    }

    lint {
        abortOnError = true
        checkDependencies = false
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.hierynomus:smbj:0.14.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
