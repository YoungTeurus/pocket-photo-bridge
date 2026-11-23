# Linux field protocol (Orange Pi)

A field session on the Orange Pi Zero 2W must leave evidence that someone who
was not there can check. Copy the session template below for each session,
fill it in, and commit it to this document. Windows has its recorded field
journal in [the Windows hardware result](windows-live.md); this is the Linux
counterpart. Install and configure the bridge as described in
[the deployment guide](mvp5-deployment.md).

## Rules

- Write the expected outcome of every step **before** the run. Write the
  observed outcome after it, even when it matches. A step without a written
  expectation proves nothing.
- Keep every observation that a claim rests on: a saved console log, a
  downloaded Diagnostics file, a photo or a video. "I looked and it was 1" is
  not kept; the last field run lost exactly this (see the results below).
- Record the queue count before **and** after each service restart, reboot or
  power cut, from a kept observation.
- The repository is public. Record builds, versions, counts, codes, timings and
  sheets. Do not record network names, passwords, tokens, addresses of a home
  network, host names, personal paths or photo content.

## Ground-truth channels

The board's own radio switches between setup and the camera, and the setup page
is closed while the bridge operates, so the bridge cannot be its own witness.

- **Serial console.** A USB serial adapter on the board's debug UART. It works
  without Wi-Fi, Bluetooth or any home network, and it keeps working while the
  radio is joined to the camera. Use it to read the diagnostics file and the
  system journal, and save the terminal log.
- **A phone as independent observer of the camera's Wi-Fi.** The phone's
  Wi-Fi list (not joined) shows whether the camera's network is visible and when
  it disappears. Note the times against the video clock.
- **Video with a clock in frame.** Film the printer's output slot, the camera
  screen and a clock for the whole session. Sheets are counted from the video,
  not from memory; durations are read from the clock.

Bridge events carry only whole seconds since the service started, and the
header's `uptime_s` is the kernel uptime when the service started; the board has
no wall clock. To place an event on the video clock, keep the serial terminal in
frame running `sudo journalctl -f -u pocket-photo-bridge.service`, which prints
each event as the service emits it, and time the event by the moment its line
appears. Without that, estimate it as the filmed bridge power-on plus
`uptime_s` plus the line's offset; this leaves out the time before the kernel
starts and is accurate only to a few seconds.

Reading the bridge's own record:

- Over the serial console, the current run is
  `/var/lib/pocket-photo-bridge/diagnostics.txt`; earlier runs are
  `diagnostics.1.txt` (most recent) up to `diagnostics.5.txt`. The last line
  containing `queued=` gives the queue count the bridge last reported:

  ```bash
  sudo grep 'queued=' /var/lib/pocket-photo-bridge/diagnostics.txt | tail -n 1
  ```

  The bridge forces this file to disk only for lines with a failure category,
  so after a power cut its last lines may be missing. Read it **before** a
  planned reboot or cut, and save the console log.
- After a reboot, sign in to setup within its 60-second window; interacting
  holds it open. The status shows `queued`, and **Diagnostics** downloads the
  current run plus `previousRuns`. A new boot with retention off logs
  `BRIDGE_BOOT_QUEUE_DISCARDED count=N` when it removed waiting photos.
- If the system journal is persistent, the service's output for the previous
  boot is also in `sudo journalctl -u pocket-photo-bridge.service -b -1`.

## Session template

### Setup record

| Field | Value |
| --- | --- |
| Date of session | |
| Build identity (`bridge --help` first line, bundle `BUILD` file, setup `build` field; all must match) | |
| Release tag and archive SHA-256 (from the `.tar.gz.sha256` file, checked with `sha256sum -c`) | |
| Board model (`cat /proc/device-tree/model`) | |
| OS image name and version (`cat /etc/os-release`) | |
| Kernel (`uname -r`) | |
| NetworkManager (`nmcli --version`) | |
| BlueZ (`bluetoothctl --version`) | |
| System journal persistent? (`journalctl --list-boots` lists earlier boots; `/var/log/journal` exists) | |
| Retention setting (**Keep waiting photos across power cycles**: on/off) | |
| Print mode (FIT or CROP) | |
| Power-on order with clock times (bridge, printer, camera) | |

### Step record

Copy one block per step.

| Field | Value |
| --- | --- |
| Step | |
| Expected outcome (written before the run) | |
| Observed outcome | |
| Last diagnostic event (line and its seconds since start) | |
| Queue count before / after (only for a restart, reboot or power cut; say where each was read) | |
| Sheets actually printed (from the video) | |
| Camera screen (what it showed, from the video) | |
| Kept evidence (file or video timestamp) | |

## Session 1 programme

Session 1 runs on a tagged release with retention **off**, as the owner decided
(issue #18, decision 4). The programme follows issue #25.

1. **Measurements.**
   - Scan duration: from the camera network appearing on the phone to
     `SERVICE_CAMERA_PRESENT`, in operation only. It includes the service's
     one-second pause between checks.
   - How long the camera keeps its Wi-Fi after a completed transfer, as seen by
     the phone.
   - What the camera screen shows when the bridge connects a second time
     (re-check of a still-visible camera).
   - Print duration: from the start of the upload to the sheet leaving the
     printer.
   - Power-on to operation: from bridge power-on to `SERVICE_OPERATION`.
     Record both the filmed time to the console line and `uptime_s` plus the
     line's offset; the second excludes the time before the kernel starts.
2. **Series in one boot.** Send photo A, then B, then A again, then choose C on
   the camera while a print is running. Expected: three sheets, no fourth.
3. **Printer off and on.** With the printer off, send a photo; it must wait
   (`queued=1`). Switch the printer on and count the sheets.
4. **Job right after printer power-on.** Submit a job immediately after the
   printer is switched on. Record the reply code (`PRINTER_SUBMIT_REFUSED code=N`
   if refused) and the number of sheets.
5. **Refusal with another code.** If the printer can be made to refuse a job
   with a code other than `8006` (for example cover open or no paper), record
   the code. While the camera stays visible, a re-check logs
   `DUPLICATE_SUPPRESSED` and nothing prints. Then leave the camera's send
   screen until the bridge logs `SERVICE_CAMERA_ABSENT`, and send the same
   photo again: it must print once. Record whether `SERVICE_CAMERA_ABSENT`
   appeared before the re-send. Otherwise record "not provoked".
6. **Four targeted power cuts** (pull the bridge's power): during receive,
   during upload to the printer, after the printer reports finished, and while
   idle. Before each cut read the queue count; after each, reboot into setup,
   download Diagnostics, read the previous run from `previousRuns`, and record
   the queue and print-history state (`queued`, the `stage`, for example
   `NEEDS_ATTENTION` for an unknown print waiting for **I checked the
   printer**, and sheets). If setup does not appear after a cut, do not cut
   the power again: over the serial console run
   `sudo journalctl -u pocket-photo-bridge -b --no-pager` and copy the
   diagnostics so the failing run is kept as well (the state directory is
   readable only by root, so the pattern is expanded there):
   `sudo sh -c 'cd /var/lib/pocket-photo-bridge && tar -cf - diagnostics*.txt' > ~/diagnostics.tar`,
   then record the last `SERVICE_FAILED` category.
7. **Retention.** With retention off, read the queue count with one photo
   waiting (printer off), reboot, and read it again from setup: expected 1, then
   0 with `BRIDGE_BOOT_QUEUE_DISCARDED count=1`. The "retention on" half is not
   applicable (issue #18, decision 4).
8. **Receive fidelity.** The bridge keeps a received original only while its
   photo waits: as
   `/var/lib/pocket-photo-bridge/service/images/<fingerprint>.original.jpg`,
   where the fingerprint is the original's SHA-256. The file is deleted once
   the photo prints, and a reboot with retention off discards it. So send the
   photo with the printer off (or stop the service and use
   `--linux-receive-only`), then, in the same boot and before the printer is
   switched on, run over the serial console
   `sudo sha256sum /var/lib/pocket-photo-bridge/service/images/*.original.jpg`
   and compare it with `sha256sum` of the file on the camera's SD card.
9. **Image check.** Print one FIT and one CROP sample and judge them by eye
   (orientation, colour, framing).
10. **Hotspot recovery without a power cycle** (issue #21). Over the serial
    console stop the service, run `sudo rfkill block wifi`, start the service,
    wait at least five minutes, then run `sudo rfkill unblock wifi`. Expected:
    the hotspot appears on the phone without a power cycle, and the run before
    the outage is still in `previousRuns`. Record the time from the unblock to
    the hotspot appearing, and the `SERVICE_SETUP_FAILED category=... count=...`
    lines of the current and previous runs.
11. **A stop takes the radio down** (issue #23). Run
    `sudo systemctl stop pocket-photo-bridge.service` over the serial console
    once in setup and once in operation while the camera is connected. Expected
    each time: `nmcli connection show --active` lists no connection owned by
    the bridge, the hotspot is gone from the phone, and the last lines of
    `sudo tail -n 3 /var/lib/pocket-photo-bridge/diagnostics.txt` contain
    `SERVICE_STOPPED`, followed in setup by `LINUX_SETUP_STOPPED`, with no
    `SERVICE_RADIO_CLEANUP_FAILED`. Record whether a bridge connection was still
    listed (not the network names) and the last two diagnostic lines, then
    start the service again.

Also record, for the conditional work: whether any power cut damaged the
persistent state (setup `stage` is `STATE_ERROR`); whether the printer still
connects after it was paired with another device or reset; whether a full queue
delays receiving; and the board LED check of issue #24.

## Earlier Orange Pi results (pull request #9)

Recorded from the description of pull request #9, as stated there. These runs
predate this protocol: their expected outcomes were not written down in advance
and they were not filmed. Both builds also predate later changes (run headers
and rotated diagnostics, build identity, the `10.212.77.1` hotspot address,
print retry back-off, the re-check of a still-visible camera and the handling
of refused jobs), so they do not validate those.

**Build d3b3cf0**

- Reconnecting to the protected setup AP, pinned SSH access, and the original
  HTTP login/CSRF forms passed.
- Two separate cold boots entered operation without signing in or choosing Run.
- JPEG A printed correctly.
- JPEG B waited while the printer was off and printed exactly once when the
  printer was switched on later.
- Afterwards the checksummed queue was empty, there was no reservation, and
  three historical submitted-photo records were retained.

**Build f116b4f**

- The pinned package installed with the bootstrap, configuration, queue and
  print journal preserved.
- The authenticated CSRF forms showed the retention setting off by default,
  saved it on and off, and preserved the other fields.
- A cold boot closed setup without HTTP login or Run.
- In a paper-free scenario the camera reported transfer completion with the
  printer off; after camera off, a 10-second power-off of the bridge, restart
  and printer on, no printing was observed. The final queue and image store were
  empty, there was no reservation, and the three-record print journal was
  unchanged.
- The observer did not keep its "before" file, so an independently observed
  transition from one waiting photo to none across the reboot is **not**
  claimed.

**Stated limits.** Long photo series and deliberate interruption during
printing remained outside this two-print hardware acceptance. Builds ran only
in GitHub Actions.

Not yet exercised on the board: a series of photos in one boot, re-checks of a
still-visible camera, refused jobs, power cuts during receive or printing,
SD-card hash comparison, FIT/CROP print quality, memory use and temperature,
Bluetooth and Wi-Fi coexistence over a long session, and battery power.
