# Splash artwork

Two files, one per orientation. Android picks by configuration, so the code
needs no branch — `Brand` looks up the name `splash_art` and the system resolves
which one that is.

| File | Current | Ideal |
|------|---------|-------|
| `drawable-port-nodpi/splash_art.jpg` | 768 × 1376 | 1440 × 3200 |
| `drawable-land-nodpi/splash_art.jpg` | 1408 × 768 | 3200 × 1440 |

`nodpi` stops Android rescaling per screen density, which is what you want for a
single full-bleed image.

## Composing a replacement

- **Keep an 8% margin clear.** The splash pushes in ~6% across the sequence, so
  the outer edge is gone by the end.
- **Nothing important in the lower third.** A scrim darkens the picture's bottom
  45% so it hands over to the dark cluster; text down there disappears into it.
- **Wordmark in the upper half**, as both current images have it.
- The portrait version wants composing, not cropping: the road running up
  through the frame is what makes it work vertically.

## If the image carries the name

Both current images have SPRINGCOMMAND, the strapline and the credit composited
in, so `Brand.ARTWORK_HAS_WORDMARK` is `true` and the app does not letter the
screen itself. Set it `false` for plain artwork.

## Format

JPEG at quality 88–90, or WebP at 82 for roughly half the size with no visible
difference on a photograph — Android Studio converts in place via right-click →
*Convert to WebP*.

With neither file present the app falls back to a drawn dial badge. Nothing
breaks.
