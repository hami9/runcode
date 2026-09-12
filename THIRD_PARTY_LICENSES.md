# Third-party licenses

runcode is MIT licensed (see [LICENSE](LICENSE)), but the APK it produces embeds several
other projects — a full CPython interpreter, OpenSSL, SQLite and the AndroidX/Compose stack.
Their licences travel with the binary, so they are reproduced here.

Versions below are the ones actually present in the `v1.0.0` APK, read out of the shipped
`.so` files rather than assumed.

## Bundled native components

| Component | Version | Licence | Full text |
|---|---|---|---|
| CPython | 3.12.12 | PSF License Agreement | [licenses/Python-PSF-2.0.txt](licenses/Python-PSF-2.0.txt) |
| OpenSSL | 3.0.18 | Apache License 2.0 | [licenses/Apache-2.0.txt](licenses/Apache-2.0.txt) |
| SQLite | 3.50.4 | Public domain | see below |
| Chaquopy | 17.0.0 | MIT | see below |

`libpython3.12.so`, `libcrypto_python.so`, `libssl_python.so` and `libsqlite3_python.so` are
built and packaged by Chaquopy. `libchaquopy_java.so` is Chaquopy's JNI bridge.

## Bundled Java/Kotlin libraries

All of the following are Apache License 2.0 — [licenses/Apache-2.0.txt](licenses/Apache-2.0.txt).

- AndroidX: `activity`, `annotation`, `arch.core`, `autofill`, `collection`, `concurrent`,
  `core`, `customview`, `emoji2`, `graphics`, `interpolator`, `lifecycle`,
  `profileinstaller`, `savedstate`, `startup`, `tracing`, `versionedparcelable`
  — Copyright The Android Open Source Project
- Jetpack Compose: `compose.animation`, `compose.foundation`, `compose.material`,
  `compose.material3`, `compose.runtime`, `compose.ui`
  — Copyright The Android Open Source Project
- Kotlin standard library and `kotlinx.coroutines` — Copyright JetBrains s.r.o. and Kotlin
  Programming Language contributors
- Guava — Copyright The Guava Authors
- JSpecify — Copyright The JSpecify Authors

## Licence texts

### Chaquopy — MIT License

Copyright (c) Chaquo Ltd.

> Permission is hereby granted, free of charge, to any person obtaining a copy of this
> software and associated documentation files (the "Software"), to deal in the Software
> without restriction, including without limitation the rights to use, copy, modify, merge,
> publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons
> to whom the Software is furnished to do so, subject to the following conditions:
>
> The above copyright notice and this permission notice shall be included in all copies or
> substantial portions of the Software.
>
> THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
> INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR
> PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE
> FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
> OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
> DEALINGS IN THE SOFTWARE.

### SQLite — public domain

> The author disclaims copyright to this source code. In place of a legal notice, here is a
> blessing:
>
> May you do good and not evil.
> May you find forgiveness for yourself and forgive others.
> May you share freely, never taking more than you give.

### CPython — PSF License Agreement

Copyright © 2001-2025 Python Software Foundation. All rights reserved.
Full text: [licenses/Python-PSF-2.0.txt](licenses/Python-PSF-2.0.txt)

### OpenSSL — Apache License 2.0

Copyright The OpenSSL Project Authors. Full text:
[licenses/Apache-2.0.txt](licenses/Apache-2.0.txt)

### Apache-2.0 components

Licensed under the Apache License, Version 2.0; you may not use these files except in
compliance with the License. A copy is in [licenses/Apache-2.0.txt](licenses/Apache-2.0.txt).
Unless required by applicable law or agreed to in writing, software distributed under the
License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
either express or implied.
