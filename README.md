# Gallery Shuffle

Gallery Shuffle is an offline home-screen photo widget for **Android 10 and later**. Choose a local album or your entire gallery, then shuffle manually or on a schedule. **Fill** crops to the widget. **Fit** shows the full photo with a background color you choose.

## Install on Android

1. On your phone, open the [latest GitHub release](https://github.com/asurazxz/android-image-widget/releases/latest). Under **Assets**, download the `gallery-shuffle-*.apk` file.
2. Open the downloaded APK and tap **Install**. If Android prompts you, enable **Allow from this source** for the browser or file manager that opened the APK, then return to the installer.
3. Touch and hold an empty area of your home screen, open **Widgets**, and add **Gallery Shuffle**. Grant full photo access, choose your settings, and tap **Save widget**.
4. Tap the widget to open a larger preview with **Shuffle** and **Open original**.

Photos stay on your device. The widget uses local images indexed by Android, and battery settings can delay scheduled shuffles.

## Contribute

Fork the repository, clone your fork, and open it in Android Studio. Use **JDK 17**, **Android SDK Platform 36**, and **Build Tools 35.0.0**. Run these checks before opening a pull request.

```sh
./gradlew assembleDebug lintDebug testDebugUnitTest
```

On Windows, use `./gradlew.bat`. With a device or emulator connected, use `installDebug` to try the app and `connectedDebugAndroidTest` to run the Android integration tests.

[Report an issue](https://github.com/asurazxz/android-image-widget/issues) with the app version, Android version, launcher, and steps to reproduce. Use sample photos in screenshots and logs.

## License

[MIT](LICENSE)
