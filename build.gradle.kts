plugins {
    `java-library`
    `maven-publish`
}

group = (findProperty("group") as String?) ?: "com.github.dractical"
version = (findProperty("version") as String?) ?: "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

tasks.test {
    useJUnitPlatform()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    withSourcesJar()
    withJavadocJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifactId = project.name
            pom {
                name.set("Conduit")
                description.set("A lightweight, annotation-driven event bus for Java.")
                url.set("https://github.com/dractical/Conduit")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
                developers {
                    developer {
                        id.set("dractical")
                        name.set("dractical")
                        url.set("https://github.com/dractical")
                    }
                }
                scm {
                    url.set("https://github.com/dractical/Conduit")
                    connection.set("scm:git:https://github.com/dractical/Conduit.git")
                    developerConnection.set("scm:git:ssh://git@github.com:dractical/Conduit.git")
                }
            }
        }
    }
}
