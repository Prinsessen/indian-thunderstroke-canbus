# Build setup — Windows

What has to be in place to build this project from scratch on a new Windows
machine, and the three traps that cost an afternoon the first time.

Day-to-day file transfer, flashing and `adb` live in
**[WORKFLOW.md](WORKFLOW.md)**. This is the one-time part.

> Written in English to match the rest of the folder; the original was Danish.

---

## What you need

**Android Studio**, for two things reused on the command line:

| | |
|---|---|
| Android SDK | `%LOCALAPPDATA%\Android\Sdk` |
| Bundled JDK ("JBR") | `C:\Program Files\Android\Android Studio\jbr` — **not used**, see below |

**A JDK 21.** Not optional, and not the one Android Studio ships. See below.

**Node.js**, only if you want Claude Code on the machine:
`npm install -g @anthropic-ai/claude-code`, then `claude` in the project folder.

### SDK components

`app/build.gradle.kts` asks for `compileSdk 35`, `targetSdk 35`, `minSdk 31`,
so the SDK needs `platforms;android-35` and build-tools 34.0.0 or newer.

The Android Gradle Plugin downloads both on first build if the licence is
accepted and there is network — which is what happened here: the machine had
only `android-37.0` and build-tools `36.0.0`, and AGP fetched the rest during
the first `assembleDebug`. To do it by hand instead:

```powershell
sdkmanager "platforms;android-35" "build-tools;34.0.0"
sdkmanager --licenses
```

---

## Trap 1 — Android Studio's JDK is too new

The bundled JBR is **JDK 25**. Gradle 8.9 and Kotlin 2.0.21 cannot parse its
version string and fail before compiling anything:

```
* What went wrong:
25.0.3
java.lang.IllegalArgumentException: 25.0.3
    at org.jetbrains.kotlin.com.intellij.util.lang.JavaVersion.parse(...)
```

The error names a version and nothing else, which makes it look like a
corrupted install rather than a version that is simply ahead of the toolchain.

Install a JDK 21 alongside, for Gradle only:

```powershell
mkdir "$env:LOCALAPPDATA\claude-jdks"
cd "$env:LOCALAPPDATA\claude-jdks"
curl -L -o jdk21.zip "https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jdk/hotspot/normal/eclipse"
Expand-Archive -Path jdk21.zip -DestinationPath . -Force
```

It unpacks to something like `jdk-21.0.12.1+1`. This replaces nothing: the
project still targets Java 17 bytecode, which JDK 21 compiles happily.

**Building from the IDE instead?** Set **File → Project Structure → SDK
Location → Gradle JDK** to a 17 or 21, and Run works without any of this.

---

## Trap 2 — `local.properties` and backslashes

The file is machine-specific and gitignored. It points Gradle at the SDK:

```properties
sdk.dir=C:/Users/YourName/AppData/Local/Android/Sdk
```

**Forward slashes.** Java's `.properties` parser reads `\U` as the start of a
unicode escape, so a Windows path with backslashes produces:

```
java.io.IOException: The filename, directory name, or volume label syntax is incorrect
```

Nothing in that message points at the file or the escape, which is what makes
it expensive. Forward slashes always work on Windows here.

---

## Trap 3 — the Gradle wrapper was missing (now fixed)

The repository had `gradle-wrapper.properties` but none of the three files that
make `./gradlew` runnable, so a fresh clone could not build at all. They are
committed now, taken from the `v8.9.0` tag of Gradle's own repository, matching
the 8.9 distribution the properties file already pinned:

| File | SHA-256 |
|---|---|
| `gradlew` | `9cbbb4d68ff7fb5211c4d58f598ac9d8664c05fdcd1e5f59b7f2c3ac1ee00af0` |
| `gradlew.bat` | `0f3ed8f03b50934cb8c48b15a470d5c20a30a5385825e48b55bcc8ea3d8f8e18` |
| `gradle/wrapper/gradle-wrapper.jar` | `498495120a03b9a6ab5d155f5de3c8f0d986a449153702fb80fc80e134484f17` |

The checksums are here so the JAR can be checked rather than trusted. A wrapper
JAR is a binary that every build executes, and "it came from the internet once"
is not a provenance anyone can verify later.

```powershell
Get-FileHash gradle\wrapper\gradle-wrapper.jar -Algorithm SHA256
```

---

## Building

**PowerShell:**

```powershell
$env:JAVA_HOME = "C:\Users\YourName\AppData\Local\claude-jdks\jdk-21.0.12.1+1"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
cd C:\SpringfieldAndroid\indian-canbus-app
.\gradlew.bat assembleDebug
```

**Git Bash / WSL:**

```bash
export JAVA_HOME="/c/Users/YourName/AppData/Local/claude-jdks/jdk-21.0.12.1+1"
export PATH="$JAVA_HOME/bin:$PATH"
cd "C:/SpringfieldAndroid/indian-canbus-app"
./gradlew assembleDebug
```

The APK lands at `app\build\outputs\apk\debug\app-debug.apk`.

Installing it: **[WORKFLOW.md](WORKFLOW.md)**.

---

## If a build fails

The compiler is the authority — there is no Android toolchain on the openHAB
server, so nothing is verified until it runs here. Paste the errors back; the
static pass that runs server-side catches resources, arity and duplicate
declarations, but not types, nullability or overload resolution.
