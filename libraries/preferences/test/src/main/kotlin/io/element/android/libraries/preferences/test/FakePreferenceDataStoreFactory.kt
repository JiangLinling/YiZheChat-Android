/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.preferences.test

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import io.element.android.libraries.preferences.api.store.PreferenceDataStoreFactory
import java.io.File
import java.nio.file.Files
import androidx.datastore.preferences.core.PreferenceDataStoreFactory as AndroidPreferenceDataStoreFactory

class FakePreferenceDataStoreFactory : PreferenceDataStoreFactory {
    override fun create(name: String): DataStore<Preferences> {
        val directory = Files.createTempDirectory("preferences-test").toFile().apply { deleteOnExit() }
        val storeFile = File(directory, "$name.preferences_pb").apply { deleteOnExit() }
        return AndroidPreferenceDataStoreFactory.create { storeFile }
    }
}
