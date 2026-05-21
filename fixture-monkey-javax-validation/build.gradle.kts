plugins {
    id("com.navercorp.fixturemonkey.gradle.plugin.java-conventions")
    id("com.navercorp.fixturemonkey.gradle.plugin.maven-publish-conventions")
}

dependencies {
    api(projects.fixtureMonkeyApi)
    api(libs.javax.validation.api)

    testImplementation(projects.fixtureMonkey)
    testImplementation(libs.junit.jupiter.engine)
    testImplementation(libs.junit.platform.engine)
    testImplementation(libs.assertj.core)
    testImplementation(libs.lombok)
    testAnnotationProcessor(libs.lombok)
    testRuntimeOnly(libs.hibernate.validator6)
    testRuntimeOnly(libs.jakarta.el3)
}

