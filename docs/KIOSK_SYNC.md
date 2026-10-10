# Optional: OpenSkyLight kiosk sync

JezLauncher can keep its notes in two-way sync with a list on an OpenSkyLight kiosk in the same
[Tailscale](https://tailscale.com) tailnet. It is **off by default** and not in the published builds: you turn it on
when you build the app yourself.

## What it does

- Pair once in **Settings → Kiosk sync**: scan the QR code the kiosk shows for the phone (or paste its link), then
  choose one of the kiosk's lists. Later: sync now, change the list, or unpair (the notes stay on both sides).
- Notes written, struck or deleted on the phone show on the kiosk within a second or two; items added or checked on
  the kiosk show on the phone while the launcher is on screen (the kiosk's change stream), otherwise at the next
  background sync (every 15 minutes).
- When the kiosk is off or out of reach, changes wait on the phone and go when it answers again. The settings row says
  when the last sync went through, or why not.
- The first sync with a list merges both sides: notes and items with the same text become one.
- Text, done state and order sync. Schedules, routines and alerts stay on the phone; the kiosk only stores them for it.
  A daily or weekly note shows unchecked on the kiosk again when its new day starts, as it does on the phone.

## Turning it on

```sh
./gradlew assembleRelease -Pjezlauncher.kioskSync=true
```

(or set `jezlauncher.kioskSync=true` in `gradle.properties`). Builds without the flag have no pairing screen, start no
sync work, open no connection, and have neither the QR scanner (zxing) nor its camera permission. The notes database
is the same in both builds.

## What the kiosk side needs

- OpenSkyLight with its companion sync API (`/api/sync/lists`, `/items`, `/events`), reachable over Tailscale.
- Tailscale on the phone, in the same tailnet.

## Security

The kiosk serves plain **http inside the tailnet**; the traffic itself is encrypted by Tailscale's WireGuard tunnel.
Kiosk sync builds therefore allow cleartext traffic, and the app narrows it in code: plain http only to a tailnet IP
(100.64.0.0/10, fd7a:115c:a1e0::/48), a MagicDNS name (`*.ts.net`) only over https (such names resolve in public DNS
too, so plain http could send the token outside the tailnet), and requests only to the paired host. Every other
network call of the app still requires https. The pairing token is kept out of backups and never logged.
