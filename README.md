# Wakeup

An alarm clock for Android that makes you do a small mission before it turns off. No ads, no account, no subscription.

**Install:** open the [latest release](../../releases/latest) on your phone, tap `Wakeup.apk`, then open it.

## Missions

- **Math**: solve problems (Easy to Brutal)
- **Typing**: type a phrase exactly
- **Shake**: shake the phone a number of times
- **Memory**: remember which squares lit up
- **Steps**: walk a number of steps
- **Barcode**: scan a code you registered (put it in another room)

The alarm goes quiet while you're doing the mission. If you stop for about 30 seconds, it gets loud again.

## Making sure it rings

- Uses `setAlarmClock()`, the same exact alarm the stock Clock app uses
- A full-screen alarm page over the lock screen
- Alarms are set again after a restart, time change or app update
- Plays on the alarm channel at max volume, and the volume buttons do nothing while it rings
- A setup screen checks the permissions Android needs

## Build

GitHub Actions builds the APK on every push to `main` and publishes it to the `latest` release.
