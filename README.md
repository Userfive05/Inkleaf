# InkLeaf

A minimal EPUB reader for the Onyx Boox Go 7.

## Features
- **Library**: lists every EPUB on the device, most recently opened first, with % read.
- **Page turns**: tap the right third of the page for next, the left third for previous (swiping and the page/volume buttons also work).
- **Menu**: tap the middle of the page to open Library · Contents · **Settings ▾**.
- **Settings dropdown**: text size, font, line spacing, margins, light, no hyphens, left alignment, Update.
- **No hyphens** (on by default): words are never split; whole words move to the next line. Soft hyphens in the book are ignored.
- **Left-aligned text** (on by default): justified paragraphs become left-aligned, so there are no stretched gaps between words. Centred headings stay centred.
- **Fonts**: Book default, Serif, Sans-serif, any `.ttf/.otf/.woff/.woff2` added with *+ Add font file…*, and fonts found in `/sdcard/Fonts` or `/sdcard/koreader/fonts`.
- **Light**: swipe up or down along the left edge of the page. Allow "modify system settings" when asked so it drives the front light.
- **Update**: checks this repo's latest GitHub Release and installs it. The button shows ● when a new build is out.

## Install
1. Open the latest release on the Boox and download `InkLeaf.apk`.
2. Install it, allowing installs from the browser or file manager if asked.
3. Open InkLeaf, tap the message to allow file access, then pick a book.

Every push to `main` builds a new release automatically, so updates come from the in-app Update button after that.

The signing key (`app/inkleaf.keystore`) is committed on purpose so every build can install over the last one.
