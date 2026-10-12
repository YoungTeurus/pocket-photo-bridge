# MVP 5: autonomous Linux bridge and dedicated setup mode

Approved scope: power on the bridge and printer in either order, select one JPEG
on the RX100 VII, receive it and print automatically. A dedicated setup hotspot
provides local configuration and troubleshooting. Cloud builds produce portable
Windows simulation and Linux ARM64 packages; users compile nothing.

## Architecture

Keep the proven Android application, Windows manual workflow and Kotlin Sony /
Xiaomi protocol modules unchanged. Add three layers to the JVM bridge:

1. A durable autonomous controller separates camera capture from printing. It
   commits validated original JPEG bytes and a content fingerprint before Sony
   TransferEnd, retains queued photos while the printer is off or busy, prepares
   the same orientation-correct print image, and uses the existing persistent
   print reservation guard. Once a printer might have accepted a job, loss of
   completion blocks automatic submissions until the owner checks the printer.
   Acknowledgement never removes the duplicate-prevention tombstone.
2. A local setup server stores credentials privately and displays status, queue
   counts and redacted diagnostics. It uses a generated administrator password,
   authenticated sessions and CSRF protection. Credentials never appear in URLs,
   diagnostics, process arguments or status responses. No remote assets/services.
3. Linux adapters manage exclusive NetworkManager hotspot/camera modes and BlueZ
   pairing / discovered SPP RFCOMM channels. The real Sony and Xiaomi sessions do
   the transfer. Radio operations have deadlines and fixed diagnostic categories.

The first boot stays in setup. Each configured boot offers a 30-second setup
window; an authenticated interaction holds setup open. Otherwise the hotspot and
HTTP server stop before joining the camera network. The owner can choose Run
from setup; enabling automatic printing is an explicit one-time configuration
choice. There is no confirmation for each photograph during normal operation.

The setup hotspot uses unique locally generated WPA2 credentials. Installation
from Windows SSH is a one-time deployment step, not compilation; the installer
shows the hotspot and administrator credentials privately. No universal password
or token is included in downloadable artifacts. The setup page is bound only to
the setup address; Windows simulation binds only to loopback. It is deliberately
unreachable while the single radio joins the camera. Reboot opens setup again.

Queue records, original/prepared images and tokens live in private state with
atomic snapshots, bounded sizes and exclusive ownership. Retries are allowed
before print reservation. A valid captured photo survives printer failure,
process restart and an unsuccessful camera acknowledgement. Corrupt state fails
closed. Identical JPEG bytes are automatically printed once; deliberate reprints
are outside this milestone. Completed image files can be removed while retaining
their durable content tombstones. Disk/queue bounds stop new captures safely.

## Windows-first verification

Add a service simulation with the real controller, HTTP setup page and protocol
sessions against synthetic wire devices. Controls simulate camera selection,
printer off/on/busy, interrupted download and uncertain print completion. It
cannot access radios or print paper. Existing manual Windows hardware commands
remain available separately. Automated scenarios prove power-order independence,
restart retention and duplicate suppression. The extracted portable EXE and
bundled Java runtime are checked in GitHub Actions, as are Linux x64 / ARM64.

## Deployment and limits

Target a board-supported 64-bit Debian/Ubuntu installation with NetworkManager,
BlueZ, libbluetooth and the Java desktop native dependencies needed by ImageIO.
An installer and enabled systemd service use the bundled runtime. This pilot
service needs administrative radio control; its authenticated local server,
private state, restricted listener and systemd sandbox are part of that boundary.
Do not install it on an unrelated multiuser server. No custom OS image, GPIO UI,
concurrent hotspot/client Wi-Fi, Linux GUI or embedded port is included.

Linux AP support, radio drivers, pairing, SPP discovery, reconnect and physical
printing remain pending Orange Pi validation. Windows and Android physical
success are evidence for their own workflows, not for Linux radio integration.

## Review focus

Inspect power loss between queue commit and Sony acknowledgement, between print
reservation and completion persistence, native ABI / errno / SDP handling,
hotspot-to-camera exclusivity, credential injection and redaction, HTTP auth /
CSRF / DNS rebinding, file permissions and symlinks, retry boundaries, queue
capacity, and packaged runtime module completeness.
