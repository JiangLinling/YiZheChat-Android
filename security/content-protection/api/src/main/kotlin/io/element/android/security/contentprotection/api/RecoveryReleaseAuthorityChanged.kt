/* SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.security.contentprotection.api

/** A profile-release snapshot changed before its atomic commit. This is not authorization.
 * Only an owned recovery may revalidate signed authority before a single bounded retry.
 */
class RecoveryReleaseAuthorityChanged : Exception("RECOVERY_RELEASE_AUTHORITY_CHANGED")
