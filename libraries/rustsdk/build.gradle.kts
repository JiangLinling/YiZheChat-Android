import java.security.MessageDigest

val isNextBuild = providers.gradleProperty("yizhe.next.enabled").orNull == "true"
val reviewedSdk = file(if (isNextBuild) "matrix-rust-sdk-next.aar" else "matrix-rust-sdk.aar")
if (isNextBuild) {
    check(reviewedSdk.isFile) { "Next builds require the reviewed Next Rust SDK AAR." }
    val actualSha256 = MessageDigest.getInstance("SHA-256")
        .digest(reviewedSdk.readBytes()).joinToString("") { "%02x".format(it) }
    check(actualSha256 == "4e8f98d557bc1cf3d4005faf55940921e2b1adb8d5362ef999fd09c956c2b02e") {
        "Next Rust SDK differs from the reviewed cumulative source baseline."
    }
}
configurations.maybeCreate("default")
artifacts.add("default", reviewedSdk)
