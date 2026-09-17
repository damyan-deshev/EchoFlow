package com.echoflow.ui.screens.chat

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.echoflow.data.TtsAudioChunkSource
import com.echoflow.data.LocalSupertonicChunkSource
import com.echoflow.data.LocalTtsModelState
import com.echoflow.data.SilentLocalTtsAudioException
import com.echoflow.data.TtsOptions
import com.echoflow.data.TtsProvider
import com.echoflow.data.TtsTextChunker
import java.io.Closeable
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject

internal enum class ReadAloudPhase { Idle, Loading, Playing }

internal data class ReadAloudState(
    val messageKey: String? = null,
    val phase: ReadAloudPhase = ReadAloudPhase.Idle,
    val error: String? = null,
)

internal class RemoteSupertonicChunkSource(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(95, TimeUnit.SECONDS)
        .build(),
) : TtsAudioChunkSource {
    override suspend fun synthesize(text: String, options: TtsOptions): ByteArray =
        suspendCancellableCoroutine { continuation ->
            val payload = JSONObject()
                .put("text", text)
                .put("voice", options.voice)
                .put("speed", options.speed)
                .put("steps", options.steps)
                .put("silence_duration", options.silenceDuration)
                .put("response_format", "wav")
                .apply { options.language?.let { put("lang", it) } }
                .toString()
            val request = Request.Builder()
                .url(options.baseUrl.trimEnd('/') + "/v1/tts")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!continuation.isCompleted) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            if (!response.isSuccessful) {
                                throw IOException("Supertonic returned HTTP ${response.code}")
                            }
                            val body = response.body ?: throw IOException("Supertonic returned no audio")
                            val length = body.contentLength()
                            if (length > MAX_CHUNK_BYTES) {
                                throw IOException("Supertonic chunk is unexpectedly large")
                            }
                            val bytes = body.bytes()
                            if (bytes.size > MAX_CHUNK_BYTES || !bytes.isWave()) {
                                throw IOException("Supertonic returned invalid WAV audio")
                            }
                            if (!continuation.isCompleted) continuation.resume(bytes)
                        } catch (error: Throwable) {
                            if (!continuation.isCompleted) continuation.resumeWithException(error)
                        }
                    }
                }
            })
        }

    private fun ByteArray.isWave(): Boolean =
        size >= 12 && String(this, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(this, 8, 4, Charsets.US_ASCII) == "WAVE"

    private companion object {
        const val MAX_CHUNK_BYTES = 16 * 1024 * 1024
    }
}

/**
 * A live Winamp-style playlist: each completed Supertonic WAV is appended as the next song while
 * Media3 plays the current one. No complete-answer WAV is ever assembled.
 */
internal class StreamingTtsController(
    context: Context,
    private val remoteSource: TtsAudioChunkSource = RemoteSupertonicChunkSource(),
    private val localSource: LocalSupertonicChunkSource = LocalSupertonicChunkSource(context.applicationContext),
    private val optionsProvider: () -> TtsOptions = { TtsOptions() },
) : Closeable {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val wavChunks = ConcurrentHashMap<String, ByteArray>()
    private val chunkIndexes = ConcurrentHashMap<String, Int>()
    private val player = ExoPlayer.Builder(appContext)
        .setMediaSourceFactory(DefaultMediaSourceFactory(appContext).setDataSourceFactory(
            InMemoryWavDataSourceFactory(wavChunks),
        ))
        .build().apply {
        setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                .build(),
            true,
        )
    }
    private val _state = MutableStateFlow(ReadAloudState())
    val state: StateFlow<ReadAloudState> = _state.asStateFlow()
    val localModelState: StateFlow<LocalTtsModelState> = localSource.modelStore.state
    private var playbackJob: Job? = null
    private var activeToken: Any? = null
    private var activeMessageKey: String? = null
    private var producerFinished = false

    init {
        scope.launch { localSource.modelStore.refresh() }
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                val key = activeMessageKey ?: return
                if (isPlaying) _state.value = ReadAloudState(key, ReadAloudPhase.Playing)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                val key = activeMessageKey ?: return
                Log.i(
                    LOG_TAG,
                    "player state=${playbackState.label()} chunk=${currentChunkLabel()} " +
                        "positionMs=${player.currentPosition} durationMs=${player.duration} " +
                        "queued=${player.mediaItemCount}",
                )
                if (playbackState == Player.STATE_ENDED) {
                    if (producerFinished) finishPlayback() else {
                        _state.value = ReadAloudState(key, ReadAloudPhase.Loading)
                    }
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                Log.i(
                    LOG_TAG,
                    "player transition chunk=${mediaItem?.mediaId?.let(chunkIndexes::get)} " +
                        "reason=$reason queued=${player.mediaItemCount}",
                )
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(LOG_TAG, "player error chunk=${currentChunkLabel()}", error)
                fail(error.message ?: "Android could not play the Supertonic WAV")
            }
        })
    }

    fun toggle(messageKey: String, text: String, optionsOverride: TtsOptions? = null) {
        if (activeMessageKey == messageKey) {
            stop()
            return
        }
        stop()
        val chunks = TtsTextChunker.chunk(text)
        if (chunks.isEmpty()) return
        val options = optionsOverride ?: optionsProvider()
        // Remote audio is already synthesized at the requested speed. A local preview may have
        // changed ExoPlayer's playback rate, so never let that leak into ordinary read-aloud.
        player.setPlaybackSpeed(1f)

        val token = Any()
        activeToken = token
        activeMessageKey = messageKey
        producerFinished = false
        _state.value = ReadAloudState(messageKey, ReadAloudPhase.Loading)

        playbackJob = scope.launch {
            try {
                fun appendWav(index: Int, wav: ByteArray) {
                    if (activeToken !== token) throw CancellationException()
                    val chunkId = "${UUID.randomUUID()}-$index"
                    wavChunks[chunkId] = wav
                    chunkIndexes[chunkId] = index
                    val wasEnded = player.playbackState == Player.STATE_ENDED
                    player.addMediaItem(
                        MediaItem.Builder()
                            .setMediaId(chunkId)
                            .setUri("echoflow-tts://chunk/$chunkId")
                            .build(),
                    )
                    Log.i(
                        LOG_TAG,
                        "player queued chunk=$index wavBytes=${wav.size} queueSize=${player.mediaItemCount}",
                    )
                    if (index == 0) {
                        player.prepare()
                        player.play()
                    } else if (wasEnded) {
                        // A slow network may let the current queue drain. Resume the newly
                        // appended song instead of treating the temporary underrun as completion.
                        player.seekTo(player.mediaItemCount - 1, 0)
                        player.prepare()
                        player.play()
                    }
                }

                if (options.provider == TtsProvider.OnDevice) {
                    val remaining = ArrayDeque(chunks)
                    var outputIndex = 0
                    while (remaining.isNotEmpty()) {
                        val chunk = remaining.removeFirst()
                        try {
                            val wav = synthesizeWithRetry(chunk, options)
                            appendWav(outputIndex, wav)
                            outputIndex++
                        } catch (silent: SilentLocalTtsAudioException) {
                            val retryPieces = TtsTextChunker.splitForRetry(chunk)
                            if (retryPieces.size < 2) throw silent
                            Log.w(
                                LOG_TAG,
                                "splitting silent chunk chars=${chunk.length} into=" +
                                    retryPieces.joinToString(",") { it.length.toString() },
                            )
                            retryPieces.asReversed().forEach(remaining::addFirst)
                        }
                    }
                } else {
                    // Keep the first request alone so time-to-first-audio stays low.
                    appendWav(0, synthesizeWithRetry(chunks.first(), options))

                    // Once playback has started, synthesize a small look-ahead window in parallel.
                    // Results are still awaited and appended in source-text order.
                    coroutineScope {
                        val pending = ArrayDeque<Pair<Int, Deferred<ByteArray>>>()
                        var nextIndex = 1

                        fun fillWindow() {
                            while (pending.size < PREFETCH_WINDOW && nextIndex < chunks.size) {
                                val index = nextIndex++
                                pending += index to async {
                                    synthesizeWithRetry(chunks[index], options)
                                }
                            }
                        }

                        fillWindow()
                        while (pending.isNotEmpty()) {
                            val (index, request) = pending.removeFirst()
                            appendWav(index, request.await())
                            fillWindow()
                        }
                    }
                }
                producerFinished = true
                if (player.playbackState == Player.STATE_ENDED) finishPlayback()
            } catch (_: CancellationException) {
                throw CancellationException()
            } catch (error: Throwable) {
                fail(error.message ?: "Read aloud failed")
            }
        }
    }

    /** Play a bundled single-utterance WAV without contacting the TTS endpoint. */
    fun toggleAsset(messageKey: String, assetPath: String, playbackSpeed: Float = 1f) {
        if (activeMessageKey == messageKey) {
            stop()
            return
        }
        stop()
        val token = Any()
        activeToken = token
        activeMessageKey = messageKey
        producerFinished = false
        _state.value = ReadAloudState(messageKey, ReadAloudPhase.Loading)

        playbackJob = scope.launch {
            try {
                val wav = withContext(Dispatchers.IO) {
                    appContext.assets.open(assetPath).use { it.readBytes() }
                }
                if (!wav.isWave()) throw IOException("Bundled voice preview is not a valid WAV")
                if (activeToken !== token) throw CancellationException()
                val chunkId = UUID.randomUUID().toString()
                wavChunks[chunkId] = wav
                player.setPlaybackSpeed(playbackSpeed.coerceIn(0.7f, 2f))
                player.addMediaItem(MediaItem.fromUri("echoflow-tts://chunk/$chunkId"))
                producerFinished = true
                player.prepare()
                player.play()
            } catch (_: CancellationException) {
                throw CancellationException()
            } catch (error: Throwable) {
                fail(error.message ?: "Voice preview failed")
            }
        }
    }

    private suspend fun synthesizeWithRetry(text: String, options: TtsOptions): ByteArray {
        val source = if (options.provider == TtsProvider.OnDevice) localSource else remoteSource
        if (options.provider == TtsProvider.OnDevice) return source.synthesize(text, options)
        var lastError: IOException? = null
        repeat(MAX_NETWORK_ATTEMPTS) { attempt ->
            try {
                return source.synthesize(text, options)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: IOException) {
                lastError = error
                if (attempt + 1 < MAX_NETWORK_ATTEMPTS) delay(350L * (attempt + 1))
            }
        }
        throw lastError ?: IOException("Read aloud failed")
    }

    fun downloadLocalModel() {
        scope.launch {
            try {
                localSource.modelStore.download()
            } catch (_: CancellationException) {
                throw CancellationException()
            } catch (_: Throwable) {
                // The store publishes the actionable failure in localModelState.
            }
        }
    }

    fun stop() {
        activeToken = null
        activeMessageKey = null
        producerFinished = false
        playbackJob?.cancel()
        playbackJob = null
        player.stop()
        player.clearMediaItems()
        wavChunks.clear()
        chunkIndexes.clear()
        _state.value = ReadAloudState()
    }

    fun clearError() {
        if (_state.value.error != null) _state.value = ReadAloudState()
    }

    private fun finishPlayback() {
        activeToken = null
        activeMessageKey = null
        playbackJob = null
        player.stop()
        player.clearMediaItems()
        wavChunks.clear()
        chunkIndexes.clear()
        _state.value = ReadAloudState()
    }

    private fun fail(message: String) {
        activeToken = null
        activeMessageKey = null
        producerFinished = false
        playbackJob?.cancel()
        playbackJob = null
        player.stop()
        player.clearMediaItems()
        wavChunks.clear()
        chunkIndexes.clear()
        _state.value = ReadAloudState(error = message)
    }

    private fun ByteArray.isWave(): Boolean =
        size >= 12 && String(this, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(this, 8, 4, Charsets.US_ASCII) == "WAVE"

    override fun close() {
        activeToken = null
        activeMessageKey = null
        producerFinished = false
        playbackJob?.cancel()
        playbackJob = null
        player.stop()
        player.clearMediaItems()
        wavChunks.clear()
        chunkIndexes.clear()
        player.release()
        localSource.close()
        scope.cancel()
    }

    private companion object {
        const val LOG_TAG = "EchoFlowTTS"
        const val PREFETCH_WINDOW = 3
        const val MAX_NETWORK_ATTEMPTS = 3
    }

    private fun currentChunkLabel(): Int? =
        player.currentMediaItem?.mediaId?.let(chunkIndexes::get)

    private fun Int.label(): String = when (this) {
        Player.STATE_IDLE -> "IDLE"
        Player.STATE_BUFFERING -> "BUFFERING"
        Player.STATE_READY -> "READY"
        Player.STATE_ENDED -> "ENDED"
        else -> toString()
    }
}

/** Resolves each playlist URI to its completed WAV already held in memory. */
private class InMemoryWavDataSourceFactory(
    private val chunks: Map<String, ByteArray>,
) : DataSource.Factory {
    override fun createDataSource(): DataSource = InMemoryWavDataSource(chunks)
}

private class InMemoryWavDataSource(
    private val chunks: Map<String, ByteArray>,
) : DataSource {
    private val listeners = mutableListOf<TransferListener>()
    private var delegate: ByteArrayDataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
        delegate?.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val chunkId = dataSpec.uri.lastPathSegment
            ?: throw IOException("Missing in-memory TTS chunk id")
        val bytes = chunks[chunkId]
            ?: throw IOException("In-memory TTS chunk is no longer available")
        return ByteArrayDataSource(bytes).also { source ->
            listeners.forEach(source::addTransferListener)
            delegate = source
        }.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        delegate?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT

    override fun getUri(): Uri? = delegate?.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        delegate?.responseHeaders ?: emptyMap()

    override fun close() {
        delegate?.close()
        delegate = null
    }
}
