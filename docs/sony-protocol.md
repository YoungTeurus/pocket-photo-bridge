# Sony smartphone transfer: evidence and implementation

Investigated 2026-10-11. This receiver is **experimental, with one successful end-to-end user test on the RX100 VII and an Android API 36 tablet using version 0.1.1**. The result applies to that tested setup; it does not establish compatibility with every firmware or tablet.

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

## First tablet result and parser correction

The user's 0.1.0 test on Android API 36 reported `WIFI_ATTACHED`, `GATEWAY_FROM_WIFI`, then `HTTP_STATUS 200 → DESCRIPTOR → HTTP_STATUS 200 → INVALID_XML`, consistently across retries. This confirms access to the camera's main descriptor and advertised action-description endpoint, but no transfer started.

The SCPD parser searched all descendants for an action's `name`. A standard action with an `argumentList` also contains argument `name` elements (including the researched TransferEnd input ErrCode), so valid XML was rejected as ambiguous. Version 0.1.1 reads only the action's direct-child name. Regression fixtures cover namespaced, prefixed and unnamespaced SCPD responses, and reject missing/duplicate action names. Session fixtures now include the nested TransferEnd argument. These are independently written protocol fixtures, not captured XML from this camera. The user's subsequent 0.1.1 test passed action discovery and completed the transfer, confirming that the fix resolved the observed hardware failure.

## Successful 0.1.1 hardware retest

The user reported “It worked!” and supplied this redacted success sequence on Android API 36:

```text
WIFI_ATTACHED
GATEWAY_FROM_WIFI
DESCRIPTOR
ACTIONS
TRANSFER_START
PUSH_PAGE count=1
SELECTION count=1
JPEG_BYTES count=13805942
TRANSFER_END
PREVIEW_READY
GALLERY_SAVED
WIFI_LOST
DISCONNECTED
```

Every recorded HTTP response was 200. This establishes that the tested RX100 VII exposes the researched service/actions and PushRoot selection flow, and that the tablet received a JPEG, decoded it, displayed a preview and completed MediaStore save. Wi-Fi loss occurred after Gallery save, so it did not interrupt this transfer. Manual Wi-Fi association was sufficient in this run. No camera XML, photo, credentials or network identifiers were collected.

The camera firmware/tablet model, an older-than-newest selected-image comparison and an SD-card original hash comparison were not supplied. The 13,805,942-byte transfer count alone does not establish full-original byte equivalence. The `HARDWARE_VALIDATION_PENDING` line in 0.1.1 is a static marker from the build produced before this test, rather than a transfer failure.

## Remaining hardware questions

- Does the successful service/selection flow repeat after camera/tablet restarts and on other firmware/tablets? Record firmware and tablet model when available.
- Does the largest non-thumbnail JPEG resource contain the full original image on this firmware? A camera-side selected **JPEG**, dimensions, file size and preferably SHA-256 comparison against the SD-card original are needed to claim byte-exact original quality. RAW conversion and movies are deliberately rejected.
- Do Wi-Fi credentials and the working gateway route persist across camera/tablet restarts? Both attachment and gateway routing worked during the successful test.
- What happens on interruption, multiple selection, or camera Wi-Fi loss before the TransferEnd response? The successful run acknowledged TransferEnd and lost Wi-Fi only after save; other lifecycle cases still need device testing.

If another camera setup has different descriptor/actions/PushRoot responses, the app stops with diagnostics. Use [the tablet procedure](tablet-testing.md) to establish the failing stage. No packet capture, PC, or Imaging Edge installation is required for the basic test. An optional Imaging Edge comparison may help isolate a firmware/pairing problem, but it is not part of this receiver's normal flow.
