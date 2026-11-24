# Webtoon Reader (Android 4.1+)

Offline vertical-scroll reader for local comics. No dependencies, no network, no ads.

Reads: `.cbz`, `.zip`, and plain folders of images (jpg/png/webp/gif/bmp).
Works with Mihon/Tachiyomi-style local libraries: `Series/Chapter.cbz` or `Series/Chapter/001.jpg`.

Controls: scroll; tap top/bottom quarter = page up/down; tap middle = toggle nav bar;
volume keys = page scroll; "Next" button at end of chapter; position remembered per chapter.

## Get the APK without installing anything (GitHub)
1. Create a free GitHub repo and upload this whole folder (keep `.github/`).
2. Open the **Actions** tab -> "Build APK" -> run (or it runs on push).
3. Download the `webtoon-reader-apk` artifact -> unzip -> `app-debug.apk`.
4. Copy to the phone, allow "Unknown sources", install.

## Or build locally
Android Studio (open this folder, Build > Build APK) or: `gradle assembleDebug` with JDK 11 + Android SDK 33.
