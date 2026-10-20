# Third-party notices

AirKeyboard's own code is under the MIT license ([LICENSE](LICENSE)). The release files also
contain the third-party software below, each under its own license.

## Android app (`AirKeyboard-<version>.apk`)

All of these are under the Apache License 2.0: [licenses/Apache-2.0.txt](licenses/Apache-2.0.txt).

| Component | Version | Copyright |
|---|---|---|
| AndroidX libraries: core 1.12.0, appcompat 1.6.1, constraintlayout 2.1.4, lifecycle 2.7.0, and the other `androidx` libraries they depend on | see left | The Android Open Source Project |
| Material Components for Android | 1.11.0 | The Android Open Source Project |
| Kotlin standard library | 1.9.22 | JetBrains s.r.o. and Kotlin Programming Language contributors |
| kotlinx.coroutines | 1.7.3 | JetBrains s.r.o. and contributors |
| JetBrains Java Annotations | 23.0.0 | JetBrains s.r.o. |
| ZXing core | 3.5.0 | ZXing authors |
| ZXing Android Embedded | 4.3.0 | ZXing authors, Journey Mobile |
| Timber | 5.0.1 | Jake Wharton |
| Guava ListenableFuture | 1.0 | The Guava Authors |
| Error Prone annotations | 2.15.0 | The Error Prone Authors |
| Material Icons (some of the app's vector icons, e.g. the microphone) | | Google LLC |

## Windows app (`AirKeyboard.exe`)

| Component | Version | License |
|---|---|---|
| QRCoder | 1.4.3 | MIT, Copyright (c) Raffael Herrmann (www.code-bude.net) |
| .NET runtime and Windows Desktop runtime (Windows Forms, WPF) | 8.0 | MIT, Copyright (c) .NET Foundation and Contributors |

The exact .NET patch version is in `AirKeyboard.exe`'s file properties. These parts include
third-party components of their own, listed in (copied unchanged; identical for .NET 8.0.31 and
8.0.32):

- .NET runtime: [licenses/dotnet-runtime-THIRD-PARTY-NOTICES.txt](licenses/dotnet-runtime-THIRD-PARTY-NOTICES.txt)
- Windows Forms: [licenses/dotnet-winforms-THIRD-PARTY-NOTICES.txt](licenses/dotnet-winforms-THIRD-PARTY-NOTICES.txt)
- WPF: [licenses/dotnet-wpf-THIRD-PARTY-NOTICES.txt](licenses/dotnet-wpf-THIRD-PARTY-NOTICES.txt)

Each release also carries all of these in one file, `AirKeyboard-licenses.txt`.

### MIT license (QRCoder)

```
Copyright (c) Raffael Herrmann (www.code-bude.net)

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

### MIT license (.NET)

```
The MIT License (MIT)

Copyright (c) .NET Foundation and Contributors

All rights reserved.

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
