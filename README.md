# Airsonic — Personal Fork

**Fork lineage:** Subsonic → Airsonic → Airsonic-Advanced → Airsonic-Pulse → **Here**

## About

This is a personal fork of Airsonic-Pulse, maintained for my own use. It will be periodically rebased on upstream as new releases land.

## Development

All modifications in this repository are written with **GitHub Copilot**, powered by **DeepSeek V4 Pro**. Changes are not pushed upstream due to potentially unstable quality. These are pragmatic, personal-use patches.

## Changelog (from forking)

- **Cue sheet handling** — builds new CUE parser (replacing strict `cuelib-core`, supporting extended/non-standard sheets: long titles, 99+ min time codes, non-zero first INDEX, special characters, REPLAYGAIN, empty lines, non-standard CATALOG/ISRC); BOM-first charset detection to prevent Japanese mojibake; dot-prefixed media files no longer excluded; added `tta`/`ac3`/`tak` to default music types; fixed multi-cue shared-base resolution and subdirectory FILE paths; corrected shared-base after upstream merge; fixed negative-track metadata leak and tracks with missing INDEX
- **Database** — increased varchar column limit 384→1024 for ensemble artist names; prevented duplicate album/artist errors during scan; propagated saved IDs back to original objects
- **Scanner** — handled null root MediaFile and Chapter ID overflow
- **CI** — upload `.war` as build artifact on push