plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "net.ixcoin.wallet"
    compileSdk = 34

    defaultConfig {
        applicationId = "net.ixcoin.wallet"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/INDEX.LIST",
                "org/bitcoin/production/*",
                "org/bitcoin/test/*",
            )
        }
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all {
            it.systemProperty("org.slf4j.simpleLogger.log.org.bitcoinj.core.Peer", "debug")
            it.systemProperty("org.slf4j.simpleLogger.log.org.bitcoinj.core.PeerGroup", "debug")
            it.testLogging { showStandardStreams = true }
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // SPV engine: bitcoinj supplies BIP32/BIP39 keys, signing, tx building and
    // the peer/bloom machinery. iXcoin's AuxPoW block format is layered on top
    // in net.ixcoin.wallet.core.
    implementation("org.bitcoinj:bitcoinj-core:0.15.10") {
        exclude(group = "org.slf4j", module = "slf4j-jdk14")
    }
    // bitcoinj declares guava at runtime scope, so it has to be requested
    // explicitly to be on the compile classpath.
    implementation("com.google.guava:guava:32.1.3-android")
    // bitcoinj's wallet format is generated against the full protobuf runtime;
    // protobuf-javalite is not a drop-in for it.
    implementation("com.google.protobuf:protobuf-java:3.25.3")
    implementation("org.slf4j:slf4j-api:2.0.13")
    implementation("com.github.tony19:logback-android:3.0.0")

    // QR codes for the receive screen
    implementation("com.google.zxing:core:3.5.3")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    testImplementation("junit:junit:4.13.2")
    // bitcoinj logs on class-init, so the JVM tests need an slf4j provider.
    testImplementation("org.slf4j:slf4j-simple:2.0.13")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
