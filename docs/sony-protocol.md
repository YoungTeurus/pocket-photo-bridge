# Sony smartphone transfer: evidence and implementation

Investigated 2026-10-11. This receiver is **experimental and not yet tested on the user's RX100 VII**. A successful build or fixture test is not camera compatibility certification.

## Relevant evidence

- [Sony RX100M7 Help Guide](https://helpguide.sony.net/dsc/1920/v1/en/contents/TP0001140498.html) distinguishes camera-side selection from smartphone-side browsing. It documents Original/2M/VGA copying, RAW-to-JPEG conversion, and the Wi-Fi information screen. It does not publish the wire protocol.
- [ImagingEdge4Linux](https://github.com/schorschii/ImagingEdge4Linux/tree/794ae128bfdc84ca4ec72f54bc0d5d7153551e24) implements the smartphone SOAP service and reports DSC-RX100-M7 compatibility. Its [protocol implementation](https://github.com/schorschii/ImagingEdge4Linux/blob/794ae128bfdc84ca4ec72f54bc0d5d7153551e24/imaging-edge.py) distinguishes `PushRoot` (camera selection) from `PhotoRoot` (entire library), with `X_TransferStart` and `X_TransferEnd`. This is direct implementation evidence for the requested mode, though the compatibility table does not supply an RX100M7 packet capture, firmware version, or original-file hash comparison.
- [sony-pm-alt](https://github.com/falk0069/sony-pm-alt) explicitly implements **Send to Computer**, PTP/IP on port 15740 and PC GUID registration. Its success does not prove Send to Smartphone works.
- [SonyPhotoTransfer](https://github.com/Morning-Sea/SonyPhotoTransfer) primarily targets ZV-E10 and describes phone-side browsing with GetStorageIDs/GetObjectHandles/GetObject. Its broad RX100 compatibility claim does not demonstrate this camera-selected flow. Its README also describes both PTP/IP and an HTTP bypass inconsistently; that claim is not used as protocol evidence here.
- [Android Wi-Fi guidance](https://developer.android.com/develop/connectivity/wifi/wifi-bootstrap) requires user-approved association for network requests. Manual Wi-Fi association is a simpler supported MVP path. The app uses the attached `Network.openConnection`, avoiding default-route cellular/VPN internet traffic.
- [AGP compatibility](https://developer.android.com/build/releases/agp-8-13-0-release-notes): AGP 8.13.2, Gradle 8.13, Java 17 and API 36. Kotlin 2.2.21 is explicitly pinned.

No third-party application source is bundled or copied. The Kotlin implementation uses independently written transport, XML parsing, selection checks and tests based on the protocol operations above. ImagingEdge4Linux's GPL source is cited as research, not incorporated as a library.

## Implemented wire flow

1. User joins the camera AP in Android Settings. Connect attaches to the existing Wi-Fi Network, even if it has no internet. It derives an IPv4 default gateway locally; `192.168.122.1` is a labeled fallback from community evidence, not a universal model guarantee.
2. GET `http://<gateway>:64321/DmsDescPush.xml`. Discover the advertised ContentDirectory and Sony XPushList service URLs, and read the XPushList SCPD. Diagnose performs only these GETs.
3. Receive verifies that `X_TransferStart` and `X_TransferEnd` are advertised, then POSTs the start SOAP action.
4. POST ContentDirectory `BrowseDirectChildren` with ObjectID **PushRoot**. Decode the escaped DIDL XML in Result; follow only containers returned under that root, with bounded pagination and cycle checks. Never fall back to PhotoRoot, the latest file, a PTP browser or remote capture.
5. Require exactly one selected item with a JPEG title and a non-thumbnail JPEG resource. Choose the largest advertised eligible JPEG. Stream its HTTP resource into private cache; enforce length/size limits and JPEG boundary checks. The Android validator confirms decoding **before** sending successful TransferEnd, then the UI generates a sampled preview.
6. After a complete download, send `X_TransferEnd` with ErrCode 0. If end acknowledgement fails, retain the downloaded JPEG and report END_UNCONFIRMED. An interrupted/failed download is deleted and does not send successful TransferEnd; exit/re-enter sharing on the camera before retrying.
7. Save copies the received bytes unchanged into a pending MediaStore row in `Pictures/Pocket Photo Bridge`, then publishes it. No recompression or EXIF modification. The preview uses a reduced bitmap; the saved file remains the received JPEG.

HTTP requests use the chosen Wi-Fi network without a system proxy or redirects. Advertised URLs must be HTTP to the exact camera IPv4 address; a separately advertised media port is permitted. XML is size-bounded, DTD/entity declarations are rejected, and raw XML/URLs/file names are never exported. Only private IPv4 camera endpoints are accepted.

Connection establishment and header exchange are coordinated with transport closure. Cancellation marks the transport immediately and drains bounded active requests off the UI thread. Wi-Fi loss cancels network I/O without interrupting the worker or discarding its validated local JPEG; user Cancel/foreground exit remains a separate interruption. Known JPEG thumbnail and icon profiles are excluded before resource selection.

## Remaining hardware questions

- Does this RX100M7 firmware advertise the same push descriptor/actions and selected-list behavior? Is SSID/password alone enough, or does it require another pairing negotiation?
- Does the largest non-thumbnail JPEG resource contain the full original image on this firmware? A camera-side selected **JPEG**, dimensions, file size and preferably SHA-256 comparison against the SD-card original are needed to claim byte-exact original quality. RAW conversion and movies are deliberately rejected.
- Does Android keep the camera AP connected while its no-internet prompt is accepted? Does its IPv4 route expose the correct gateway? Do credentials persist across camera/tablet restarts?
- Does X_TransferEnd drop Wi-Fi before its HTTP response reaches Android? This case is reported separately and does not discard a complete JPEG.

If the descriptor/actions/PushRoot differ, the app stops with diagnostics; the precise blocker is the unobserved RX100M7 service/selection handshake. Run [the tablet experiment](tablet-testing.md). No packet capture, PC, or Imaging Edge installation is required for the first test. An optional Imaging Edge comparison may help isolate a firmware/pairing problem, but it is not part of this receiver's normal flow.
