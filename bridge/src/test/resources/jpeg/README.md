These synthetic fixtures were encoded independently with Pillow 12.3.0/libjpeg.
They contain no camera data.

- `annex-k-jpeg90.jpg`: 16×16 RGB `(127, 85, 42)`, JPEG quality 90,
  `optimize=False`. Its DHT and DQT segments are the independent reference for
  the printer's Annex K tables and quality-90 quantizers.
- `large-quadrants.jpg`: 8192×8192 RGB with red, green, blue and yellow quadrants
  in reading order, JPEG quality 90, `optimize=True`. A separate JVM with a
  64 MiB heap validates and prepares this image; a full decoded raster would
  exceed that heap.

Other fixtures are constructed by tests using JDK ImageIO and small, explicit
TIFF APP1 segments. The production preparer has no Pillow dependency.
