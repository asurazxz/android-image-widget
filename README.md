<h1 align="center">Gallery Shuffle</h1>

<p align="center">
  <strong>An offline Android home-screen widget that shuffles photos from your local gallery.</strong>
</p>

<p align="center">
  <a href="#compatibility"><img src="https://img.shields.io/badge/Android-10%2B-22704f" alt="Android 10 and later"></a>
  <a href="#quick-start"><img src="https://img.shields.io/badge/JDK-17-315c8d" alt="Build with JDK 17"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-MIT-7655a6" alt="MIT license"></a>
</p>

<p align="center">
  <a href="#quick-start">Quick start</a> ·
  <a href="#add-a-widget">Add a widget</a> ·
  <a href="#privacy-and-permissions">Privacy</a> ·
  <a href="#contributing-and-support">Contributing</a>
</p>

## Features

- Uses locally available, MediaStore-indexed still images. Choose the entire gallery or one or more local folders/albums for each widget.
- Keeps each widget's source, current photo, shuffle history, display mode, viewer preference, and schedule independent.
- The home-screen widget shows the photo by itself. Tap the photo to open its preview.
- The launcher picker shows a bundled sample photo before setup.
- **Appearance** follows the system’s light or dark mode. Use wallpaper colors on Android 12 and later, or choose a custom color for app controls and widget recovery. Android 10–11 use Gallery Shuffle green by default. Displayed photos retain their original colors.
- Choose **Fill** to crop to the widget or **Fit** to show the whole photo with a background color of your choice. Larger widgets use higher-resolution previews within Android's widget limits.
- Choose manual-only rotation, 15 minutes, 30 minutes, every 24 hours, or a custom interval of 1–168 whole hours.
- Works offline. No account, ads, analytics, telemetry, or photo uploads.

Albums mean local MediaStore folder groupings, not cloud albums, favorites, or people collections. Cloud-only photos and videos are outside the app's scope.

## Compatibility

Emulator testing covers Android 10, 13, and 16 (API 29, 33, and 36), including picker previews and minimum-size widget recovery at 200% text. Android 16 coverage includes TalkBack focus and activation and both navigation modes. Nexus Launcher on Android 13 reported a 2 × 3 tile for the declared 2 × 2 target. Physical ColorOS and a second launcher remain open.

## Quick start

The first public APK is pending. You can build and install a debug APK on an Android 10 (API 29) or newer device or emulator.

Install JDK 17. Use Android Studio's SDK Manager to install Android SDK Platform 36 and Build Tools 35.0.0. Set `ANDROID_HOME` to your SDK location or open the project in Android Studio to configure it. Run these commands from the repository root.

```sh
./gradlew assembleDebug
./gradlew installDebug
```

On Windows, use `.\gradlew.bat` with the same tasks. Connect an Android device or start an emulator before running `installDebug`. The build writes the APK to `app/build/outputs/apk/debug/app-debug.apk` and uses the debug application ID `dev.localgallery.widget.debug`.

## Add a widget

1. Long-press an empty area of the home screen and open the launcher's **Widgets** picker.
2. Find Gallery Shuffle and place a widget.
3. Grant full photo access, choose the entire gallery or search for specific local albums, and set the display and rotation preferences. Tap **Save widget** when ready.
4. Tap the photo to open its preview. Choose **Shuffle** to change the widget and preview photo while keeping the preview open, or choose **Open original** to open the file in the preferred or system-selected image viewer. Press Back to return home.

Launcher menus and widget sizing vary by device. If the widget picker does not list the app, try opening the app once, then check the launcher's widget picker again.

## Change the appearance

Open Gallery Shuffle and select **Appearance**. Choose **Wallpaper colors** on Android 12 and later or **Gallery Shuffle colors** on earlier versions to use the default palette. To choose your own accent, select **Custom color**, use a swatch, adjust the sliders, or enter a six-digit hex value, then select **Apply**. **Cancel** or Back discards draft changes. Returning to the default palette keeps your last custom color available.

## Privacy and permissions

Gallery Shuffle reads image entries exposed through Android MediaStore and requires full image access to choose from the gallery. Original files stay untouched. The release APK requests version-appropriate photo-read permissions and has limited package visibility for compatible image viewers. WorkManager adds boot, wake lock, and network-state permissions for its infrastructure. The APK has no `INTERNET`, video, location, or all-files access permission. The app works offline without accounts, photo uploads, ads, or telemetry.

Android launchers can temporarily retain a rendered widget snapshot until the app next observes a permission change and updates it. Once the app observes revoked access, it fails closed and replaces the photo with a recovery state; instant remote-view erasure at the exact moment of revocation is not promised.

## FAQ and limitations

**Why does it need full photo access?** The widget selects from the local image library, so it needs permission to enumerate and display eligible images. Selected-photo-only permission is not supported as a widget source.

**Why are some photos missing?** The app sees local still images that Android has indexed in MediaStore and made accessible. Cloud-only items, unindexed files, private app folders, and videos are not included.

**Why did an automatic change happen late?** Rotation uses Android's battery-friendly, inexact background work. Doze, battery saver, app standby, force-stop, or manufacturer-specific restrictions may delay it. “Daily” is a repeating 24-hour interval, not a fixed time of day. After force-stopping the app, open it again to let scheduling reconcile.

**Why does Open original show a chooser?** Android may ask which compatible viewer to use when there is no default. You can also choose a preferred viewer in the widget settings.

## Contributing and support

Run `./gradlew assembleDebug lintDebug testDebugUnitTest` for local checks and `./gradlew connectedDebugAndroidTest` with a connected emulator or device. For bug reports, include the app version, Android version, launcher, steps to reproduce, and relevant redacted logs. Use synthetic photos in screenshots and test reports.

## License

The project is licensed under the [MIT License](LICENSE). Material Components for Android is distributed under Apache License 2.0. The instrumentation-only HEIC fixture also has an [Apache 2.0 license and provenance record](app/src/androidTest/assets/README.md).
