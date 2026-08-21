# Android And iOS Managed Runtime Notices

This inventory covers the managed runtime families resolved by the current
Android and iOS Gradle inputs. Exact direct versions are recorded in
`gradle/libs.versions.toml`, which is included with release metadata. Native
players and codec libraries remain covered by their platform manifests.

| Runtime family | Platforms | Published license | Upstream source |
| --- | --- | --- | --- |
| Kotlin and KotlinX | Android, iOS | Apache-2.0 | https://github.com/JetBrains/kotlin and https://github.com/Kotlin |
| Compose Multiplatform and Skiko | Android, iOS | Apache-2.0; bundled Skia retains BSD-3-Clause terms | https://github.com/JetBrains/compose-multiplatform and https://github.com/JetBrains/skiko |
| AndroidX, JetBrains AndroidX, and Media3 | Android, iOS where resolved | Apache-2.0 | https://android.googlesource.com/platform/frameworks/support and https://github.com/JetBrains/compose-multiplatform-core |
| Ktor | Android, iOS | Apache-2.0 | https://github.com/ktorio/ktor |
| Koin | Android, iOS | Apache-2.0 | https://github.com/InsertKoinIO/koin |
| Coil | Android, iOS | Apache-2.0 | https://github.com/coil-kt/coil |
| Touchlab Kermit and Stately | Android, iOS | Apache-2.0 | https://github.com/touchlab/Kermit and https://github.com/touchlab/Stately |
| OkHttp and Okio | Android, iOS where resolved | Apache-2.0 | https://github.com/square/okhttp and https://github.com/square/okio |
| Android desugared JDK libraries | Android | GPL-2.0 with Classpath Exception | https://github.com/google/desugar_jdk_libs |
| JetBrains annotations, JSpecify, and Guava listenable-future marker | Android | Apache-2.0 | https://github.com/JetBrains/java-annotations, https://github.com/jspecify/jspecify, and https://github.com/google/guava |
| AndroidX bundled SQLite | Android, iOS | Apache-2.0 wrapper; SQLite is public domain | https://android.googlesource.com/platform/frameworks/support and https://sqlite.org/copyright.html |

These components keep their upstream copyright, license, and notice terms. A
new runtime family or changed license must be reviewed before the relevant
platform readiness entry is accepted.
