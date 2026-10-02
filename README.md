# Measure

A camera tape measure for the Galaxy S21 Ultra (any ARCore phone with Chrome).

- **In the room:** aim the circle at a surface, tap + to drop points. Separate lines or one joined path, undo, clear, running total, cm or inches.
- **From a photo:** photograph something of known size next to what you want to measure, mark both, read the length.

Open `index.html` over https in Chrome on the phone (GitHub Pages works: https://tuchus.github.io/Measure2/). Room measuring needs Google Play Services for AR.

## Android app

`android/` holds a native app with the same two ways of measuring, using Google's ARCore. GitHub builds it on every push to `main`.

Get it on the phone: https://github.com/tuchus/Measure2/releases/latest/download/Measure.apk

The first time, Android asks you to allow installing apps from your browser or Files app. Later builds install over the top.
