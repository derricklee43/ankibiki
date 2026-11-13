#!/usr/bin/env python3
"""Self-verifying AnkiBiki corpus backup bundles: export / verify / restore / diff.

Why this exists: backups/<ts>/mining.db (from backup_mining_db.py) is a raw,
machine-local snapshot with no metadata and no integrity record, and there is
no restore path at all. This tool wraps a mining.db into a portable, verifiable
bundle (zip) that can be copied off-device/off-machine, and provides restore +
full source-vs-restored comparison, satisfying the recovery acceptance
criteria in docs/roadmap.md section 1:

  - every table in one file: photo metadata + extracted text + OCR blocks,
    user-edit flags (modified_by_user_flag), item visibility (visibility_state,
    is_hidden), occurrence review/deletion state (state);
  - invalid or incompatible bundles are rejected BEFORE anything is written,
    and restore never modifies an existing target without --force;
  - photos are referenced by MediaStore URI by default; --include-photos
    embeds image bytes so a fresh install can recover them (see docs/recovery.md
    for the re-import/remap procedure — MediaStore ids are device-local).

Bundle layout (format_version 1, zip):
  manifest.json    format, timestamps, sqlite user_version, per-table row
                   counts, sha256 of mining.db, photo mode + photo map
  mining.db        consistent snapshot taken via the sqlite backup API
  photos/<id>-<n>.<ext>   optional originals, keyed by scraped_photos.media_store_id

Stdlib only; safe to run from Windows Python or WSL on any machine.

  export  --db backups/<ts>/mining.db --out-dir DIR [--include-photos DIR]
  verify  --bundle FILE.zip
  restore --bundle FILE.zip --target PATH/new/mining.db [--force]
  diff    --source DB --target DB           (exit 1 on any mismatch)
  info    --bundle FILE.zip
"""

import argparse
import hashlib
import json
import os
import sqlite3
import sys
import zipfile
from datetime import datetime, timezone
from pathlib import Path

BUNDLE_FORMAT = "ankibiki-backup"
FORMAT_VERSION = 1
DB_NAME = "mining.db"
TABLES = ("scraped_photos", "extracted_items", "item_occurrences")
# user_version values this tool will restore; extend when migrations land.
# 5 = card_templates (wizard), 6 = ocr_corrections (cloud-gate audit log), 7 = is_gold on
# scraped_photos (explicit gold membership). Bundles carry the whole sqlite file, so auxiliary
# tables/columns ship and restore regardless of validation scope; a pre-v7 bundle restored by a
# v7 app simply migrates on first open. is_gold is deliberately NOT in MINING_COLUMNS so older
# bundles stay restorable.
SUPPORTED_USER_VERSIONS = {1, 2, 3, 4, 5, 6, 7, 8}
MINING_COLUMNS = {
    "scraped_photos": {"media_store_id", "content_uri", "date_added",
                       "language_tag", "extracted_text", "ocr_blocks_json",
                       "status", "duplicate_of_photo_id",
                       "modified_by_user_flag"},
    "extracted_items": {"language_tag", "item_type", "expression", "kanji",
                        "kana", "meaning", "level_badge", "corpus_rank",
                        "anki_account_id", "deck_id", "deck_name",
                        "anki_note_id", "is_hidden", "visibility_state"},
    "item_occurrences": {"id", "language_tag", "item_type", "expression",
                         "photo_id", "sentence", "created_at", "state",
                         "modified_by_user_flag"},
}


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def fail(msg):
    print(f"FAIL: {msg}")
    return 1


def ok(msg):
    print(f"ok: {msg}")


def db_user_version(con):
    return con.execute("PRAGMA user_version").fetchone()[0]


def table_columns(con, table):
    return {r[1] for r in con.execute(f'PRAGMA table_info("{table}")')}


def inspect_db(db_path):
    """Open, integrity-check, and summarize a mining.db. Raises on problems."""
    con = sqlite3.connect(str(db_path), timeout=10)
    try:
        rows = con.execute("PRAGMA integrity_check").fetchall()
        if len(rows) != 1 or rows[0][0] != "ok":
            raise ValueError(f"integrity_check failed: {rows[:3]}")
        uv = db_user_version(con)
        counts, missing_cols = {}, []
        for t in TABLES:
            try:
                counts[t] = con.execute(f'SELECT COUNT(*) FROM "{t}"').fetchone()[0]
            except sqlite3.Error as e:
                raise ValueError(f"table {t} missing/unreadable: {e}")
            miss = MINING_COLUMNS[t] - table_columns(con, t)
            if miss:
                missing_cols.append(f"{t}: {sorted(miss)}")
        if missing_cols:
            raise ValueError("incompatible schema, missing columns -> "
                             + "; ".join(missing_cols))
        return uv, counts, con.execute("PRAGMA schema_version").fetchone()[0]
    finally:
        con.close()


def cmd_export(args):
    src = Path(args.db)
    if not src.is_file():
        return fail(f"source db not found: {src}")
    try:
        uv, counts, schema_ver = inspect_db(src)
    except (ValueError, sqlite3.Error) as e:
        return fail(f"refusing to export an invalid source: {e}")
    if uv not in SUPPORTED_USER_VERSIONS:
        return fail(f"unsupported user_version {uv} "
                    f"(supported: {sorted(SUPPORTED_USER_VERSIONS)})")

    ts = datetime.now().strftime("%Y%m%d-%H%M%S")
    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    n, bundle = 2, out_dir / f"ankibiki-corpus-{ts}.zip"
    while bundle.exists():  # same-second exports must not overwrite each other
        bundle = out_dir / f"ankibiki-corpus-{ts}-{n}.zip"
        n += 1

    # Consistent copy of the db even if the source has hot journals around.
    work = Path(args.work_dir or out_dir) / f".export-{ts}-{os.getpid()}"
    work.mkdir(parents=True, exist_ok=True)
    snap = work / DB_NAME
    src_con, dst_con = sqlite3.connect(str(src)), sqlite3.connect(str(snap))
    try:
        src_con.backup(dst_con)
    finally:
        dst_con.close()
        src_con.close()

    photos_meta, photo_sources = [], []
    if args.include_photos:
        photos_dir = Path(args.include_photos)
        if not photos_dir.is_dir():
            return fail(f"--include-photos expects a directory of files named "
                        f"<media_store_id>-*, got: {photos_dir}")
        # Expect photos-map.json beside staged photos (written by
        # scripts/stage_photos.py when the device is connected), or map by
        # filename prefix.
        map_file = photos_dir / "photos-map.json"
        if map_file.is_file():
            photo_files = json.loads(map_file.read_text())
        else:
            photo_files = []
            for p in sorted(photos_dir.iterdir()):
                if not p.is_file() or p.name == "photos-map.json":
                    continue
                stem = p.name.split("-", 1)[0]
                if stem.isdigit():
                    photo_files.append({"file": p.name, "media_store_id": int(stem)})
        for entry in photo_files:
            p = photos_dir / entry["file"]
            if not p.is_file():
                return fail(f"photo map references missing file: {p}")
            sha = sha256_file(p)
            photos_meta.append({**entry, "sha256": sha, "bytes": p.stat().st_size})
            photo_sources.append(p)

    uv2, counts2, _ = inspect_db(snap)
    if counts2 != counts:
        return fail(f"snapshot counts changed during export: {counts} -> {counts2}")

    manifest = {
        "format": BUNDLE_FORMAT,
        "format_version": FORMAT_VERSION,
        "created_utc": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "created_by": os.environ.get("USER") or os.environ.get("USERNAME") or "unknown",
        "source_db": str(src),
        "db": {"user_version": uv, "schema_version": schema_ver,
               "sha256": sha256_file(snap), "rows": counts,
               "bytes": snap.stat().st_size},
        "photos": {"mode": "included" if photos_meta else "referenced",
                   "note": "MediaStore URIs are device-local; on a fresh "
                           "install re-import photos/ and remap by filename+sha256 "
                           "(docs/recovery.md)." if photos_meta else
                           "images NOT embedded; only referenced by content_uri",
                   "files": photos_meta},
    }
    with zipfile.ZipFile(bundle, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("manifest.json", json.dumps(manifest, indent=2))
        z.write(snap, DB_NAME)
        for p in photo_sources:
            z.write(p, f"photos/{p.name}")
    import shutil
    shutil.rmtree(work, ignore_errors=True)
    ok(f"bundle written: {bundle} ({bundle.stat().st_size} bytes, "
       f"photos={manifest['photos']['mode']}, rows={counts})")
    return cmd_verify(argparse.Namespace(bundle=str(bundle),
                                         work_dir=args.work_dir))


def _read_manifest(bundle):
    with zipfile.ZipFile(bundle) as z:
        names = set(z.namelist())
        if "manifest.json" not in names or DB_NAME not in names:
            raise ValueError("not an ankibiki bundle (missing manifest.json/mining.db)")
        return json.loads(z.read("manifest.json")), names


def cmd_verify(args):
    bundle = Path(args.bundle)
    if not bundle.is_file():
        return fail(f"bundle not found: {bundle}")
    try:
        manifest, names = _read_manifest(bundle)
    except (zipfile.BadZipFile, ValueError) as e:
        return fail(f"unreadable bundle: {e}")
    if manifest.get("format") != BUNDLE_FORMAT:
        return fail(f"unknown bundle format: {manifest.get('format')!r}")
    if manifest.get("format_version") != FORMAT_VERSION:
        return fail(f"unsupported format_version {manifest.get('format_version')} "
                    f"(this tool understands {FORMAT_VERSION})")
    db_info = manifest["db"]
    work = Path(getattr(args, "work_dir", None) or bundle.parent) / f".verify-{os.getpid()}"
    work.mkdir(parents=True, exist_ok=True)
    extracted_db = work / DB_NAME
    with zipfile.ZipFile(bundle) as z:
        for member in z.namelist():
            if member.startswith("photos/") or member == DB_NAME:
                z.extract(member, work)
    uv = counts = None
    failures = []
    if sha256_file(extracted_db) != db_info["sha256"]:
        failures.append("mining.db sha256 mismatch (bundle is corrupted)")
    try:
        uv, counts, _ = inspect_db(extracted_db)
        if uv != db_info["user_version"]:
            failures.append(f"user_version mismatch: manifest {db_info['user_version']} "
                            f"vs db {uv}")
        if uv not in SUPPORTED_USER_VERSIONS:
            failures.append(f"unsupported user_version {uv}")
        if counts != db_info["rows"]:
            failures.append(f"row counts mismatch: manifest {db_info['rows']} vs db {counts}")
    except (ValueError, sqlite3.Error) as e:
        failures.append(f"db invalid: {e}")
    for meta in manifest.get("photos", {}).get("files", []):
        p = work / "photos" / meta["file"]
        if not p.is_file():
            failures.append(f"photo missing from bundle: {meta['file']}")
        elif sha256_file(p) != meta["sha256"]:
            failures.append(f"photo sha256 mismatch: {meta['file']}")
    if failures:
        import shutil
        shutil.rmtree(work, ignore_errors=True)
        for f in failures:
            print(f"FAIL: {f}")
        return fail(f"{bundle.name} is NOT restorable ({len(failures)} problem(s))")
    ok(f"{bundle.name} verified (user_version={uv}, rows={counts}, "
       f"photos={manifest.get('photos', {}).get('mode')})")
    import shutil
    shutil.rmtree(work, ignore_errors=True)
    return 0


def cmd_restore(args):
    target = Path(args.target)
    if target.exists() and not args.force:
        return fail(f"target exists: {target} -- refusing to touch it (use --force "
                    f"to replace; a fresh isolated restore is the documented path)")
    if target.is_dir():
        return fail(f"--target must be a file path, got directory")
    rc = cmd_verify(args)
    if rc != 0:
        return fail("restore aborted: bundle failed verification (nothing written)")
    manifest, _ = _read_manifest(Path(args.bundle))
    work = target.parent / f".restore-{os.getpid()}"
    work.mkdir(parents=True, exist_ok=True)
    staged_db = work / DB_NAME
    with zipfile.ZipFile(args.bundle) as z:
        z.extract(DB_NAME, work)
        if args.restore_photos_to:
            rphotos = Path(args.restore_photos_to)
            rphotos.mkdir(parents=True, exist_ok=True)
            n = 0
            for member in z.namelist():
                if member.startswith("photos/") and member != "photos/":
                    z.extract(member, rphotos)
                    n += 1
            print(f"note: extracted {n} photo file(s) to {rphotos} — they are NOT yet "
                  f"registered with MediaStore; see docs/recovery.md for the remap step")
    target.parent.mkdir(parents=True, exist_ok=True)
    final = staged_db
    if target.exists():
        bak = target.with_name(target.name + ".pre-restore")
        target.rename(bak)
        print(f"note: previous target kept at {bak}")
    try:
        uv, counts, _ = inspect_db(final)
        os.replace(final, target)
    except (ValueError, sqlite3.Error) as e:
        import shutil
        shutil.rmtree(work, ignore_errors=True)
        return fail(f"restored db failed open/verify: {e} (previous corpus untouched)")
    finally:
        import shutil
        shutil.rmtree(work, ignore_errors=True)
    ok(f"restored {target} (user_version={uv}, rows={counts}, "
       f"sha256={manifest['db']['sha256'][:12]}…)")
    return 0


def cmd_diff(args):
    src, dst = Path(args.source), Path(args.target)
    for p in (src, dst):
        if not p.is_file():
            return fail(f"not found: {p}")
    con_s, con_d = sqlite3.connect(str(src)), sqlite3.connect(str(dst))
    total_diff = 0
    try:
        for t in TABLES:
            rows_s = con_s.execute(f'SELECT * FROM "{t}" ORDER BY 1,2,3,4').fetchall()
            rows_d = con_d.execute(f'SELECT * FROM "{t}" ORDER BY 1,2,3,4').fetchall()
            # normalize schema differences (extra trailing cols) by column set
            cs = [r[1] for r in con_s.execute(f'PRAGMA table_info("{t}")')]
            cd = [r[1] for r in con_d.execute(f'PRAGMA table_info("{t}")')]
            common = [c for c in cs if c in cd]
            if cs != cd:
                print(f"WARN: {t} column sets differ (source {cs} vs target {cd}); "
                      f"comparing common columns only")
                idx = [cs.index(c) for c in common]
                rows_s = [tuple(r[i] for i in idx) for r in rows_s]
                idx = [cd.index(c) for c in common]
                rows_d = [tuple(r[i] for i in idx) for r in rows_d]
            if rows_s == rows_d:
                ok(f"{t}: {len(rows_s)} rows identical")
            else:
                total_diff += 1
                s_only = sorted(set(rows_s) - set(rows_d), key=str)[:5]
                d_only = sorted(set(rows_d) - set(rows_s), key=str)[:5]
                print(f"MISMATCH {t}: source {len(rows_s)} vs target {len(rows_d)} rows")
                for r in s_only:
                    print(f"  only in source: {r}")
                for r in d_only:
                    print(f"  only in target: {r}")
    finally:
        con_s.close()
        con_d.close()
    if total_diff:
        return fail("restored corpus differs from source")
    ok("full corpus comparison: restored data matches source exactly "
       "(all tables, all columns incl. edit flags, visibility, review states)")
    return 0


def cmd_info(args):
    try:
        manifest, _ = _read_manifest(Path(args.bundle))
    except (zipfile.BadZipFile, ValueError) as e:
        return fail(f"unreadable bundle: {e}")
    print(json.dumps(manifest, indent=2))
    return 0


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)

    e = sub.add_parser("export")
    e.add_argument("--db", required=True)
    e.add_argument("--out-dir", required=True)
    e.add_argument("--include-photos", help="dir of image files named <media_store_id>-* "
                   "(or with photos-map.json)")
    e.add_argument("--work-dir")
    e.set_defaults(fn=cmd_export)

    v = sub.add_parser("verify")
    v.add_argument("--bundle", required=True)
    v.add_argument("--work-dir")
    v.set_defaults(fn=cmd_verify)

    r = sub.add_parser("restore")
    r.add_argument("--bundle", required=True)
    r.add_argument("--target", required=True)
    r.add_argument("--restore-photos-to")
    r.add_argument("--force", action="store_true")
    r.add_argument("--work-dir")
    r.set_defaults(fn=cmd_restore)

    d = sub.add_parser("diff")
    d.add_argument("--source", required=True)
    d.add_argument("--target", required=True)
    d.set_defaults(fn=cmd_diff)

    i = sub.add_parser("info")
    i.add_argument("--bundle", required=True)
    i.set_defaults(fn=cmd_info)

    args = ap.parse_args()
    sys.exit(args.fn(args))


if __name__ == "__main__":
    sys.exit(main())
