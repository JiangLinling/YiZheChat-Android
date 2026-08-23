/*
 * YiZheChat brand resources.
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

plugins {
    id("io.element.android-compose-library")
}

android {
    namespace = "cn.yizhesy.yizhechat.appicon"

    buildTypes {
        register("nightly")
    }
}
