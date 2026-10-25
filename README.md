# F1 Circle

An Android take on the F1 "circle of doom": every car drawn on a single circle that represents one
lap, placed by **time** rather than distance, so the spacing between dots is the gap between cars.
Tap a driver to see where they would come back out if they pitted right now.

Works live during a session and as a replay of past races.

<p>
  <img src="docs/replay.png" width="300" alt="Replay of the 2026 Italian GP with Russell selected: lapped cars greyed out and the pit-rejoin window shown">
  <img src="docs/live.png" width="300" alt="Live view of the 2026 US GP with Piastri selected and the rejoin window drawn behind Bottas">
</p>

## Features

- **Live mode** follows whatever session is running, straight from F1's live timing feed. No login
  needed. Pause, step back a second at a time to line the circle up with your stream, or jump back
  to live.
- **Replay** any race from 2023 on, with a timeline, ±30 s skips and 1×–64× speed.
- **Pit window**: select a driver to see the band where they would rejoin, the position they would
  drop to, and the cars either side. Pit loss is measured from the race's own clean stops (or the
  typical pit-lane time until there are enough), reduced under a safety car or VSC, and can be
  nudged by hand.
- **Gaps** on every label, relative to the leader or to the selected driver. Cars a lap or more
  away from the selected driver turn grey.
- Track status (green, SC, VSC, red flag, chequered), lap counter, a reference lap time, and the
  sector lines learned from the session itself.
- Labels can be hidden per driver; the choice is remembered across races.

## How it works

The circle is a lap measured in time: an angle is a share of the reference lap (the average of the
leader's last five green-flag laps). That keeps one second the same size everywhere on the circle,
so gaps and the pit-loss band read correctly whatever part of the track a car is on.

Cars are positioned from timing data, not GPS:

1. Every sector-line crossing is an exact anchor for that car. Between anchors it moves at its
   recent pace and never passes a timing line it hasn't been seen crossing.
2. A car's gap to the leader means it is where the leader was that many seconds ago, so everyone
   else is placed along the leader's interpolated path. This is far more precise than
   extrapolating each car on its own.

Checked against real sector crossings (where the model had each car just before the crossing came
in, versus where it actually was):

| Data | Median error | 90% within |
| --- | --- | --- |
| 2026 Italian GP replay (SC, red flag, VSC) | 0.06 s | 0.17 s |
| 2026 US GP live feed | 0.05 s | 0.14 s |

Using each car's own sector times alone gives a 0.17 s median and 0.50 s at the 90th percentile.

## Data sources

| Mode | Source | Notes |
| --- | --- | --- |
| Live | `livetiming.formula1.com/signalrcore` (SignalR Core) | Anonymous access covers everything the app uses. An F1 TV login would only add car GPS positions, which aren't needed. |
| Replay | [OpenF1](https://openf1.org) historical API | Free, 2023 onwards. OpenF1 blocks all free requests while a live session is running, so race lists and opened races are cached on the device. |

F1's own archive of past sessions (`livetiming.formula1.com/static`) now requires authentication,
so it isn't used.

## Building

Requirements: JDK 17+, Android SDK with platform 37, and a device or emulator on Android 8.0+.

```bash
./gradlew installDebug        # build and install on a connected device
./gradlew testDebugUnitTest   # model, parser and live-feed tests
```

The model tests run against the 2026 Italian GP data in `app/src/test/resources`. The live-feed
accuracy test needs a recorded feed (one SignalR message per line, prefixed with the receive time in
ms) and is skipped without one:

```bash
F1_LIVE_RECORDING=/path/to/recording.jsonl ./gradlew testDebugUnitTest
```

## Project layout

```
app/src/main/java/com/f1circle/app/
  data/
    LiveTimingClient.kt     SignalR connection to F1 live timing, with reconnects
    LiveSessionBuilder.kt   folds the live feed into SessionData
    OpenF1Client.kt         downloads and caches historical sessions
    OpenF1Parser.kt         OpenF1 JSON → SessionData
    Models.kt               shared data types
  model/
    CircleModel.kt          places cars, reference lap, sector lines, pit loss and rejoin window
  ui/
    CircleCanvas.kt         draws the circle, cars and labels; tap handling
    LiveScreen.kt           live view with pause and delay
    ReplayScreen.kt         replay view, pit panel, label menu
    SessionPicker.kt        race list and live entry point
```

## Disclaimer

Unofficial and not associated with Formula 1 or OpenF1. F1, FORMULA 1 and related marks are
trademarks of Formula One Licensing B.V. Timing data belongs to its respective owners.
