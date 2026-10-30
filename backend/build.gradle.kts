plugins { alias(libs.plugins.kotlin.jvm); alias(libs.plugins.kotlin.serialization); application }
kotlin { jvmToolchain(17) }
application { mainClass.set("com.harshkatariya.fieldflow.server.ServerKt") }
dependencies {
    implementation(project(":shared"))
    implementation(libs.serialization)
    listOf("core", "netty", "content-negotiation", "auth", "status-pages", "cors", "call-logging").forEach {
        implementation("io.ktor:ktor-server-$it-jvm:2.3.13")
    }
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:2.3.13")
    implementation("org.jetbrains.exposed:exposed-core:0.60.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.60.0")
    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("org.postgresql:postgresql:42.7.5")
    implementation("org.flywaydb:flyway-core:10.22.0")
    implementation("org.flywaydb:flyway-database-postgresql:10.22.0")
    implementation("org.mindrot:jbcrypt:0.4")
    implementation("ch.qos.logback:logback-classic:1.5.18")
    testImplementation("io.ktor:ktor-server-test-host-jvm:2.3.13")
    testImplementation(libs.junit)
    testImplementation("com.h2database:h2:2.3.232")
}
tasks.test { testLogging { events("passed", "skipped", "failed") } }
