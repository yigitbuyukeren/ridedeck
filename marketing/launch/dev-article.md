# Designing a motorcycle dashboard for Android: what belongs on the home screen

> **Editorial note:** DEV’s current AI guidance allows disclosed, fact-checked assistance but says AI-generated content should not primarily promote a business, program or the author. Treat this as a draft to rewrite in your own voice, verify against the build, and disclose according to the live rules.

**Suggested tags:** `#showdev` `#android` `#ux` `#kotlin`

**Cover:** `visuals/dev-cover-1000x420.png`

**Cover alt text:** A dark editorial cover with the title “What belongs on a motorcycle dashboard?” and a route-planning screenshot from the RideDeck v0.7.0 Android emulator. The caption labels the capture as emulator footage with simulated GPS and a Turkish UI, with English available.

---

Most Android apps are designed for a hand, a pocket and a lot of attention. A motorcycle dashboard has the opposite problem: the phone is mounted, the screen is landscape, the rider is wearing gloves, and the app has to communicate in a glance.

RideDeck is my attempt to build that screen. It is a free, open-source Android app for motorcycles: GPS speed, an OpenStreetMap-based map with motorcycle routing and voice guidance, media controls, phone controls and local trip history. It is inspired by the glanceable layout idea of CarPlay and Android Auto, but it is not an integration with either system. It runs on the phone itself.

This is a build note about the decisions behind the current v0.7.0 screen, not a claim that the app is finished. Real-device and real-riding validation are still open, SOS/crash detection is experimental, and the screenshots in this article come from an Android emulator with simulated GPS.

## Start with three jobs, not a feature list

The home screen has room for three things before it becomes a settings menu:

1. **How fast am I going?**
2. **What is the next turn?**
3. **What is playing?**

Everything else is secondary. The speed card gets the visual weight. The map and next-turn card sit on the right. Media controls sit below the map. The left dock switches between navigation, music, phone and settings; it is not a second feature grid.

That hierarchy also explains what is not on the home screen: account prompts, onboarding cards, social features, weather alerts stacked on top of navigation, and a permanent row of settings toggles. A dashboard is a place to read state, not a place to configure it.

## Unknown is a valid speed

The first rule I wanted to enforce was simple: **do not invent a zero**.

A GPS fix can disappear in a tunnel, under a bridge, in a car park or after the phone loses permission. Showing `0 km/h` in that moment is worse than showing nothing: it looks like a real measurement and can mislead the rider.

RideDeck separates two values:

- `speedKmh`: the raw GPS measurement used by recording and safety logic;
- `displayKmh`: a filtered value for the gauge, or `null` when there is no fresh measurement.

The display filter smooths normal GPS noise and briefly holds the last value through a short gap. After that it returns `null`, and the UI shows `--` rather than a fake stop. The filter never extrapolates a speed and never lets a suspect jump refresh the last usable value.

The exact hold windows are deliberately small constants in `SpeedFilter` (three seconds while moving, five seconds at standstill). The important design choice is not the number; it is that “unknown” is a first-class state in the UI.

There is a second distinction behind the gauge. The displayed speed is filtered, but the crash detector, trip recorder and speed-limit warning use the raw GPS speed. A smooth gauge should not delay a safety decision. Keeping the two paths separate made the rest of the app easier to reason about.

## Lock the setup, not the ride

A phone dashboard has to be set up before the ride. Searching for a destination, opening settings or typing on a keypad are parked activities. RideDeck enforces that with a small motion state machine (from `data/Motion.kt`):

```kotlin
fun inputLocked(previous: Boolean, motion: Motion): Boolean = when (motion) {
    Motion.MOVING -> true
    Motion.STOPPED -> false
    Motion.UNKNOWN -> previous
}
```

`MotionTracker` does not switch on a single noisy fix. Once it has a known state, it needs two consecutive fixes at or above 5 km/h to decide the bike is moving, and two below the threshold to decide it has stopped. Coming out of `UNKNOWN`, the first valid fix sets the state; `inputLocked` keeps the previous lock while no fresh fix exists. A tunnel does not silently unlock the keyboard.

The 5 km/h threshold is a product decision, not a claim that every rider or phone will behave the same way. It is also why the lock state is based on filtered display speed while the safety path keeps the raw measurement. The two values have different jobs.

## One GPS stream, several consumers

The screen, an active ride's foreground service and the optional overlay all need location. A naive implementation opens a location listener in each place, then has to decide which one owns the GPS when a screen is destroyed or a service stops.

RideDeck uses one process-owned `RideTracking` object with a reference-counted `LocationArbiter`. The screen, service and overlay ask for the shared stream; when one owner leaves, the others keep it alive. The same architecture feeds the map cursor, speed card, navigation controller, trip recorder and crash detector.

That centralisation is also a testability decision. The pure logic classes do not need Android's `LocationManager`: `SpeedFilter`, `MotionTracker`, `CrashDetector`, route matching and trip math take their inputs and clocks from outside. The Android adapters are thin.

## Let navigation outlive the screen

A motorcycle dashboard is often not the foreground app. The phone may be locked, the screen may be off, or the rider may switch to another app. If navigation only lives inside the Compose screen, guidance stops when the screen does.

RideDeck keeps the navigation session in `RideDeckApplication`, not in the screen. `RideNavigation` owns the `NavigationController`, voice output, media state and route persistence. The Activity can be recreated without dropping an active route. The ride service can continue with the screen off, and the notification can end the ride.

The trade-off is more lifecycle code and a stricter ownership model. Every route/search request carries a generation so a stale reply cannot replace a newer route. Cancelling a request also cancels the network call rather than merely ignoring the result. That is the kind of plumbing that is easy to skip in a demo and hard to retrofit later.

## Language is runtime state

v0.7.0 added runtime language switching. On Android 13+, the app-language menu can switch between English and Turkish without restarting the process. The TTS voice and route instructions follow the new language, and unsupported device languages fall back to English. A live language change also has to interact safely with an active SOS announcement and an in-flight route request.

This was mostly a state-ownership problem, not a translation problem. The app language is not a string resource lookup that happens once at startup; it is an input that can change while a screen, a service and a TTS engine are alive. The emulator test changed `en-US` to `tr-TR`, kept the same process ID, and verified that the interface changed. I have not listened to the real TTS output in both languages on a physical device, so that remains an explicit limitation rather than a claim.

## What I left out

Some omissions are deliberate:

- **A vehicle selector.** RideDeck is motorcycle-only today. A selector would imply tuned car and bicycle behavior that does not exist. Other vehicle types are a long-term goal, not a shipped feature.
- **A voice assistant.** An earlier prototype tried wake-word listening. Android's `SpeechRecognizer` was not reliable for continuous listening, so the feature was removed rather than shown broken.
- **Offline routing.** Routing, search and weather require internet. Previously downloaded map areas are retained, but new offline downloads are paused pending provider-compliance checks. There is no offline route calculation or recalculation.
- **Live traffic and radar alerts.** Those are not implemented. “Navigation” here means motorcycle routing and guidance, not a traffic product.
- **Accounts and a RideDeck backend.** Trips and settings stay on the device. Online features still contact third-party map, search, weather and fuel providers; “no RideDeck account” is not the same as “no network requests”.

The same restraint applies to the SOS/crash feature. It is optional, default-off and experimental. It attempts an SMS after an impact-and-inactivity trigger and a countdown. It does not guarantee delivery, has not been field-tuned, and is not a substitute for emergency services or safety equipment. It is in the app, but it is not the headline.

## Testing the parts that can lie to you

The current v0.7.0 evidence is:

- 848/848 debug and 848/848 release JVM tests, with zero failures or skips;
- 209 instrumentation tests: 208 passed and one skipped in the combined run, and the skipped permission test passed separately;
- zero lint errors (warnings were not suppressed);
- a signed release APK and static 16 KB ELF/ZIP alignment checks.

Those numbers do not prove real-world behavior. The instrumentation suite runs on an x86_64 API 35 emulator. Physical devices, real GPS and riding, real SMS/operator delivery, lock-screen/Doze/OEM behavior, real calls and Bluetooth intercom, thermal/battery behavior and the physical 16 KB runtime are still unverified. The public 1.0 decision remains **NO-GO**.

The screenshots in this article are emulator captures with simulated GPS. The interface is shown in Turkish; English is available in the app. They are useful for explaining layout and state, not for claiming a road test.

## What I would like feedback on

The part I am least sure about is the information hierarchy. Speed is the largest element, the next turn sits beside it, and music is below. Is that the right order for a mounted phone? What would you remove first? And if you have built glanceable Android UI, how do you test readability across screen sizes without pretending an emulator run is a riding test?

Source: https://github.com/yigitbuyukeren/ridedeck · v0.7.0 release and APK: https://github.com/yigitbuyukeren/ridedeck/releases/tag/v0.7.0

If you try it, please do the setup parked, keep the phone mounted securely, and treat the current build as a pre-1.0 project with explicit open validation gates.
