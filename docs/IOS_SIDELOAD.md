# Installing ChapterPlayer on an iPhone without a paid Apple account

The macOS CI job builds an UNSIGNED `ChapterPlayer-unsigned.ipa` on every merge to `main` (about 20 extra minutes, so not on pull requests) and on a manual run (Actions, Android CI, Run workflow) (artifact name `ChapterPlayer-unsigned-ipa`, kept 14 days). You sign it yourself with a free Apple ID and install it over USB from Windows.

## What you need (once)
- A Windows PC with iTunes (the Apple installer version, not the Microsoft Store one) so the PC can talk to the iPhone, and the iPhone connected by USB and trusted ("Trust this computer").
- Sideloadly (https://sideloadly.io), free.
- A free Apple ID. Use a spare one if you prefer: Sideloadly signs the app with a certificate tied to it.
- On the iPhone: Settings, Privacy & Security, turn on **Developer Mode** (iOS 16 and later; the phone restarts once). After the first install, open Settings, General, VPN & Device Management, tap the Apple ID shown, and **Trust** it.

## Each install or re-sign (every 7 days)
1. On GitHub, open the latest run of "Android CI" on `main`, download the artifact `ChapterPlayer-unsigned-ipa`, and unzip it to get `ChapterPlayer-unsigned.ipa`.
2. Open Sideloadly, plug in the iPhone, drag the `.ipa` onto it, enter the Apple ID and press Start. The first time it asks for an app-specific password or two-factor code.
3. The app appears on the home screen. It stops opening after 7 days: repeat step 2 (your data stays) to renew it.

## Limits of the free route
- 7 days per signature, and at most 3 sideloaded apps per Apple ID.
- No CarPlay, no push notifications and no other special entitlements; a few system features are restricted.
- The app uses plain http to reach servers on your home network, so the first time you add a local server iOS asks for **Local Network** access: allow it.

## If it does not install or open
- Check Developer Mode is on and the Apple ID is trusted (see above).
- "Maximum number of apps" means three free apps are already installed under that Apple ID: delete one.
- Tell Claude what error text Sideloadly or the phone shows.
