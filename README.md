# Music (Android app)

A fully native Android music app (not a web page): Spotify-style smooth scrolling, animations and player, plus Wi-Fi sync with the PC page. Songs are saved inside the app on your tablet.
On the home screen it is called **Music**. Music plays natively, so it keeps going with the screen off and shows
the normal lock-screen / notification player (allow notifications when asked).

## Get the APK (about 5 minutes, free, no Android Studio)
1. On github.com create a **new private repository** (any name, e.g. `loved-songs`).
2. Click **uploading an existing file** and drag in everything from this folder
   (including the hidden `.github` folder), then **Commit changes**.
   - If `.github` did not upload: go to **Actions → set up a workflow yourself**, paste the content of
     `.github/workflows/build.yml`, and commit.
3. Open the **Actions** tab. A run called "Build Loved Songs APK" starts by itself. Wait for the green tick.
4. Click that run, scroll to **Artifacts**, download **LovedSongs-apk**, unzip it: `LovedSongs.apk`.
5. Copy `LovedSongs.apk` to the tablet, tap it, allow "install unknown apps" when asked.

Updating later: upload the new files, wait for the new run, install the new APK over the old one.
Your songs stay (same signing key, kept in `app/lovedsongs.jks`, so keep this repository private).

## Sync with your PC
1. Tablet and PC on the same Wi-Fi. Open the app on the tablet.
2. Tablet: tap the sync button (circular arrows). It shows an address and a 6-digit code.
3. PC: open `loved-songs.html` (the downloaded file), tap the same sync button, enter them once, press Sync now.
   Next time just press Sync now. If the tablet's address changed, the PC finds it again by itself.
Songs, edits, thumbnails, E badges and removals travel both ways. Nothing uses the internet.
