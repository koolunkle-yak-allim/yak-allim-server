plugins {
    id("org.springframework.boot") version "3.5.15"
    id("io.spring.dependency-management") version "1.1.7"
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.spring") version "2.0.21"
    kotlin("plugin.jpa") version "2.0.21"
    kotlin("kapt") version "2.0.21"
}

group = "com.example.yakallim"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

repositories {
    mavenCentral()
}

val onnxruntimeVersion: String by project
val firebaseAdminVersion: String by project
val guavaVersion: String by project
val protobufVersion: String by project
val grpcVersion: String by project
val springdocVersion: String by project
val ktorVersion: String by project

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    implementation("org.springframework.boot:spring-boot-starter-data-jpa")

    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")

    implementation("com.google.firebase:firebase-admin:$firebaseAdminVersion")
    implementation("com.microsoft.onnxruntime:onnxruntime:$onnxruntimeVersion")

    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:$springdocVersion")

    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")

    kapt("org.springframework.boot:spring-boot-configuration-processor")

    // Firebase Admin SDK 등이 전이적으로 끌어오는 Guava/Protobuf/gRPC 버전 충돌 방지를 위한 버전 고정
    constraints {
        implementation("com.google.guava:guava:$guavaVersion")
        implementation("com.google.protobuf:protobuf-java:$protobufVersion")
        implementation("io.grpc:grpc-netty-shaded:$grpcVersion")
    }

    runtimeOnly("com.h2database:h2")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
    testImplementation("io.ktor:ktor-client-mock:$ktorVersion")
}

configurations.all {
    exclude(group = "commons-logging", module = "commons-logging")
}

kotlin {
    jvmToolchain(17)

    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

tasks.test {
    useJUnitPlatform()
}
allOpen {
    annotation("javax.persistence.Entity")
    annotation("javax.persistence.Embeddable")
    annotation("javax.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.Embeddable")
    annotation("jakarta.persistence.MappedSuperclass")
}