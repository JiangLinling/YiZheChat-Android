import java.security.MessageDigest

val isNextBuild = providers.gradleProperty("yizhe.next.enabled").orNull == "true"
val reviewedSdk = file(if (isNextBuild) "matrix-rust-sdk-next.aar" else "matrix-rust-sdk.aar")
if (isNextBuild) {
    check(reviewedSdk.isFile) { "Next builds require the reviewed Next Rust SDK AAR." }
    val actualSha256 = MessageDigest.getInstance("SHA-256")
        .digest(reviewedSdk.readBytes()).joinToString("") { "%02x".format(it) }
    check(actualSha256 == "a707011204f2b46d0628c4c425ea3f7cefa3b3056ff600fa38e0a0b2399e8a5e") {
        "Next Rust SDK differs from the reviewed cumulative source baseline."
    }
}
configurations.maybeCreate("default")
artifacts.add("default", reviewedSdk)
