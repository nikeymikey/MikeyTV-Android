# MikeyTV for Android TV

A personal fork of [Wholphin](https://github.com/damontecres/Wholphin) by damontecres, licensed under GPL-2.0. It adds MikeyTV branding and theme, plus replies to admin messages through the MikeyTV Messaging server plugin.

## Install with Downloader

| Device | URL |
| --- | --- |
| Fire TV Stick, Chromecast with Google TV | `https://raw.githubusercontent.com/nikeymikey/MikeyTV-Android/downloads/MikeyTV.apk` |
| 64-bit only devices | `https://raw.githubusercontent.com/nikeymikey/MikeyTV-Android/downloads/MikeyTV-arm64.apk` |

## Messages

Admin messages show for 12 to 30 seconds without taking focus. Open them with Menu while the message shows, from **Messages** in the side menu, or from the message button in the player controls. Replies need the MikeyTV Messaging plugin on the server.

## Merge upstream changes

```bash
git remote add upstream https://github.com/damontecres/Wholphin.git
git fetch upstream
git merge upstream/main
```

New code lives in the `mikeytv` package and files named `mikeytv_*`. Kotlin package names are unchanged on purpose, to keep merges simple.
