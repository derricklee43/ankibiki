# AnkiBiki data recovery

Status: **in-app export/restore workflow implemented and demonstrated** —
Settings (top-right) → *Export corpus backup (bundle)* / *Restore corpus from
bundle* on the lookup tab. Bundles are fully compatible with
`scripts/backup_bundle.py` in both directions (proved on an API 35 emulator
2026-11-10). Remaining pieces: photos-included export needs the device's
MediaStore readable (works automatically when reconnected), and the fresh-device
photo re-link helper.

Demo log (2026-11-10, second round, real corpus): Pixel snapshot
`backups/20261110-190438/mining.db` (50 photos / 214 items / 262 occurrences)
→ PC-built bundle → in-app restore on emulator → restored DB byte-identical to
the bundle's `mining.db`, manifest sha256 match, all three tables
content-hash-identical to the Pixel source, `integrity_check ok`, schema v3,
previous corpus preserved as `mining.db.pre-restore`. App-side export on the
emulator produced a bundle that `backup_bundle.py verify` accepts; its database
is content-identical to the source (byte-delta only from the WAL-checkpoint
header counter). Earlier same-day demos (tampered/emptied bundle rejection)
remain covered by the restore validation tests.

## What the corpus is, and where it lives

Everything the app derives lives in one SQLite database, `mining.db`
(`user_version` 3 as of writing):

| Table | Holds | Recovery-relevant columns |
| --- | --- | --- |
| `scraped_photos` | photo metadata + extracted text | `content_uri`, `date_added`, `language_tag`, `extracted_text`, `ocr_blocks_json`, `status`, `duplicate_of_photo_id`, `modified_by_user_flag` |
| `extracted_items` | dictionary words / grammar items | `is_hidden`, `visibility_state` (ACTIVE / ARCHIVED / HIDDEN), Anki link fields |
| `item_occurrences` | per-photo sentences/candidates with triage history | `state` (UNSEEN / SEEN / … review & deletion state), `modified_by_user_flag`, `sentence`, `created_at` |

Two copies of truth exist and must both be considered:

1. **The database** — the mined text, edits, visibility and review decisions.
   Small (hundreds of KB), fully contained in `mining.db`.
2. **The source images** — pixel data in the device's MediaStore, referenced
   from `scraped_photos.content_uri`. MediaStore IDs are **device-local**: they
   do not survive a reinstall or a new phone.

`scripts/backup_mining_db.py` (run automatically before instrumented tests)
protects copy 1 as raw snapshots in `backups/` — but only on this one machine,
which is why the roadmap demands an independent, verifiable, off-device copy.

## Recovery bundles

`scripts/backup_bundle.py` wraps a `mining.db` into a self-verifying zip:

```
manifest.json   format version, creation time/owner, user_version,
                sha256 + row counts per table, photo mode + per-photo sha256
mining.db       consistent snapshot (sqlite backup API, integrity-checked)
photos/<id>-*   optional — original image bytes keyed by media_store_id
```

Two photo modes, recorded explicitly in every bundle (roadmap requires this
distinction be made clear):

- `referenced` — images stay on-device; the bundle restores all *data about*
  photos but not the pixels.
- `included` — image bytes are embedded, so a wiped phone or new device can be
  restored completely (re-import steps below).

### Commands (pure stdlib Python — works from Windows or WSL)

```bash
# export the latest device snapshot into a bundle
python scripts/backup_bundle.py export --db backups/<ts>/mining.db --out-dir <dir>

# export including photos (files staged as <media_store_id>-<name>.<ext>)
python scripts/backup_bundle.py export --db backups/<ts>/mining.db \
    --out-dir <dir> --include-photos <staged-photo-dir>

python scripts/backup_bundle.py verify  --bundle <file.zip>
python scripts/backup_bundle.py restore --bundle <file.zip> --target <path>/mining.db \
    [--restore-photos-to <dir>] [--force]
python scripts/backup_bundle.py diff    --source <original.db> --target <restored.db>
python scripts/backup_bundle.py info    --bundle <file.zip>
```

Safety behavior, verified by tests (see Demonstration log):

- verify checks zip structure, format version, db sha256, `PRAGMA
  integrity_check`, required tables **and columns**, row counts vs manifest,
  and per-photo hashes;
- restore runs full verification *before* writing anything; an existing target
  is refused unless `--force` (with `--force` the old file is kept as
  `mining.db.pre-restore`, never silently overwritten);
- unsupported `user_version` / `format_version` / missing columns → bundle is
  reported as incompatible and rejected — the existing corpus is never touched.

## Restore into the app (device path)

The app opens `mining.db` from its private `databases/` dir. To restore:

1. Install the **debug** build, launch once so Room creates `mining.db`, then:
   `adb shell am force-stop com.derricklee.ankidict`
2. `python scripts/backup_bundle.py restore --bundle <zip> --target ./mining.db`
3. Push into place: `adb push mining.db /data/local/tmp/ && adb shell "run-as
   com.derricklee.ankidict sh -c 'cp /data/local/tmp/mining.db databases/mining.db'"`
   — only replaces the device db; keep the auto-generated pre-replace snapshot
   in `backups/` as rollback.
4. `diff` the pushed file against the bundle's db (pull it back the same way)
   and launch the app: photos, items, visibility and review states must match.

For a **fresh device / wiped install** with an `included` bundle, add:

- push `photos/` to `/sdcard/DCIM/AnkiBiki/` and trigger a media scan
  (`adb shell content insert` or `am broadcast MEDIA_SCANNER_SCAN_FILE`);
- MediaStore assigns *new* IDs, so `scraped_photos.media_store_id` /
  `content_uri` no longer point at the right image. Re-associate by matching
  the bundle manifest's per-photo `sha256`/filename against the re-imported
  files (IDs are positional only; the text data does not depend on them).
  A `scripts/relink_photos` helper for this is the remaining device-side task.

## Demonstration log (2026-11-10, no device attached)

Against `backups/20261110-082512/mining.db` (25 photos, 122 items,
136 occurrences — includes 98 SEEN / 38 UNSEEN states, ACTIVE/ARCHIVED/HIDDEN
visibility, 7 occurrence + 2 photo user-edit flags):

| # | Step | Result |
| --- | --- | --- |
| 1 | export → `referenced` bundle (22 KB) | ok, auto-verified |
| 2 | restore into isolated fresh db | ok |
| 3 | `diff` restored vs source | **all 3 tables byte-identical** (25/122/136 rows, every column) |
| 4 | 1-byte tamper of bundled db | rejected: sha256 mismatch + `integrity_check` malformed; nothing written |
| 5 | `format_version: 99` bundle | rejected: unsupported format; nothing written |
| 6 | restore over existing target without `--force` | refused; target hash unchanged |
| 7 | export from db missing `visibility_state` column | refused: incompatible schema, nothing exported |
| 8 | export/restore with photos included | 3 photo files round-tripped, sha256 identical; `diff` clean |
| 9 | two restores of same bundle to separate targets | byte-identical outputs |

## Isolated-install demonstration on an emulator (2026-11-10)

With the Pixel disconnected, the device path was proven on a local
Android 15 (API 35) emulator (`android-35/google_apis/x86_64`, WHPX-accelerated,
headless; driven from WSL through the Windows `adb.exe`):

1. Fresh debug install of the app; launched once (no `databases/` created —
   Room opens `mining.db` lazily).
2. `backup_bundle.py restore` of the real corpus bundle staged `mining.db`,
   pushed via `/data/local/tmp`, copied in with `run-as` (documented device
   path above).
3. Launched the app → mining tab rendered the restored corpus: extracted
   words with occurrence counts and frequency ranks (無念 1回·N3 #726, 最期
   4回·N3 #344, 陸 with stored deck name `01 NihongoShark…`). The grammar
   section showed "no data" — faithful to the source (the snapshot contains
   122 WORD items, 0 GRAMMAR).
4. Pulled the app's live `mining.db` back (after force-stop) and `diff`-ed
   against the source snapshot: byte-identical, integrity ok.
5. Ran `scripts/backup_mining_db.py` against the emulator end-to-end:
   snapshot created (`backups/20261110-095903`), verified, and diffed
   identical to source — the pre-test backup guard works on any adb device,
   not just the phone.

Known emulator gap: photo thumbnails show placeholders because the image
bytes live in the Pixel's MediaStore; URIs in the restored DB don't resolve
there. Covered by `included` bundles + the relink step once a device is
attached.

## Off-device copies

The bundle is one small zip — copy it anywhere independent of this machine and
of the phone (USB stick, cloud drive, second PC). Suggested habit: after any
serious mining session, export a bundle and drop it into a synced folder
(`--out-dir` can point straight at e.g. a Dropbox/OneDrive directory).
Keep at least one bundle older than your current work, so a bad sync is
recoverable.

## Known gaps (open)

- ~~In-app export/restore UI~~ — done: RecoveryManager + Settings menu entries
  (SAF picker, confirmation dialog, self-relaunch via CLEAR_TASK; Android 15
  blocks background relaunch, so the app restarts itself from the foreground).
- `scripts/relink_photos` for fresh-device remap (step 8 of device path). The
  in-app restore stages `included` bundle photos to `files/recovery-photos/`
  with `photos-map.json` and leaves MediaStore registration for this helper.
- Device-staged photo pull (`backups/` snapshots contain the db only; the
  in-app export embeds photos whenever MediaStore can read them).
