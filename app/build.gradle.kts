import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

val enableInternalSqlBuild = providers.gradleProperty("enableInternalSqlBuild")
    .map { it.toBooleanStrict() }.getOrElse(false)

// Nur die ausdruecklich aktivierte interne Variante liest SQL-Zugangsdaten.
val internalSqlProperties = Properties().apply {
    if (enableInternalSqlBuild) {
        val propertiesFile = rootProject.file("local.properties")
        require(propertiesFile.isFile) { "Lokale Konfiguration fuer internalSql fehlt." }
        propertiesFile.inputStream().use(::load)
        for (key in listOf("sqlServerHost", "sqlServerDatabase", "sqlServerUsername", "sqlServerPassword")) {
            require(!getProperty(key).isNullOrBlank()) { "Lokale Konfiguration fuer internalSql unvollstaendig: $key" }
        }
    }
}

fun buildConfigString(name: String): String {
    return "\"" + name.replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""
}

android {
    namespace = "com.example.myapplication"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.myapplication"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "SQL_SERVER_HOST", buildConfigString(""))
        buildConfigField("int", "SQL_SERVER_PORT", "1433")
        buildConfigField("String", "SQL_SERVER_DATABASE", buildConfigString(""))
        buildConfigField("String", "SQL_SERVER_SCHEMA", buildConfigString(""))
        buildConfigField("String", "SQL_SERVER_USERNAME", buildConfigString(""))
        buildConfigField("String", "SQL_SERVER_PASSWORD", buildConfigString(""))
        buildConfigField("boolean", "SQL_EINZELVERSUCH_ERLAUBT", "false")
    }

    buildTypes {
        if (enableInternalSqlBuild) {
            create("internalSql") {
                initWith(getByName("debug"))
                matchingFallbacks += listOf("debug")
                buildConfigField("boolean", "SQL_EINZELVERSUCH_ERLAUBT", "true")
                buildConfigField("String", "SQL_SERVER_HOST", buildConfigString(internalSqlProperties.getProperty("sqlServerHost")))
                val sqlPort = internalSqlProperties.getProperty("sqlServerPort", "1433").toIntOrNull()
                require(sqlPort != null && sqlPort in 1..65535) { "Lokaler SQL-Port fuer internalSql ungueltig." }
                buildConfigField("int", "SQL_SERVER_PORT", sqlPort.toString())
                buildConfigField("String", "SQL_SERVER_DATABASE", buildConfigString(internalSqlProperties.getProperty("sqlServerDatabase")))
                buildConfigField("String", "SQL_SERVER_SCHEMA", buildConfigString(internalSqlProperties.getProperty("sqlServerSchema", "dbo")))
                buildConfigField("String", "SQL_SERVER_USERNAME", buildConfigString(internalSqlProperties.getProperty("sqlServerUsername")))
                buildConfigField("String", "SQL_SERVER_PASSWORD", buildConfigString(internalSqlProperties.getProperty("sqlServerPassword")))
            }
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        buildConfig = true
    }
    sourceSets {
        getByName("test").java.srcDir("src/sqlAdapterTest/java")
        getByName("androidTest").java.srcDir("src/sqlAdapterTest/java")
    }
}

dependencies {
    implementation(libs.activity.ktx)
    implementation(libs.appcompat)
    implementation(libs.constraintlayout)
    implementation(libs.material)
    implementation(libs.recyclerview)
    implementation("net.sourceforge.jtds:jtds:1.3.1")
    testImplementation(libs.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
}
