plugins {
    kotlin("jvm") version "2.3.0"
}

group = "org.winand"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    implementation("io.qtjambi:qtjambi:6.10.2")
    implementation("io.qtjambi:qtjambi-native-windows-x64:6.10.2")
}

kotlin {
    jvmToolchain(25)
}

tasks.test {
    useJUnitPlatform()
}
