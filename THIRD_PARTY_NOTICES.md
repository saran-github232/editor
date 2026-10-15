# Third-party notices

Clipper uses AndroidX/Jetpack Media3, Material Components, Kotlin coroutines, Gson, OkHttp, Google Play services authorization, Vosk, and JNA. Their license notices must remain with the distributed application where required.

- AndroidX, Media3, Material Components, Gson and Vosk API: Apache License 2.0.
- Kotlin and kotlinx.coroutines: Apache License 2.0.
- OkHttp: Apache License 2.0.
- JNA: dual LGPL 2.1 or Apache License 2.0; this project uses the Apache option.
- Google Play services: Google's Android SDK/Google APIs terms apply.
- Vosk small English US 0.15 and small Hindi 0.22 models: Apache License 2.0 according to the [official model catalog](https://alphacephei.com/vosk/models). Models download separately on request.
- The instrumentation-only `speech-test.wav` fixture comes from [Vosk's Python example](https://github.com/alphacep/vosk-api/blob/master/python/example/test.wav) in the Apache-2.0 Vosk repository. It is not packaged in the app.

Review the final dependency tree and distribute the applicable complete license texts/attributions before public release. This inventory does not replace upstream license files.
