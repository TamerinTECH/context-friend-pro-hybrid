# ContextFriend brand assets

A vector redraw of the ContextFriend logo: a blue box, a yellow lid with three sound bars, two smiling
eyes. Every file here and every Android icon is generated from one geometry:

```bash
python -I docs/brand/make_brand.py
```

PNGs are rendered with headless Chrome; no extra Python packages are needed.

## Files

| File | Use |
|---|---|
| `contextfriend-logo.svg` | Master logo, transparent, 512 × 512 |
| `contextfriend-logo-mono.svg` | One-colour logo (navy), for print or embossing |
| `contextfriend-app-icon.svg` | Square icon on white (source of the Play icon) |
| `png/contextfriend-logo-{48,96,192,512,1024}.png` | Transparent PNGs |
| `png/play-icon-512.png` | Google Play app icon |
| `png/play-feature-graphic-1024x500.png` | Google Play feature graphic |
| `png/devpost-thumbnail-1200x800.png` | Devpost project thumbnail (3:2) |
| `devpost-thumbnail.svg`, `play-feature-graphic.svg` | Sources of the two images above |

Android (written by the same script into `android/app/src/main/res/`):

| Resource | Use |
|---|---|
| `mipmap-anydpi-v26/ic_launcher.xml`, `ic_launcher_round.xml` | Adaptive app icon, white background |
| `drawable/ic_launcher_foreground.xml` | Full-colour logo inside the 66 dp safe zone |
| `drawable/ic_launcher_monochrome.xml` | Themed icon (Android 13+) |
| `drawable/ic_stat_contextfriend.xml` | Notification icon (white silhouette) |
| `drawable/logo.xml` | In-app logo (`BrandMark`) |

## Colours

| Role | Colour |
|---|---|
| Logo blue (also the app's primary) | `#245FD6` |
| Lid yellow (also the app's accent) | `#F6C453` |
| Eyes | `#FFFFFF` |
| Text navy | `#162E51` |

The full app palette is in [../design/design-tokens.json](../design/design-tokens.json) and `Theme.kt`.

If the original vector file of the logo is available, replace the geometry at the top of
`make_brand.py` and run the script again; all assets update together.
