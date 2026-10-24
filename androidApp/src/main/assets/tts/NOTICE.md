# Speech (Piper through sherpa-onnx)

Fetched by `scripts/fetch-tts.sh`, not stored in git.

- Library: sherpa-onnx 1.13.8 (Apache-2.0; not 2.x, which has no built-in espeak phonemizer), https://github.com/k2-fsa/sherpa-onnx
- Voices: Piper medium Russian voices (fp32), as packed by sherpa-onnx (https://github.com/rhasspy/piper-voices):
  - denis, dmitri: dataset licence CC0.
  - ruslan: dataset licence CC BY-NC-SA 4.0 (non-commercial, share-alike).
  - irina: dataset licence "unknown" in its model card (the source is the RHVoice voice).
- Phoneme data: espeak-ng (GPL-3.0). It is bundled for the Piper voices only.

Personal, non-commercial use only. Check the licences above before publishing a built APK.
