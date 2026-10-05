plugins {
    id("com.android.application")
}

android {
    namespace = "dev.footprinthere.overtone"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.footprinthere.overtone"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.1"
    }

    // 공유용 서명 열쇠는 저장소 밖에 두고, 경로와 비밀번호는 ~/.gradle/gradle.properties 에서 읽는다.
    val storeFilePath = providers.gradleProperty("OVERTONE_STORE_FILE").orNull
    signingConfigs {
        if (storeFilePath != null) {
            create("release") {
                storeFile = file(storeFilePath)
                storePassword = providers.gradleProperty("OVERTONE_STORE_PASSWORD").get()
                keyAlias = providers.gradleProperty("OVERTONE_KEY_ALIAS").get()
                keyPassword = providers.gradleProperty("OVERTONE_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
