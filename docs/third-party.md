# Third-party notices

## On-device Supertonic

EchoFlow's on-device TTS implementation follows the public Supertonic v3 inference graph and the
MNN port published by `vra/supertonic-mnn`. The Kotlin/JNI integration in this repository is an
EchoFlow implementation; it does not copy the remote service into the app.

- MNN runtime: 3.6.1, Apache-2.0. The license shipped beside the vendored headers is at
  `app/src/main/cpp/third_party/licenses/MNN-LICENSE.txt`.
- MNN port provenance: `vra/supertonic-mnn` revision
  `ce5d7b25e2581293cd9d4ac0a462ac6e5e7d1cd0`.
- Model repository: `yunfengwang/supertonic-tts-mnn`, pinned revision
  `c0425ea0b5884cdb9c23a99b2373dcdb47f142fa`.

The model payload is downloaded only when the user requests offline TTS. Every file has a pinned
size and SHA-256 digest in `LocalSupertonicManifest`; incomplete downloads use `.part` files and are
never treated as installed models.
