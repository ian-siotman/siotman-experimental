plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

tasks.test {
    useJUnitPlatform()
}

dependencies {
    val kotlinVersion: String by rootProject
    val springBootVersion: String by rootProject

    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinVersion")
    implementation("org.jetbrains.kotlin:kotlin-noarg:$kotlinVersion")
    implementation("org.jetbrains.kotlin:kotlin-allopen:$kotlinVersion")

    testImplementation(kotlin("test"))
    testImplementation(kotlin("test-junit5"))

    implementation("org.springframework.boot:spring-boot-gradle-plugin:$springBootVersion")
    implementation("org.springframework.boot:spring-boot-dependencies:$springBootVersion")
}

gradlePlugin {
    plugins {
        create("CommonConventions") {
            id = "common-conventions"
            implementationClass = "CommonConventions"
        }

        create("CommonKotlinConventions") {
            id = "common-kotlin-conventions"
            implementationClass = "CommonKotlinConventions"
        }

        create("CommonSpringConventions") {
            id = "common-spring-conventions"
            implementationClass = "CommonSpringConventions"
        }
    }
}

