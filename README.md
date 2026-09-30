# Panorama

Panorama is an Android application that records a handheld spherical photograph. The operator holds the phone in portrait, turns in place, and tilts from the ceiling to the floor. The app stores one JPEG for each cell of a 3×12 capture grid, projects those frames onto an equirectangular canvas from the recorded camera direction, and displays the result from inside a sphere.

The prebuilt debug package is `dist/panorama-debug.apk`. It is an arm64-v8a APK (`applicationId` `com.panorama.app`, `minSdk` 29) and can be sideloaded without compiling.

## Contents

- [Operating model](#operating-model)
- [Architecture](#architecture)
- [Capture geometry](#capture-geometry)
- [Orientation](#orientation)
- [Camera pipeline](#camera-pipeline)
- [Persistence](#persistence)
- [Sphere assembly](#sphere-assembly)
- [Spherical viewer](#spherical-viewer)
- [Repository layout](#repository-layout)
- [Build requirements](#build-requirements)
- [Build on an offline Windows PC](#build-on-an-offline-windows-pc)
- [Build from a GitHub clone](#build-from-a-github-clone)
- [Native library](#native-library)
- [Runtime data](#runtime-data)
- [Troubleshooting](#troubleshooting)
- [Third-party components](#third-party-components)

## Operating model

1. The home screen lists capture sessions from SQLite.
2. A new session opens the back camera and a dot matrix. The top row is the ceiling, the middle row is the horizon, and the bottom row is the floor. Each column is a 30° azimuth sector.
3. When the phone is portrait, steady, and aimed at an empty cell, the app writes a JPEG. There is no hold-still checkbox; auto-capture is always on. A manual Capture button remains.
4. **Build sphere** is enabled after six frames. It composites every stored frame into `panorama.jpg` and opens the viewer.
5. The viewer looks out from the center of the sphere. Turning or tilting the phone orbits the view relative to the pose at which the viewer opened. Dragging adds an offset.

Sessions that already have a panorama open the viewer. Sessions that do not open the camera so more cells can be filled.

## Architecture

```
MainActivity
    │
    ├─ CaptureActivity
    │     ├─ CameraController          Camera2 preview + JPEG
    │     ├─ HeadingTracker            rotation vector → azimuth, pitch, roll
    │     ├─ CaptureGridView           3×12 dot matrix
    │     └─ PanoramaStitcher
    │           └─ NativeStitcher      JNI
    │                 └─ stitcher.cpp  pose projection (OpenCV imgcodecs/imgproc)
    │
    └─ ViewerActivity
          └─ SpherePanoramaView        OpenGL ES 2.0 inside-out sphere
```

Work that touches disk or the native compositor runs on the single-thread executor owned by `PanoramaApp`. The UI thread only updates views and starts that work.

The Android Gradle Plugin 4.1.3 Java bindings for OpenCV do not include `org.opencv.stitching.Stitcher`. An earlier build called `cv::Stitcher::create(PANORAMA)` through JNI. Pairwise registration of 36 frames does not finish in acceptable time on a phone, so the shipped compositor does not use that pipeline. It projects each JPEG with the azimuth and pitch that were stored when the frame was captured. OpenCV is still used for decode, scale, and JPEG encode.

## Capture geometry

Defined in `CaptureGeometry`.

| Constant | Value | Meaning |
| --- | --- | --- |
| `SECTOR_COUNT` | 12 | Columns around the horizon |
| `SECTOR_DEGREES` | 30 | Width of one column |
| `PITCH_ROWS` | 3 | Up, level, down |
| `PITCH_TARGETS` | +55°, 0°, −55° | Aim direction for each row |
| `PITCH_TOLERANCE` | 18° | How close pitch must be before a cell captures |
| `MAX_ROLL` | 40° | Phone must stay portrait in the hand |
| `HORIZONTAL_FOV` | 50° | Used only to highlight the column in front of the lens |
| `SPOT_COUNT` | 36 | `PITCH_ROWS * SECTOR_COUNT` |

Azimuth is wrapped to `[0, 360)`. `signedDelta` is the shortest turn, positive when the target is to the right. `pitchRowOf` selects the nearest target row. A cell captures only when `pitchMatches` is true for that row, so looking up cannot fill the horizon row.

The grid widget is 72dp tall. Row 0 is drawn at the top.

## Orientation

`HeadingTracker` prefers `Sensor.TYPE_ROTATION_VECTOR` (absolute, 0° = north). If that sensor is missing it uses `TYPE_GAME_ROTATION_VECTOR` and treats the direction at session start as azimuth 0. The listener reports that choice with `absolute`.

The back-camera look vector is taken from the rotation matrix:

- east = `-R[2]`
- north = `-R[5]`
- up = `-R[8]`
- azimuth = `atan2(east, north)`
- pitch = `atan2(up, hypot(east, north))`
- roll = `atan2(R[6], R[7])`

Azimuth and pitch are smoothed with a 0.25 factor. Roll is not smoothed; it is only a gate. Capture is refused while `abs(roll) > 40°`, which blocks a landscape grip without blocking a tilt toward the ceiling or floor.

Steady state is 700 ms with azimuth and pitch movement under 3.5°. Leaving a cell clears the latch so the same cell is not taken twice while the phone is still aimed at it.

## Camera pipeline

`CameraController` uses Camera2, a `TextureView`, and the back camera. The activity is fixed to portrait.

Still capture sets `CaptureRequest.JPEG_ORIENTATION` to `(sensorOrientation - displayDegrees + 360) % 360`. On a typical phone in portrait that is 90°. The JPEG is then decoded and, if EXIF still says rotate, turned upright before it is saved. Long edge is limited to 1280 px.

The live preview is a separate path. On this class of device the `TextureView` buffer is already upright while the display rotation is `ROTATION_0`. An additional 90° or 270° transform laid the preview on its side. `configureTransform` therefore only center-crops in portrait. It applies the Camera2 landscape-display rotation only when the display itself is `ROTATION_90` or `ROTATION_270`.

## Persistence

`PanoramaDatabase` is `panorama.db`, version 2, with foreign keys enabled.

`sessions`

| Column | Type | Notes |
| --- | --- | --- |
| `id` | INTEGER PK | |
| `created_at` | INTEGER | epoch millis |
| `status` | TEXT | `open`, `stitched`, or `failed` |
| `panorama_path` | TEXT | absolute path after a successful build |
| `frame_count` | INTEGER | maintained on each upsert |

`frames`

| Column | Type | Notes |
| --- | --- | --- |
| `session_id` | INTEGER | FK to `sessions(id)` ON DELETE CASCADE |
| `pitch_row` | INTEGER | 0 up, 1 level, 2 down |
| `sector` | INTEGER | 0–11 |
| `azimuth`, `pitch` | REAL | degrees at shutter time |
| `file_path` | TEXT | JPEG path |
| `captured_at` | INTEGER | epoch millis |

Unique key: `(session_id, pitch_row, sector)`. `onUpgrade` drops both tables. That is acceptable for this application; it is not a migration that preserves old captures.

Files live under `getExternalFilesDir(PICTURES)/session_<id>/`:

- `row_<pitchRow>_sector_<sector>.jpg`
- `panorama.jpg`

`CaptureStorage.deleteSessionFiles` removes that directory when a session is deleted from the list.

## Sphere assembly

`PanoramaStitcher.stitch` reads the frame list, passes paths plus azimuth and pitch into `NativeStitcher.stitch`, and on status 0 marks the session stitched.

`stitcher.cpp` builds a 1600×800 equirectangular image (2:1). For each output pixel:

- longitude maps to azimuth across the width
- latitude maps to pitch, +90° at the top row and −90° at the bottom row
- the world direction is `(sin(az) cos(pitch), cos(az) cos(pitch), sin(pitch))` in east, north, up

Each shot is a pinhole camera with a 60° horizontal field of view. Vertical field of view follows the JPEG aspect ratio. The camera basis is `forward`, `right = normalize(forward × worldUp)`, `up = normalize(right × forward)`, with a fallback when the phone is aimed near a pole. A pixel is sampled only when it projects inside the photo. Overlaps blend with weight `(1 - nx²)(1 - ny²)`, which favors the center of each frame. Source images are scaled so the long edge is at most 640 px before sampling. Uncovered pixels stay black. The JPEG is written at quality 90.

Native status codes returned to Java:

| Code | Meaning |
| --- | --- |
| 0 | Success |
| 1 | Fewer than two frames |
| -1 | Frames could not be read |
| -2 | OpenCV or C++ exception |
| -3 | `imwrite` failed |

The viewer treats the whole bitmap as the sphere. Black regions are directions that no frame covered.

## Spherical viewer

`SpherePanoramaView` is a `GLSurfaceView` (OpenGL ES 2.0). The mesh is a UV sphere, 32 stacks by 64 slices, stored in buffer objects. Face culling is off so the inside of the sphere is visible. The camera sits at the origin and looks down −Z, which is the center of the texture (`u = 0.5`).

Texture coordinates put the top of the bitmap at the top of the sphere. Wrap mode is `CLAMP_TO_EDGE` because a non-power-of-two equirectangular image is incomplete under `GL_REPEAT` on OpenGL ES 2.0; horizontal wrap is done in the fragment shader with `fract`.

`ViewerActivity` decodes the JPEG to at most 2048 px on the long edge, `ARGB_8888`. Phone look is a delta from the first sensor sample, so the picture is in front of the operator when the screen opens instead of jumping to an absolute compass heading.

## Repository layout

```
app/src/main/java/com/panorama/app/
    MainActivity.java              session list
    CaptureActivity.java           capture loop
    CaptureGeometry.java           grid math
    ViewerActivity.java            sphere screen
    PanoramaApp.java               background executor
    camera/CameraController.java
    sensor/HeadingTracker.java
    data/                          SQLite and files
    stitch/                        Java side of the compositor
    ui/                            grid, sphere, session row
app/src/main/cpp/
    CMakeLists.txt
    stitcher.cpp
app/src/main/res/                  layouts, strings, theme
third_party/m2/                    Maven repository for an offline Gradle resolve
third_party/opencv-4.5.5-android-sdk.zip
                                   OpenCV Android SDK, when present
gradle/wrapper/dists/.../gradle-6.7-bin.zip
dist/panorama-debug.apk            prebuilt debug APK
local.properties.example           SDK path template
```

`local.properties`, `third_party/opencv/` (the unpacked SDK), and `app/build/` are gitignored.

## Build requirements

| Component | Pin | Why |
| --- | --- | --- |
| Gradle | 6.7 | Wrapper shipped in `gradle/wrapper` |
| JDK | 11 | Gradle 6.7 does not start on JDK 17 or newer |
| Android Gradle Plugin | 4.1.3 | Last plugin line that runs on Gradle 6.7 with this project |
| compile / min / target SDK | 29 | |
| Build tools | 29.0.3 | Change `buildToolsVersion` if the SDK folder name differs |
| CMake | 3.10.2.4988404 | AGP 4.1 talks to CMake server mode. CMake 3.20+ removes it |
| NDK | whatever is installed | `ndkVersion` is unset so the side-by-side NDK on the machine is used |
| ABI | `arm64-v8a` | Phone ABI. The Windows or Mac CPU is only the build host |
| STL | `c++_shared` | Packaged beside `libpanorama_stitch.so` |

Do not let Android Studio upgrade the Android Gradle Plugin or the wrapper. Those upgrades leave the vendored Maven repository and the Gradle 6.7 zip behind.

SDK packages, using `sdkmanager` names:

- `platforms;android-29`
- `build-tools;29.0.3`
- `cmake;3.10.2.4988404`
- one `ndk;<version>`

If more than one NDK is installed, set `ndkVersion "<folder name>"` in `app/build.gradle` to the directory name under `<sdk>/ndk`.

## Build on an offline Windows PC

The tree vendors three things that would otherwise be downloaded:

- Gradle 6.7, at the path the wrapper hashes from `distributionUrl` (`distributionBase=PROJECT`)
- `third_party/m2`, a Maven layout of the Android Gradle Plugin 4.1.3 graph and the app dependencies (`appcompat` 1.2.0, `recyclerview` 1.1.0, and their transitives)
- `third_party/opencv-4.5.5-android-sdk.zip`, when the copy you have includes it

JDK, the Android SDK, CMake, and the NDK are machine installs. They are not in git.

1. Copy `local.properties.example` to `local.properties`.
2. Set `sdk.dir` with escaped backslashes, for example `sdk.dir=C\:\\Users\\Alex\\AppData\\Local\\Android\\Sdk`.
3. In Android Studio, set Gradle JDK to 11 and enable offline mode.
4. Open this directory. Use the wrapper. Do not download a different Gradle.
5. Build with **Make Project**, or from `cmd`:

```
gradlew.bat assembleDebug --offline
```

The first native build unpacks OpenCV into `third_party/opencv`. The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

If `build-tools` or CMake on that PC uses another directory name, change only the matching string in `app/build.gradle`. Point CMake at a 3.10.x SDK package, not at 3.22.

## Build from a GitHub clone

GitHub rejects blobs larger than 100 MB. The OpenCV Android SDK zip is 224 MB, so it is not in the GitHub history. The Gradle 6.7 zip and `third_party/m2` are.

On a machine with network, the `fetchOpenCvSdk` task downloads the archive on first build:

```
https://github.com/opencv/opencv/releases/download/4.5.5/opencv-4.5.5-android-sdk.zip
```

To prepare an offline machine, download that file into `third_party/opencv-4.5.5-android-sdk.zip` before disconnecting, then build with `--offline`.

```
gradlew.bat assembleDebug
```

Omit `--offline` for that first OpenCV download. Later builds can use `--offline`.

## Native library

`app/src/main/cpp/CMakeLists.txt` imports OpenCV from `third_party/opencv/sdk/native/jni` after the unpack task. The Android SDK ships Release static libraries only, so Debug, RelWithDebInfo, and MinSizeRel are mapped onto Release. The shared library is `panorama_stitch`. It links OpenCV `core`, `imgproc`, and `imgcodecs`, plus `log`. The JNI symbol is `Java_com_panorama_app_stitch_NativeStitcher_stitch`.

`fetchOpenCvSdk` is a dependency of `preBuild` and of the CMake tasks, so a clean tree unpacks (or downloads) the SDK before `externalNativeBuild`.

## Runtime data

The database and pictures are private to the app. Uninstalling the app deletes them. Replacing the APK with the same application id and signing key keeps them. A database version change drops sessions.

Build sphere needs at least six frames (`MIN_BUILD` in `CaptureActivity`). Filling all 36 cells is what covers the sphere. Gaps remain black in the viewer.

## Troubleshooting

| Symptom | Cause | What to change |
| --- | --- | --- |
| Gradle fails immediately on a new JDK | Gradle 6.7 cannot parse bytecode from JDK 17+ | Gradle JDK = 11 |
| `SDK location not found` | No `local.properties` | Copy the example and set `sdk.dir` |
| Build-tools revision not installed | `buildToolsVersion` does not match a folder under `<sdk>/build-tools` | Set that string to an installed revision |
| CMake server error, or NPE in `ServerProtocolV1` | CMake 3.20+ | Install `cmake;3.10.2.4988404` and keep `version "3.10.2"` |
| NDK location is ambiguous | Several side-by-side NDKs | Set `ndkVersion` to one folder name |
| `Could not resolve com.android.tools.build:gradle:4.1.3` while offline | Offline mode is off and the machine is rewriting versions, or `third_party/m2` was not checked out | Build with `--offline` from a full checkout |
| OpenCV zip missing | GitHub clone | Download the 4.5.5 Android SDK zip into `third_party/` (see above) |
| Preview is on its side | A 90° `TextureView` transform was applied on top of an already upright buffer | `CameraController.configureTransform` must not post-rotate in portrait |
| Build sphere never returns | The old OpenCV `Stitcher` pairwise path | Current `stitcher.cpp` projects from stored headings and returns in seconds |

## Third-party components

- AndroidX AppCompat 1.2.0 and RecyclerView 1.1.0
- Android Gradle Plugin 4.1.3 and its Maven dependencies, vendored under `third_party/m2`
- OpenCV 4.5.5 Android SDK, [Apache License 2.0](https://github.com/opencv/opencv/blob/4.5.5/LICENSE)
- Gradle 6.7 binary distribution

OpenCV's static libraries are linked into `libpanorama_stitch.so`. The unpacked SDK tree is produced locally and is not committed.
