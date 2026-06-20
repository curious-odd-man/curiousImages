# Test fixtures for `PhotoMetadataExtractorTest`

- `with-exif-dates.jpg` — 800x600 JPEG with `DateTimeOriginal` = 2023-06-15T14:30:00 and
  `DateTimeDigitized` = 2023-06-16T09:00:00, plus `PixelXDimension`/`PixelYDimension` EXIF tags.
  Exercises the `EXIF_ORIGINAL` priority branch.
- `no-exif-dates.jpg` — 640x480 baseline JPEG with no EXIF segment at all. Exercises the
  `FILESYSTEM` fallback branch (capture date comes from `Files.getLastModifiedTime`) and the
  `JpegDirectory` width/height fallback branch (no EXIF dimension tags present).
- `plain.png` — 320x240 PNG. Exercises the `PngDirectory` IHDR width/height path.

## Missing: a real CR2 fixture

No `sample.cr2` is included here. CR2 is Canon's proprietary TIFF-based raw format; this sandbox
has no network access to either a real camera-shot sample or the `metadata-extractor` library
itself (so a hand-fabricated TIFF could not be validated against the real parser before being
committed). Shipping a fake "CR2" that doesn't faithfully reproduce a real file's IFD/thumbnail
structure would risk giving false confidence rather than real coverage.

**Before shipping this feature**, drop a real CR2 sample (with an embedded EXIF preview) into this
folder as `sample.cr2`, then un-skip `PhotoMetadataExtractorTest.cr2EmbeddedPreviewExtraction`
(currently `@Disabled`) and verify against the *exact pinned* `metadata-extractor` version, per the
known-regression caveat in the implementation plan, §9.
