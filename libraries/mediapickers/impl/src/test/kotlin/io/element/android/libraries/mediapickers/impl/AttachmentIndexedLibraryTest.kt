package io.element.android.libraries.mediapickers.impl

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AttachmentIndexedLibraryTest {
    @Test
    fun `repeat opens and local search never repeat baseline or unchanged system query`() = runBlocking {
        val backend = Backend()
        withLibrary(backend) { library ->
            library.refresh(emptyList())
            await { backend.saved?.baselineComplete == true && !library.state.value.updating }
            repeat(3) { round ->
                library.refresh(emptyList())
                await { backend.reconciles > round && !library.state.value.updating }
                library.state.value.index!!.files.filter { it.name.contains("pdf") && matchesAttachmentCategory(it.name, "All") }
            }
            assertThat(backend.baselines).isEqualTo(1)
            assertThat(backend.mediaQueries).isEqualTo(1)
        }
        withLibrary(backend) { reopened ->
            reopened.refresh(emptyList())
            await { backend.reconciles == 4 && !reopened.state.value.updating }
            assertThat(reopened.state.value.index!!.files).hasSize(1)
            assertThat(backend.baselines).isEqualTo(1)
            assertThat(backend.mediaQueries).isEqualTo(1)
        }
    }

    @Test
    fun `provider generation replacement removes deleted indexed file and source update does not walk disk`() = runBlocking {
        val backend = Backend()
        withLibrary(backend) { library ->
            library.refresh(emptyList())
            await { backend.saved?.baselineComplete == true && !library.state.value.updating }
            backend.mediaIndex = EMPTY_ATTACHMENT_INDEX
            backend.stamp = "generation:2"
            library.refresh(emptyList(), sourcesChanged = true, dirty = setOf("/Documents"))
            await { backend.reconciles == 1 && !library.state.value.updating }
            assertThat(library.state.value.index!!.files).isEmpty()
            assertThat(backend.mediaQueries).isEqualTo(2)
            assertThat(backend.baselines).isEqualTo(1)
            assertThat(backend.lastDirty).containsExactly("/Documents")
            assertThat(backend.sourceQueries).isEqualTo(2)
        }
    }

    @Test
    fun `revoked permission removes global projection and persists inaccessible cache clearing`() = runBlocking {
        val backend = Backend()
        withLibrary(backend) { library ->
            library.refresh(emptyList())
            await { backend.saved?.baselineComplete == true && !library.state.value.updating }
            backend.allowed = false
            library.refresh(emptyList())
            await { backend.saved?.sharedAccess == false && !library.state.value.updating }
            assertThat(library.state.value.index!!.files).isEmpty()
            assertThat(backend.saved!!.media.files).isEmpty()
            assertThat(backend.saved!!.disk.files).isEmpty()
            backend.allowed = true
            library.refresh(emptyList())
            await { backend.baselines == 2 && !library.state.value.updating }
        }
    }

    @Test
    fun `queued requests preserve forced refresh and every dirty directory`() = runBlocking {
        val backend = Backend()
        withLibrary(backend) { library ->
            library.refresh(emptyList())
            await { backend.saved?.baselineComplete == true && !library.state.value.updating }
            backend.slowMedia = true
            library.refresh(emptyList(), force = true)
            await { backend.mediaQueries == 2 }
            library.refresh(emptyList(), force = true, dirty = setOf("/Documents"))
            library.refresh(emptyList(), dirty = setOf("/Download"))
            await { backend.reconciles == 2 && !library.state.value.updating }
            assertThat(backend.mediaQueries).isEqualTo(3)
            assertThat(backend.lastDirty).containsExactly("/Documents", "/Download")
            assertThat(backend.lastForce).isTrue()
        }
    }

    @Test
    fun `cancelled discovery cannot persist a completed baseline`() = runBlocking {
        val backend = Backend().apply { slowBaseline = true }
        withLibrary(backend) { library ->
            library.refresh(emptyList())
            await { backend.baselines == 1 }
            library.close()
            assertThat(backend.saved!!.baselineComplete).isFalse()
        }
    }

    private suspend fun await(condition: () -> Boolean) = withTimeout(10_000) {
        while (!condition()) delay(10)
    }

    private suspend fun withLibrary(backend: Backend, block: suspend (AttachmentIndexedLibrary) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val library = AttachmentIndexedLibrary(backend, scope)
        try {
            block(library)
        } finally {
            library.close()
            scope.cancel()
        }
    }

    private class Backend : AttachmentIndexBackend {
        @Volatile var allowed = true
        @Volatile var stamp = "generation:1"
        @Volatile var saved: AttachmentIndexSnapshot? = null
        @Volatile var baselines = 0
        @Volatile var mediaQueries = 0
        @Volatile var sourceQueries = 0
        @Volatile var reconciles = 0
        @Volatile var slowMedia = false
        @Volatile var slowBaseline = false
        @Volatile var lastDirty = emptySet<String>()
        @Volatile var lastForce = false
        @Volatile var mediaIndex = AttachmentIndex(listOf(AttachmentFile(
            Uri.parse("file:///Documents/a.pdf"),
            "a.pdf",
            "application/pdf",
            10L,
            100,
            100,
            "Documents",
            "root"
        )), emptyList(), 0, false)
        override fun hasSharedAccess() = allowed
        override fun allowedSources(sources: List<AttachmentSource>) = sources
        override suspend fun load() = saved
        override suspend fun save(snapshot: AttachmentIndexSnapshot) {
            saved = snapshot
        }
        override suspend fun sources(sources: List<AttachmentSource>): AttachmentIndex {
            sourceQueries++
            return EMPTY_ATTACHMENT_INDEX
        }
        override suspend fun mediaStamp() = stamp
        override suspend fun media(): AttachmentIndex {
            mediaQueries++
            if (slowMedia) delay(100)
            return mediaIndex
        }
        override suspend fun baseline(directories: MutableMap<String, Long>, onUpdate: suspend (AttachmentIndex) -> Unit): AttachmentIndex {
            baselines++
            if (slowBaseline) delay(60_000)
            return EMPTY_ATTACHMENT_INDEX
        }
        override suspend fun reconcile(snapshot: AttachmentIndexSnapshot, dirty: Set<String>, force: Boolean): AttachmentIndexSnapshot {
            lastDirty = dirty
            lastForce = force
            reconciles++
            return snapshot
        }
        override fun watchDirectories(snapshot: AttachmentIndexSnapshot) = emptyList<String>()
    }
}
