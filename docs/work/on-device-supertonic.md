# On-device Supertonic execution record

## Product slice

Add a provider choice to Read aloud without changing its playlist contract: Remote continues to
fetch independent WAV chunks from a configured endpoint; On device produces the same independent
WAV chunks with MNN/OpenCL and feeds them into the existing ordered Media3 queue.

## Acceptance checks

- provider selection persists and is visible in the settings summary;
- voice, language, speed and steps apply to both providers;
- remote keeps parallel prefetch while local inference is serialized;
- on-device model files are revision-pinned, size-checked and SHA-256-verified;
- the model can be downloaded from the UI and seeded through `run-as` for development;
- OpenCL compiled kernels persist in the app-private cache;
- unit tests and the debug APK build pass;
- an installed Pixel build synthesizes and plays a local chunk;
- a syntactically valid but silent WAV never enters the playback queue;
- a persistently failing chunk is split without losing or reordering normalized text.

## Evidence

The runtime choice came from the preceding quantization lab: MNN 3.6.1 FP16 with OpenCL was the
only tested mobile GPU path that materially beat CPU on the target Pixel. Twelve steps sounded
slightly more polished in the fixed-seed listening test, so it remains the fresh-install default;
5/8/12 and exact step selection remain available.

## Runtime architecture

The provider owns the model lifecycle; the player only sees ordered WAV items:

1. `TtsTextNormalizer` keeps letters, digits, whitespace and prosodically useful punctuation.
2. `TtsTextChunker` creates standalone utterances. Remote synthesis keeps a three-item prefetch
   window; local synthesis is serialized on one dedicated OpenCL thread.
3. `LocalSupertonicChunkSource` tokenizes the chunk, loads its selected voice style, creates a fresh
   MNN runtime and synthesizes through duration predictor, text encoder, vector estimator and
   vocoder.
4. The resulting 44.1 kHz, mono, 16-bit PCM WAV is measured before it can reach Media3.
5. Accepted WAVs are appended as independent `MediaItem`s to the live in-memory playlist. They are
   never concatenated into an answer-sized temporary file.

MNN 3.6.1's OpenCL execution on the target Pixel proved unsafe to reuse across differently shaped
Express graphs. Every local attempt therefore gets a fresh runtime, while the expensive,
device-specific compiled-kernel cache remains persistent. The cache is written once when absent;
rewriting it between utterances was also observed to destabilize subsequent executions.

## Silent-output containment

"The native call returned a WAV" is not a sufficient success condition. A real failure observed on
the Pixel returned a correctly formed 1.6 MB WAV containing 18 seconds of zeros. Media3 correctly
played that file, which sounded to the listener as though a paragraph had been skipped.

Every local result is therefore checked for:

- plausible duration relative to input length, with deliberately conservative bounds;
- PCM peak of at least 512;
- RMS of at least 64;
- at least 1% of samples above an absolute amplitude of 256.

These floors are far below the measured speech output and are intended to reject silence, tiny
corrupt files and near-zero garbage—not quiet delivery. A rejected result is regenerated up to
three times with a fresh runtime and seed. If all attempts fail, only that chunk is bisected near its
midpoint, preferring sentence or phrase punctuation and then whitespace. The two pieces return to
the front of the same work queue, so they become ordinary consecutive WAV items. Splitting can
recur; if a chunk can no longer be divided, the UI reports an error instead of silently losing text.

The split is tested to preserve the entire normalized string when its pieces are rejoined.

## Reproduced failure and A/B evidence

On 2026-09-18, this normalized English chunk repeatedly triggered the issue locally:

> The 7th edition's chapter is literally titled Aversive Control: Avoidance and Punishment 5, and
> it's the single worst-behaved arrangement in the entire subject: a coercive contingency imposed
> on someone with no alternative response and no way out.

With M5, Auto language, 12 steps and 1.05x speed:

| Execution | Bytes | Audio | Peak | RMS | Active samples | Result |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| Pixel MNN/OpenCL, full chunk, each of 3 attempts | 1,615,916 | 18.32 s | 0 | 0 | 0.0% | rejected |
| Strix Supertonic, same full chunk and settings | 1,536,044 | 17.42 s | 11,444 | 2,073 | 65.3% | accepted |
| Pixel fallback, first 89-character piece | 663,596 | 7.52 s | 16,815 | 1,533 | 45.5% | accepted |
| Pixel fallback, remaining 155-character piece | 1,019,948 | 11.56 s | 14,536 | 1,481 | 52.4% | accepted |

This rules out the source text and the Supertonic model as the primary cause. The working diagnosis
is an input-shape-dependent failure in the Android MNN/OpenCL execution path. Other chunks confirmed
that the protection must be general: some shapes failed deterministically until split, while one
280-character chunk produced silence once and valid speech on its second clean-runtime attempt.

## Diagnostics

Android logcat tag `EchoFlowTTS` records, without logging message text:

- input character and token counts;
- attempt number and synthesis time;
- WAV bytes, duration, peak, RMS and active-sample percentage;
- adaptive split sizes;
- Media3 queue insertion, transition, state, position and player errors.

This creates an inspectable `generated -> validated -> queued -> played` trail. It was what separated
the original playback hypothesis from the actual silent-generation failure: Media3 transitioned to
the correct index, but that index's PCM statistics were all zero.

## Current limitation

Signal validation proves that meaningful audio exists; it does not perform speech recognition or
prove that every source word was spoken. Content-level verification would require a separate ASR
pass and is intentionally outside the interactive read-aloud hot path.
