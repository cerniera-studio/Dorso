# Dorso

*Dorso: the spine of a book.*

Long reads from Medium, The Athletic, Reddit and the web, set as a printed two-page spread on the Galaxy Z Fold, with the crease as the spine. Start on the cover, unfold, and the same sentence waits at the top of the left page.

A Cerniera app, from the Daily App Ideas page of 19 Nov 2026. Built on the [Kit](https://github.com/cerniera-studio/kit) (a git submodule at `kit/`).

- **Share** a link to Dorso from any app (or paste it on the shelf). The page is fetched once, its article kept by Readability's rules (as Firefox's Reader View), and stored on the phone.
- **Every shape:** one page on the upright cover; two short pages on the turned cover; a page each side of the crease when open, the gutter wider than the crease, the camera hole kept clear. A crease across (half folded that way) gives a page above and a page below.
- **Your place** is a character, not a percentage: after a fold, a turn or a new type size, the pages are cut afresh so the line you were reading starts the left page.
- **Signed-in pages** (The Athletic, Medium member stories) arrive as their teaser for now; reading them through your own session is the next step.

Builds on main are published as releases with `Dorso.apk`; other branches as pre-releases. The emulator walk (`tools/emulator_test.sh`) changes shape under an open book and checks the place holds.
