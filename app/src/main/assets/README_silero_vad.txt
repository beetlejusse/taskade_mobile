Silero VAD model (barge-in detection)
=====================================

Place the Silero VAD v5 ONNX model in this folder as:

    silero_vad_v5.onnx

This is the SAME model the web client ships at:
    <repo>/client/public/silero_vad_v5.onnx

How it is used
--------------
- voice/vad/SileroVadModel.kt loads this asset via ONNX Runtime Mobile and runs it
  per audio frame to produce a speech probability, using the web client's tuned
  thresholds (see voice/vad/VadDetector.kt). It exists ONLY to detect barge-in;
  end-of-turn is decided server-side by Deepgram (see PRD §5.2).

Graceful fallback
-----------------
- If this file is absent (or ONNX Runtime fails to initialize), the app
  automatically falls back to an RMS energy VAD (voice/vad/EnergyVadModel.kt), so
  barge-in still works with coarser tuning. Bundling the real model is strongly
  recommended for production quality on-device VAD.
