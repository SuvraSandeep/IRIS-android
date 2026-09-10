# IRIS 8.14.0 — Command Deck refinement and phone questions

Based on b5721ba1fb8ca6188a4b6f4978f2655851df24d4 (8.13.2). Preserves the existing Command Deck and customisation settings.

## Added
- A searchable shared phone-fact catalog used by the UI and voice/text answers.
- Battery health status, voltage and technology; RAM/storage totals and usage; display resolution, refresh rate, HDR, density and brightness setting; system build, security patch, CPU architecture and available logical cores; audio volume, ringer/DND/media, lock/location settings, sensor inventory and usage.
- Network interface, MTU, private DNS, default-connection addresses and device traffic since boot.
- Tap for example questions; expandable detail sources; long-press to copy values.
- A faint grid and optional animated scan sweep. Existing orb animations now stop when detached or hidden.

## Corrected
- No name-based Bluetooth identity guessing. GATT and audio endpoints are observations; endpoint availability does not prove active routing or playback.
- No VPN tunnel address labelled as Wi-Fi/cellular IP.
- Actual service uptime, rather than time since opening the dashboard.
- Slow metrics persist between traffic ticks; expanded rows survive refresh; long values wrap.
- Telemetry stops when leaving Home and resumes when returning from background.
- Missing charging state, missing traffic direction and unavailable counters do not become valid readings.
- Added normal ACCESS_WIFI_STATE permission needed for Wi-Fi metrics. SSID may still require location permission and enabled location settings.

## Try
- What is my Wi-Fi IP?
- What is my battery health?
- What is my screen refresh rate?
- Which sensors are being used?
- What devices are connected to Bluetooth?
- What data is being transmitted?
- Tell me my battery health and free storage.

Answers use local Android state, not an LLM guess. Rate queries measure IRIS traffic for about one second; this is not a bandwidth speed test. No public-IP requests, packet interception, Bluetooth discovery or sensor activation is added. Battery health is a platform condition, not a remaining-capacity percentage. Sensor inventory is hardware availability; active sensor reporting is limited to instrumented IRIS usage. Other apps' payloads, hidden connections and CPU temperature remain unavailable.

Validation: scripts/test-speech.sh includes existing seven suites and new PhoneFactsTest. APK compilation is required in CI; device/OEM visual and permission behaviour still requires phone testing. Source archives exclude signing material and personal assets and are not committed, following the latest repository policy.
