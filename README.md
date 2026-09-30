# Panorama

Android app that captures a full sphere. Hold the phone in portrait, turn, and tilt. The app stores one photo for each dot on the grid (3 rows x 12 columns = 36 shots), builds one equirectangular picture, and opens a spherical viewer.

The debug APK in this repo is `dist/panorama-debug.apk` (arm64 phone). Copy it to the phone and install it if you do not need to compile.

## Offline build on Intel Windows

This tree already contains:

- Gradle 6.7 (`gradle/wrapper/dists/.../gradle-6.7-bin.zip`)
- Maven jars and aars (`third_party/m2`)
- OpenCV 4.5.5 Android SDK zip (`third_party/opencv-4.5.5-android-sdk.zip`)

No download is required for those. The PC still needs a local Android SDK, JDK 11, and an NDK, because those are machine installs rather than project files.

### Tools that must already be installed

| Tool | Required piece |
| --- | --- |
| JDK | 11. Gradle 6.7 does not run on JDK 17+. In Android Studio set Gradle JDK to 11. |
| Android SDK | Platform `android-29`, build-tools `29.0.3`, CMake `3.10.2.4988404` |
| NDK | Any side-by-side NDK under `<sdk>/ndk/<version>` |
| Android Studio | Arctic Fox / 4.2 era matches this plugin. Newer Studio is fine if it can run Gradle 6.7 on JDK 11. |

SDK Manager package names, if you add them before disconnecting:

- `platforms;android-29`
- `build-tools;29.0.3`
- `cmake;3.10.2.4988404`
- one `ndk;<version>`

### Open the project

1. Copy `local.properties.example` to `local.properties`.
2. Set `sdk.dir` to your SDK, with escaped backslashes: `sdk.dir=C\:\\Users\\Alex\\AppData\\Local\\Android\\Sdk`
3. Android Studio: File > Settings > Build, Execution, Deployment > Gradle.
   - Gradle JDK: 11
   - Offline mode: on
4. File > Open this folder. Trust the Gradle wrapper when asked. Do not let Studio upgrade the plugin or the wrapper.
5. Build > Make Project, or run `gradlew.bat assembleDebug --offline` from the project folder.

The first native build unpacks OpenCV into `third_party/opencv` (gitignored, created locally). The APK is `app/build/outputs/apk/debug/app-debug.apk`. It contains `arm64-v8a` only, which is the phone ABI, not the Windows CPU.

### If a version on your SDK differs

Edit `app/build.gradle` only:

- `buildToolsVersion` must match a folder name in `<sdk>/build-tools`.
- `cmake { version "3.10.2" }` must match `<sdk>/cmake/3.10.2.4988404`. CMake 3.20+ removes the server mode this plugin needs, so do not point it at 3.22.
- If Gradle says multiple NDKs were found, add `ndkVersion "<folder name>"` next to `compileSdkVersion`, using the folder name under `<sdk>/ndk`.

## What the app does

1. `MainActivity` lists sessions stored in SQLite (`panorama.db`).
2. `CaptureActivity` shows the back camera and a dot grid. Top row is looking up, middle is level, bottom is looking down. Each column is 30 degrees.
3. Holding still on an empty dot saves a JPEG. The heading comes from the rotation sensor.
4. **Build sphere** places those JPEGs onto one 1600x800 equirectangular image using the saved azimuth and pitch. It does not run OpenCV's pairwise stitcher, which stalls on 36 photos.
5. `ViewerActivity` draws that image on the inside of a sphere. Turn the phone or drag to look around.

Photos and the finished picture live in the app's external files directory, under `session_<id>/`.

## Where to read the code

Each class below is the whole of that concern. Comments in the files name the units; the list is the map.

- `CaptureGeometry` — 12 columns, 3 pitch rows, angle wrap, and "which dot is in front of the camera".
- `sensor/HeadingTracker` — rotation vector to azimuth, pitch, and roll. Azimuth increases when you turn right.
- `camera/CameraController` — Camera2 preview and JPEG capture. Saved frames are rotated upright with EXIF. The live preview is only scaled in portrait, because an extra 90° turn laid it on its side.
- `ui/CaptureGridView` — the small dot matrix under the preview.
- `CaptureActivity` — auto-capture when the phone is steady, portrait, and aimed at an empty dot.
- `data/PanoramaDatabase` — sessions and frames. `pitch_row` plus `sector` is the unique spot.
- `data/CaptureStorage` — JPEG paths: `row_<r>_sector_<ss>.jpg` and `panorama.jpg`.
- `stitch/NativeStitcher` / `cpp/stitcher.cpp` — project each photo onto the sphere with the recorded direction.
- `ui/SpherePanoramaView` — OpenGL sphere. The bitmap's top is looking up.
- `ViewerActivity` — loads `panorama.jpg` and follows the phone from the pose where the viewer opened.

`app/src/main/cpp/CMakeLists.txt` links OpenCV `core`, `imgproc`, and `imgcodecs` into `libpanorama_stitch.so`.
