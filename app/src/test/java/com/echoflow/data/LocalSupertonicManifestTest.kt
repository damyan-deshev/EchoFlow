package com.echoflow.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSupertonicManifestTest {
    @Test
    fun manifestIsPinnedAndComplete() {
        assertEquals(15, LocalSupertonicManifest.files.size)
        assertEquals(LocalSupertonicManifest.files.sumOf { it.bytes }, LocalSupertonicManifest.totalBytes)
        assertTrue(LocalSupertonicManifest.files.all { it.sha256.matches(Regex("[0-9a-f]{64}")) })
        assertTrue(LocalSupertonicManifest.files.any { it.path.endsWith("vector_estimator.mnn") })
        assertEquals(10, LocalSupertonicManifest.files.count { it.path.startsWith("voice_styles/") })
    }

    @Test
    fun providerStorageIsStableAndBackwardsCompatible() {
        assertEquals(TtsProvider.OnDevice, TtsProvider.fromStorage("on_device"))
        assertEquals(TtsProvider.Remote, TtsProvider.fromStorage("remote"))
        assertEquals(TtsProvider.Remote, TtsProvider.fromStorage(null))
        assertEquals(TtsProvider.Remote, TtsProvider.fromStorage("future_value"))
    }
}
