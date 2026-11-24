# Parked features — optional, low-priority, no scheduled go
Canonical ledger for nice-to-haves that are cheap to bolt on later but own
no design of their own. Rule of the file: anything parked here must state
(1) where it plugs in (file/doc anchor so future-me doesn't re-derive it)
and (2) what ruling it defers. Completed items move verbatim to
docs/feature_log.md (repo rule 2026-11-14). Big frozen initiatives live in
the ⏸ PARKED sections of docs/roadmap.md instead; this list is the small
stuff.

## PF-1 Inbox media-type dropdown
Ruled optional 2026-11-23 (user): the sentence-media design ships the inbox
filter as one segmented control (all / photos / other). Upgrade path: a
dropdown (or chip row) listing every media_refs.kind present in the corpus.
Plugs into: docs/sentence-media-refs-design.md §4 U2 + the renderer
registry §4 U1 (kind list already derivable with
SELECT DISTINCT kind FROM media_refs). Cost: layout only; no schema, no
query-shape change (predicate is already kind-driven).

## PF-2 Video-row posters in the inbox
Ruled out of M1 2026-11-23 (D4 = text card + kind chip only). Deferred
alternative from docs/sentence-media-refs-design.md §8 D4: resolve a poster
for kind='video_cues' rows. Plugs into: stremio_library already caches a
poster column per show (MiningRepository stremio_library DDL +
loadStremioLibrary, StremioWrites.kt:74) and cue documents carry video_id,
so the renderer registry (U1) gains a video branch:
video_id -> base_id -> poster URL -> Glide. Open wrinkle to pin at pickup:
poster URLs are Stremio CDN links (expiry/caching behavior undocumented
from a foreign app) and rows can exist for shows not in the library cache
(movies are fine; deleted-show case) -> must degrade to the text card,
which is exactly what U1's unknown-kind fallback already guarantees.
