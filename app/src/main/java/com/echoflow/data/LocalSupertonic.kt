package com.echoflow.data

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest
import java.text.Normalizer
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

sealed interface LocalTtsModelState {
    data object Checking : LocalTtsModelState
    data object Missing : LocalTtsModelState
    data class Downloading(val downloaded: Long, val total: Long) : LocalTtsModelState
    data class Ready(val bytes: Long) : LocalTtsModelState
    data class Failed(val message: String) : LocalTtsModelState
}

data class LocalTtsModelFile(val path: String, val bytes: Long, val sha256: String)

/** Pinned, hash-verified Supertonic MNN v3 model payload. */
object LocalSupertonicManifest {
    const val REVISION = "c0425ea0b5884cdb9c23a99b2373dcdb47f142fa"
    const val REPOSITORY = "yunfengwang/supertonic-tts-mnn"
    val files = listOf(
        LocalTtsModelFile("mnn_models/fp16/duration_predictor.mnn", 1_901_628, "360761cb6e0addbf14add1a971dc8ea250a807eb3879bdcbf15b2f9ea137c309"),
        LocalTtsModelFile("mnn_models/fp16/text_encoder.mnn", 18_102_788, "7d48b256a791b147b5131727ee097799ff666ceadb4c4b3f681008f8cc478523"),
        LocalTtsModelFile("mnn_models/fp16/vector_estimator.mnn", 128_634_124, "8f3e3a62769b15b5d12abcbab41473ca94e311aeb9002677d1e41bd800b5d7d0"),
        LocalTtsModelFile("mnn_models/fp16/vocoder.mnn", 50_803_764, "b83c99651b78cc8042a90e6beab0bb30d629e55df80916bbccecda18e2bf1ade"),
        LocalTtsModelFile("mnn_models/unicode_indexer.json", 277_676, "9bf7346e43883a81f8645c81224f786d43c5b57f3641f6e7671a7d6c493cb24f"),
        LocalTtsModelFile("voice_styles/F1.json", 292_046, "bbdec6ee00231c2c742ad05483df5334cab3b52fda3ba38e6a07059c4563dbc2"),
        LocalTtsModelFile("voice_styles/F2.json", 292_423, "7c722c6a72707b1a77f035d67f0d1351ba187738e06f7683e8c72b1df3477fc6"),
        LocalTtsModelFile("voice_styles/F3.json", 290_794, "12f6ef2573baa2defa1128069cb59f203e3ab67c92af77b42df8a0e3a2f7c6ab"),
        LocalTtsModelFile("voice_styles/F4.json", 291_808, "c2fa764c1225a76dfc3e2c73e8aa4f70d9ee48793860eb34c295fff01c2e032b"),
        LocalTtsModelFile("voice_styles/F5.json", 291_479, "45966e73316415626cf41a7d1c6f3b4c70dbc1ba2bee5c1978ef0ce33244fc8d"),
        LocalTtsModelFile("voice_styles/M1.json", 291_748, "e35604687f5d23694b8e91593a93eec0e4eca6c0b02bb8ed69139ab2ea6b0a5b"),
        LocalTtsModelFile("voice_styles/M2.json", 292_055, "b76cbf62bac707c710cf0ae5aba5e31eea1a6339a9734bfae33ab98499534a50"),
        LocalTtsModelFile("voice_styles/M3.json", 290_198, "ea1ac35ccb91b0d7ecad533a2fbd0eec10c91513d8951e3b25fbba99954e159b"),
        LocalTtsModelFile("voice_styles/M4.json", 291_522, "ca8eefad4fcd989c9379032ff3e50738adc547eeb5e221b82593a6d7b3bac303"),
        LocalTtsModelFile("voice_styles/M5.json", 291_469, "dd22b92740314321f8ae11c5e87f8dd60d060f15dd3a632b5adf77f471f77af2"),
    )
    val totalBytes: Long = files.sumOf { it.bytes }
    fun url(file: LocalTtsModelFile) =
        "https://huggingface.co/$REPOSITORY/resolve/$REVISION/${file.path}?download=true"
}

class LocalSupertonicModelStore(
    context: Context,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .build(),
) {
    val root = File(context.filesDir, "supertonic-mnn-v3")
    private val mutex = Mutex()
    private val _state = MutableStateFlow<LocalTtsModelState>(LocalTtsModelState.Checking)
    val state: StateFlow<LocalTtsModelState> = _state.asStateFlow()

    suspend fun refresh() = withContext(Dispatchers.IO) {
        _state.value = if (allFilesValid()) {
            LocalTtsModelState.Ready(LocalSupertonicManifest.totalBytes)
        } else LocalTtsModelState.Missing
    }

    suspend fun download() = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                root.mkdirs()
                var complete = LocalSupertonicManifest.files
                    .filter { valid(File(root, it.path), it) }.sumOf { it.bytes }
                for (entry in LocalSupertonicManifest.files) {
                    val target = File(root, entry.path)
                    if (valid(target, entry)) continue
                    target.parentFile?.mkdirs()
                    val partial = File(target.path + ".part")
                    partial.delete()
                    val response = client.newCall(Request.Builder().url(LocalSupertonicManifest.url(entry)).build()).execute()
                    response.use {
                        if (!it.isSuccessful) throw IOException("Model download returned HTTP ${it.code}")
                        val body = it.body ?: throw IOException("Model download returned no data")
                        body.byteStream().use { input ->
                            partial.outputStream().use { output ->
                                val buffer = ByteArray(128 * 1024)
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    output.write(buffer, 0, count)
                                    complete += count
                                    _state.value = LocalTtsModelState.Downloading(
                                        complete.coerceAtMost(LocalSupertonicManifest.totalBytes),
                                        LocalSupertonicManifest.totalBytes,
                                    )
                                }
                            }
                        }
                    }
                    if (!valid(partial, entry)) {
                        partial.delete()
                        throw IOException("Downloaded model file failed verification: ${entry.path}")
                    }
                    if (target.exists() && !target.delete()) throw IOException("Could not replace ${entry.path}")
                    if (!partial.renameTo(target)) throw IOException("Could not install ${entry.path}")
                }
                _state.value = LocalTtsModelState.Ready(LocalSupertonicManifest.totalBytes)
            } catch (error: Throwable) {
                _state.value = LocalTtsModelState.Failed(error.message ?: "Model download failed")
                throw error
            }
        }
    }

    suspend fun requireReady(): File {
        if (state.value !is LocalTtsModelState.Ready) refresh()
        if (state.value !is LocalTtsModelState.Ready) {
            throw IOException("On-device Supertonic is not downloaded. Open Read aloud settings to install it.")
        }
        return root
    }

    private fun allFilesValid() = LocalSupertonicManifest.files.all { valid(File(root, it.path), it) }

    private fun valid(file: File, expected: LocalTtsModelFile): Boolean =
        file.isFile && file.length() == expected.bytes && sha256(file) == expected.sha256

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

private data class LocalVoiceStyle(
    val ttl: FloatArray,
    val ttlRows: Int,
    val ttlColumns: Int,
    val dp: FloatArray,
    val dpRows: Int,
    val dpColumns: Int,
) {
    companion object {
        fun load(file: File): LocalVoiceStyle {
            val root = JSONObject(file.readText())
            val ttl = tensor(root.getJSONObject("style_ttl"))
            val dp = tensor(root.getJSONObject("style_dp"))
            return LocalVoiceStyle(ttl.first, ttl.second[1], ttl.second[2], dp.first, dp.second[1], dp.second[2])
        }

        private fun tensor(node: JSONObject): Pair<FloatArray, IntArray> {
            val dimensions = node.getJSONArray("dims")
            val shape = IntArray(dimensions.length()) { dimensions.getInt(it) }
            val result = FloatArray(shape.fold(1) { total, value -> total * value })
            var offset = 0
            val a = node.getJSONArray("data")
            for (i in 0 until a.length()) {
                val b = a.getJSONArray(i)
                for (j in 0 until b.length()) {
                    val c = b.getJSONArray(j)
                    for (k in 0 until c.length()) result[offset++] = c.getDouble(k).toFloat()
                }
            }
            return result to shape
        }
    }
}

private class LocalUnicodeProcessor(indexFile: File) {
    private val indexer = JSONArray(indexFile.readText()).let { json ->
        IntArray(json.length()) { json.getInt(it) }
    }

    data class Tokens(val ids: IntArray, val mask: FloatArray)

    fun process(input: String, language: String): Tokens {
        var text = Normalizer.normalize(input, Normalizer.Form.NFKD)
        replacements.forEach { (from, to) -> text = text.replace(from, to) }
        text = text.replace(Regex("[♥☆♡©\\\\]"), "")
            .replace("@", " at ")
            .replace("e.g.,", "for example, ")
            .replace("i.e.,", "that is, ")
            .replace(Regex("\\s+"), " ").trim()
        if (!text.matches(endPunctuation)) text += "."
        val wrapped = "<$language>$text</$language>"
        val codePoints = wrapped.codePoints().toArray()
        return Tokens(
            IntArray(codePoints.size) { indexer[codePoints[it] and 0xffff] },
            FloatArray(codePoints.size) { 1f },
        )
    }

    private companion object {
        val replacements = linkedMapOf(
            "–" to "-", "‑" to "-", "—" to "-", "_" to " ", "“" to "\"", "”" to "\"",
            "‘" to "'", "’" to "'", "´" to "'", "`" to "'", "[" to " ", "]" to " ",
            "|" to " ", "/" to " ", "#" to " ", "→" to " ", "←" to " ",
        )
        val endPunctuation = Regex(".*[.!?;:,'\"“”‘’)\\]}…。」』】〉》›»]$", RegexOption.DOT_MATCHES_ALL)
    }
}

internal object LocalSupertonicNative {
    init {
        if (Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }) {
            System.loadLibrary("MNN")
            System.loadLibrary("MNN_Express")
            System.loadLibrary("MNN_CL")
        }
        System.loadLibrary("echoflow_supertonic")
    }

    external fun nativeCreate(modelDir: String, cachePath: String): Long
    external fun nativeSynthesize(
        handle: Long,
        textIds: IntArray,
        textMask: FloatArray,
        styleTtl: FloatArray,
        ttlRows: Int,
        ttlColumns: Int,
        styleDp: FloatArray,
        dpRows: Int,
        dpColumns: Int,
        steps: Int,
        speed: Float,
        seed: Long,
    ): ByteArray
    external fun nativeCloseAndSaveCache(handle: Long)
    external fun nativeClose(handle: Long)
}

class LocalSupertonicChunkSource(
    private val context: Context,
    val modelStore: LocalSupertonicModelStore = LocalSupertonicModelStore(context),
) : TtsAudioChunkSource, Closeable {
    private val inferenceMutex = Mutex()
    // MNN's OpenCL command queue is created and consumed on one stable thread. Dispatchers.IO may
    // resume consecutive chunks on different workers, which invalidates the queue on Pixel GPUs.
    private val inferenceDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "EchoFlow-Supertonic-OpenCL")
    }.asCoroutineDispatcher()
    private var processor: LocalUnicodeProcessor? = null
    private val styles = mutableMapOf<String, LocalVoiceStyle>()

    override suspend fun synthesize(text: String, options: TtsOptions): ByteArray =
        inferenceMutex.withLock {
            withContext(inferenceDispatcher) {
                val root = modelStore.requireReady()
                val tokenizer = processor ?: LocalUnicodeProcessor(
                    File(root, "mnn_models/unicode_indexer.json"),
                ).also { processor = it }
                val style = styles.getOrPut(options.voice) {
                    LocalVoiceStyle.load(File(root, "voice_styles/${options.voice}.json"))
                }
                val tokens = tokenizer.process(text, options.language ?: "na")
                val cache = File(root, "mnn-opencl.cache")
                repeat(MAX_SILENT_AUDIO_ATTEMPTS) { attempt ->
                    val needsInitialCacheWrite = !cache.isFile || cache.length() == 0L
                    // MNN 3.6.1 corrupts its OpenCL runtime after several differently-shaped
                    // Express graphs. Isolate each attempt in a fresh runtime while reusing the
                    // device-specific compiled-kernel cache.
                    val chunkHandle = LocalSupertonicNative.nativeCreate(
                        root.absolutePath,
                        cache.absolutePath,
                    )
                    val startedAt = SystemClock.elapsedRealtime()
                    var usableAudio = false
                    try {
                        val wav = LocalSupertonicNative.nativeSynthesize(
                            chunkHandle, tokens.ids, tokens.mask,
                            style.ttl, style.ttlRows, style.ttlColumns,
                            style.dp, style.dpRows, style.dpColumns,
                            options.steps, options.speed, System.nanoTime(),
                        )
                        val signal = wav.signalStats()
                        Log.i(
                            LOG_TAG,
                            "local chunk chars=${text.length} tokens=${tokens.ids.size} " +
                                "attempt=${attempt + 1} " +
                                "synthMs=${SystemClock.elapsedRealtime() - startedAt} $signal",
                        )
                        usableAudio = signal.isUsableFor(text.length)
                        if (usableAudio) return@withContext wav
                        Log.w(LOG_TAG, "silent/corrupt local audio; retrying chunk")
                    } finally {
                        if (usableAudio && needsInitialCacheWrite) {
                            // Seed once. Rewriting between utterances destabilizes MNN 3.6.1 on
                            // the Pixel OpenCL driver.
                            LocalSupertonicNative.nativeCloseAndSaveCache(chunkHandle)
                        } else {
                            LocalSupertonicNative.nativeClose(chunkHandle)
                        }
                    }
                }
                throw SilentLocalTtsAudioException(
                    "Supertonic produced silent audio after $MAX_SILENT_AUDIO_ATTEMPTS attempts",
                )
            }
        }

    override fun close() {
        processor = null
        styles.clear()
        inferenceDispatcher.close()
    }

    private companion object {
        const val LOG_TAG = "EchoFlowTTS"
        const val MAX_SILENT_AUDIO_ATTEMPTS = 3

        fun ByteArray.signalStats(): PcmSignalStats {
            if (size <= 44) return PcmSignalStats(size, 0, 0, 0, 0.0)
            var sumSquares = 0.0
            var peak = 0
            var active = 0
            var samples = 0
            var offset = 44
            while (offset + 1 < size) {
                val sample = ((this[offset].toInt() and 0xff) or
                    (this[offset + 1].toInt() shl 8)).toShort().toInt()
                val magnitude = kotlin.math.abs(sample)
                peak = maxOf(peak, magnitude)
                if (magnitude >= 256) active++
                sumSquares += sample.toDouble() * sample
                samples++
                offset += 2
            }
            val rms = if (samples == 0) 0 else kotlin.math.sqrt(sumSquares / samples).toInt()
            val activePct = if (samples == 0) 0.0 else active * 100.0 / samples
            val durationMs = samples * 1_000L / 44_100L
            return PcmSignalStats(size, durationMs, peak, rms, activePct)
        }
    }
}

class SilentLocalTtsAudioException(message: String) : IOException(message)

private data class PcmSignalStats(
    val wavBytes: Int,
    val audioMs: Long,
    val peak: Int,
    val rms: Int,
    val activePct: Double,
) {
    fun isUsableFor(textChars: Int): Boolean {
        // The proportional floor also rejects the tiny but non-empty WAV produced by some broken
        // OpenCL executions. It is deliberately far below normal Supertonic speaking duration.
        val minimumDurationMs = (textChars * 10L).coerceIn(250L, 2_000L)
        return audioMs >= minimumDurationMs && peak >= 512 && rms >= 64 && activePct >= 1.0
    }

    override fun toString(): String =
        "wavBytes=$wavBytes audioMs=$audioMs peak=$peak rms=$rms " +
            "activePct=${"%.1f".format(java.util.Locale.US, activePct)}"
}
