plugins {
    id("java-library")
    id("maven-publish")
    id("eclipse")
}

repositories {
    mavenCentral()
}

group = "net.minecraft"
version = "2.1"

dependencies {
    api("net.sf.jopt-simple:jopt-simple:5.0.4")
    api("org.apache.logging.log4j:log4j-api:2.14.1")
}

java {
    withSourcesJar()
    withJavadocJar()
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

publishing {
    publications {

        create<MavenPublication>("maven") {
            groupId = project.group.toString()
            artifactId = project.name
            version = project.version.toString()
            from(components["java"])
        }
    }
}