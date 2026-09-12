import java.util.Properties
import java.security.KeyStore
import java.security.SecureRandom
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    id("com.diffplug.spotless")
}
android {
    namespace = "com.oki"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "com.oki"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    testOptions { unitTests.isReturnDefaultValues = true }
    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")
    lint { abortOnError = true; checkReleaseBuilds = true }
}
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
spotless { kotlin { target("src/**/*.kt"); ktfmt("0.54").kotlinlangStyle() } }
dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.05.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.navigation:navigation-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.room:room-runtime:2.7.1")
    implementation("androidx.room:room-ktx:2.7.1")
    ksp("androidx.room:room-compiler:2.7.1")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    implementation("androidx.exifinterface:exifinterface:1.4.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.room:room-testing:2.7.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// Opt-in personal distribution. No credential is generated into Kotlin, BuildConfig, or XML.
// The encrypted envelope is bound to the APK signing certificate, then re-wrapped by Android Keystore.
// The certificate is public: this obscures credentials, it cannot make a client-side secret unrecoverable.
val embedAiCredentials = providers.gradleProperty("embedAiCredentials").orNull == "true"
val localSigning = Properties().apply {
    val file = rootProject.file(".signing.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
if (localSigning.isNotEmpty()) {
    android.signingConfigs.create("personalRelease") {
        storeFile = file(localSigning.getProperty("storeFile"))
        storePassword = localSigning.getProperty("storePassword")
        keyAlias = localSigning.getProperty("keyAlias")
        keyPassword = localSigning.getProperty("keyPassword")
    }
    android.buildTypes.getByName("release").signingConfig = android.signingConfigs.getByName("personalRelease")
}
listOf("debug", "release").forEach { variant ->
    val outputDir = layout.buildDirectory.dir("generated/privateBootstrap/$variant")
    android.sourceSets.getByName(variant).assets.srcDir(outputDir)
    val task = tasks.register("generate${variant.replaceFirstChar(Char::uppercase)}PrivateBootstrap") {
        if (variant == "debug") dependsOn("validateSigningDebug")
        outputs.dir(outputDir)
        outputs.upToDateWhen { false }
        doLast {
            val folder = outputDir.get().asFile.apply { mkdirs() }
            val output = folder.resolve("athii-bootstrap.bin")
            if (!embedAiCredentials) { output.delete(); return@doLast }
            val config = android.buildTypes.getByName(variant).signingConfig
                ?: error("Private credential builds require an APK signing configuration.")
            val store = KeyStore.getInstance(KeyStore.getDefaultType())
            config.storeFile!!.inputStream().use { store.load(it, config.storePassword!!.toCharArray()) }
            val certificate = store.getCertificate(config.keyAlias!!).encoded
            val env = rootProject.file(".env").readLines().filter { '=' in it && !it.trimStart().startsWith('#') }.associate {
                val (name, value) = it.split('=', limit = 2)
                name.trim() to value.trim().trim('"', '\'')
            }
            val gemini = env["GEMINI_API_KEY"] ?: env["GOOGLE_API"] ?: error("Gemini credential missing from local .env")
            val groq = env["GROQ_API_KEY"] ?: env["GROK_API"] ?: error("Groq credential missing from local .env")
            val payload = groovy.json.JsonOutput.toJson(mapOf("GEMINI" to gemini, "GROQ" to groq)).toByteArray()
            val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val key = MessageDigest.getInstance("SHA-256").digest(certificate + salt + "athii-bootstrap-v1:com.oki".toByteArray())
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"))
            output.writeBytes(salt + cipher.iv + cipher.doFinal(payload))
            payload.fill(0); key.fill(0)
        }
    }
    tasks.matching { (it.name == "merge${variant.replaceFirstChar(Char::uppercase)}Assets" || (it.name.contains("lint", ignoreCase = true) && it.name.contains(variant, ignoreCase = true))) }.configureEach { dependsOn(task) }
}
