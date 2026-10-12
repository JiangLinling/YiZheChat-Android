import java.security.MessageDigest

val isNextBuild = providers.gradleProperty("yizhe.next.enabled").orNull == "true"
val reviewedSdk = file(if (isNextBuild) "matrix-rust-sdk-next.aar" else "matrix-rust-sdk.aar")
if (isNextBuild) {
    check(reviewedSdk.isFile) { "Next builds require the reviewed Next Rust SDK AAR." }
    val actualSha256 = MessageDigest.getInstance("SHA-256")
        .digest(reviewedSdk.readBytes()).joinToString("") { "%02x".format(it) }
    check(actualSha256 == "3b39596671f70f1f1c88a854d23309ca383bde3bc955bdc04410996a4bd2c34a") {
        "Next Rust SDK differs from the reviewed cumulative source baseline."
    }
}
configurations.maybeCreate("default")
artifacts.add("default", reviewedSdk)
