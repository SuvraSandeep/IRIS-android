# IRIS — Standing Project Rules (read every session)

## RULE 1 — Keep the feature deck in sync (MANDATORY)
There is an interactive feature deck at **`IRIS-FEATURES.html`** (project root): a self-contained
HTML "PPT" with one page per feature, an index/home, live search, category filters, and
keyboard navigation.

**Every time a feature is added, edited, or removed, I MUST update `IRIS-FEATURES.html` in the
same change:**
- Add / edit / remove the matching object in the `FEATURES` JavaScript array.
- Update the `BUILD_VERSION` constant near the top to the new app version.
- Keep categories consistent.

Feature object shape:
```js
{ id, cat, icon, title, tagline, desc, examples: [..voice phrasings..], version }
```
Categories: `Voice & Recognition`, `Communication`, `Productivity`, `Info & Awareness`,
`Intelligence & Memory`, `Personalization & UI`, `Privacy, Safety & Robustness`.

This is a permanent obligation for this project — do not skip it.

## RULE 2 — Versioning (existing)
Bump `versionName` and `versionCode` on every release. Major feature +1.0.0, small feature +0.1.0,
build/issue fix +0.0.1. versionCode always +1.

## RULE 3 — Deliverables (existing)
Each change: rebuild `IRIS-Android-vX.Y.Z-source.zip` + `build-iris-apk.yml` in the project root,
and provide a commit message.
