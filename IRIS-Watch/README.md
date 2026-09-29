# IRIS Remote for Amazfit T-Rex 3 (experimental)

This is a Zepp OS mini-app source project, not an Android APK for the watch. It provides phone status/charging, a 30-second phone finder, stop finder, and a request that posts a Talk notification on the phone. Talk uses the phone microphone after the notification is tapped. Watch microphone streaming and replacing Zepp Flow are not implemented.

## Pair and test

1. Install IRIS 12.0.0 on Android. In Settings → T-Rex 3 remote, authenticate, enable the bridge, and obtain the pairing secret. Keep IRIS running.
2. In this directory run `npm install`, then use the official Zepp Zeus CLI to build/preview (`zeus build`, `zeus preview`). The development app ID is 20001; obtain your own Zepp app ID before distribution. Use the T-Rex 3 target (480 × 480).
3. Install via Zepp developer preview. Open the mini-app settings in Zepp on the same Android phone and paste the secret. Do not send the secret to anyone.
4. Press Phone status, Ring phone, Stop ringing, and Request talk. Test screen-on/off and reconnect, then silent mode and your actual DND modes. DND must allow alarms.
5. Disable the bridge to stop accepting requests. Generate a new pairing code to revoke the old one; update Zepp settings.

The watch talks to its Zepp Side Service over BLE. The Side Service tries authenticated HTTP to `127.0.0.1:18473` on the SAME phone. No LAN endpoint or cloud relay is provided. The server rejects browser origins, arbitrary commands, bodies, unauthenticated requests and rapid repeated actions. Pairing does not grant access to contacts, messages, recordings or owner profiles. Anyone with the secret on the same phone can use these limited controls.

**Compatibility gate:** Zepp's Android build may prohibit cleartext or loopback HTTP. Its public fetch documentation does not guarantee localhost support. A successful source build does not prove this transport works on a real T-Rex 3. If status cannot connect while IRIS is running, this prototype cannot yet be used on that Zepp build; do not expose the endpoint to your Wi-Fi network as a workaround. No real watch/device test has been performed here.

Official references: [device list](https://docs.zepp.com/docs/reference/related-resources/device-list/), [architecture](https://docs.zepp.com/docs/guides/architecture/arc/), [Fetch API sample](https://docs.zepp.com/docs/samples/app/fetchAPI/). ZML wiring follows the interfaces shown in the official zeppos-samples fetch-api and todo-list projects; application code is original.

Validation here: JavaScript syntax checks passed. Zeus packaging was blocked by automatic approval review when the CLI contacted an external upload CDN that might receive source files. No packaged watch app is supplied; external build/upload approval and real-device testing remain outstanding.
