plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin { jvmToolchain(17) }

dependencies {
    // NIP-01 Schnorr curve primitives. The application implements protocol framing
    // but delegates secp256k1 point arithmetic to a maintained crypto provider.
    implementation("org.bouncycastle:bcprov-jdk18on:1.83")
    api(project(":core:model"))
    testImplementation("junit:junit:4.13.2")
}

tasks.test { useJUnit() }
