#!/usr/bin/env python3
"""Snapshot the phone's mining.db into <repo>/backups/ before instrumented tests.

Context: /data/data/<pkg>/databases/mining.db on the phone is the ONLY copy of
the mined corpus + triage decisions. Android deletes app data on uninstall --
which is how it was once wiped by an instrumented-test run. The
android.injected.androidTest.leaveApksInstalledAfterRun flag in gradle.properties
is layer 1; this script, wired as a dependsOn of every gradle-initiated wipe
task (connectedDebugAndroidTest and the uninstall* tasks) in
app/build.gradle.kts, is layer 2.

Exit contract (the gradle task does NOT ignore exit values):
  0  backup created, OR genuinely nothing to protect (no device attached / app
     not installed / mining.db never created) -- skipped with a clear message.
  1  a backup was DUE and failed (device attached but pull/verify failed, adb
     missing, device unauthorized...) -- the test run must be blocked.

Snapshots land in backups/<YYYYmmdd-HHMMSS>/mining.db (plus the rollback
journal when the device has a non-empty one), matching the existing manual
backup convention. Only the newest MINING_BACKUP_KEEP timestamped dirs are
kept (default 20); anything else in backups/ is never touched.
"""

import os
import re
import shutil
import sqlite3
import subprocess
import sys
import time
from datetime import datetime
from pathlib import Path

PACKAGE = "com.derricklee.ankidict"
DB_NAME = "mining.db"
REPO_ROOT = Path(__file__).resolve().parent.parent
BACKUP_DIR = REPO_ROOT / "backups"
KEEP = int(os.environ.get("MINING_BACKUP_KEEP", "20"))
TS_DIR_RE = re.compile(r"^\d{8}-\d{6}(-\d+)?$")


def log(level, msg):
    print(f"{level}: {msg}", flush=True)


def resolve_adb():
    """$ADB > $ANDROID_HOME/$ANDROID_SDK_ROOT > local.properties sdk.dir >
    ~/Android/Sdk > PATH. Skips Windows-style sdk.dir values (unusable here)."""
    candidates = []
    if os.environ.get("ADB"):
        candidates.append(Path(os.environ["ADB"]))
    for var in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        if os.environ.get(var):
            candidates.append(Path(os.environ[var]) / "platform-tools" / "adb")
    lp = REPO_ROOT / "local.properties"
    if lp.is_file():
        m = re.search(r"^sdk\.dir=(.+)$", lp.read_text(), re.M)
        if m:
            p = m.group(1).strip().replace("\\:", ":").replace("\\=", "=")
            if not re.match(r"^[A-Za-z]:", p):
                candidates.append(Path(os.path.expanduser(p)) / "platform-tools" / "adb")
    candidates.append(Path.home() / "Android" / "Sdk" / "platform-tools" / "adb")
    w = shutil.which("adb")
    if w:
        candidates.append(Path(w))
    for c in candidates:
        if Path(c).is_file():
            return str(c)
    return None


def adb_shell(adb, serial, args, timeout=60):
    cmd = [adb] + (["-s", serial] if serial else []) + ["shell"] + args
    return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)


def list_devices(adb):
    """Returns (usable_serials, other_states) from `adb devices`."""
    out = subprocess.run([adb, "devices"], capture_output=True, text=True, timeout=30).stdout
    usable, unusable = [], []
    for line in out.splitlines()[1:]:
        parts = line.strip().split("\t")
        if len(parts) == 2:
            if parts[1] == "device":
                usable.append(parts[0])
            else:
                unusable.append(f"{parts[0]} ({parts[1]})")
    return usable, unusable


def pull_db_file(adb, serial, rel_path, dest):
    """exec-out (no pty => no CRLF mangling) + run-as (debuggable app's private dir)."""
    cmd = [adb, "-s", serial, "exec-out", "run-as", PACKAGE, "cat", rel_path]
    with open(dest, "wb") as f:
        r = subprocess.run(cmd, stdout=f, stderr=subprocess.PIPE, timeout=300)
    return r.returncode == 0 and os.path.getsize(dest) > 0


def verify_snapshot(db_file):
    """Open with sqlite3 (this also replays/rolls back a stale journal if present)
    and require a clean integrity_check."""
    try:
        con = sqlite3.connect(str(db_file), timeout=10)
        try:
            rows = con.execute("PRAGMA integrity_check").fetchall()
        finally:
            con.close()
        return len(rows) == 1 and rows[0][0] == "ok"
    except sqlite3.Error as e:
        log("WARN", f"snapshot failed to open/verify: {e}")
        return False


def unique_backup_dir(base_ts):
    d = BACKUP_DIR / base_ts
    n = 2
    while d.exists():
        d = BACKUP_DIR / f"{base_ts}-{n}"
        n += 1
    d.mkdir(parents=True)
    return d


def force_stop(adb, serial):
    r = adb_shell(adb, serial, ["am", "force-stop", PACKAGE])
    if r.returncode != 0:
        log("ERROR", f"am force-stop failed: {r.stderr.strip()}")
        return False
    time.sleep(0.5)  # let the process actually die before the pull
    return True


def prune(root, keep):
    """Delete timestamped snapshot dirs beyond the newest `keep`. Other names untouched."""
    if not root.is_dir():
        return []
    dirs = sorted((d for d in root.iterdir() if d.is_dir() and TS_DIR_RE.match(d.name)),
                  key=lambda d: d.name, reverse=True)
    removed = []
    for d in dirs[keep:]:
        shutil.rmtree(d, ignore_errors=True)
        removed.append(d.name)
    return removed


def main():
    adb = resolve_adb()
    if not adb:
        log("ERROR", "adb not found (install platform-tools or set ADB=/path/to/adb); "
                     "cannot snapshot the phone's mining.db before this test run")
        return 1

    usable, unusable = list_devices(adb)
    if not usable:
        if not unusable:
            log("INFO", "skipped: no device attached -- nothing to back up")
            return 0
        log("ERROR", f"device attached but not ready: {unusable} -- unlock the phone and "
                     "accept USB debugging, or unplug it to intentionally skip the backup")
        return 1

    serial = os.environ.get("ANDROID_SERIAL")
    if serial:
        if serial not in usable:
            log("ERROR", f"ANDROID_SERIAL={serial} is not among the usable devices {usable}")
            return 1
    elif len(usable) > 1:
        log("ERROR", f"multiple devices attached: {usable} -- set ANDROID_SERIAL to the phone "
                     "holding mining.db; guessing which one to trust is not acceptable for a backup")
        return 1
    else:
        serial = usable[0]

    installed = adb_shell(adb, serial, ["pm", "list", "packages", PACKAGE])
    if installed.returncode != 0:
        log("ERROR", f"device {serial} is usable but pm list packages failed: "
                     f"{installed.stderr.strip()} -- cannot prove there is nothing to back up")
        return 1
    if not any(line.strip() == f"package:{PACKAGE}" for line in installed.stdout.splitlines()):
        log("INFO", f"skipped: {PACKAGE} is not installed on {serial} -- nothing to back up")
        return 0

    # App is installed -> a backup is DUE. Anything preventing it from here on
    # must exit 1 so the instrumented-test run cannot proceed.
    listing = adb_shell(adb, serial, ["run-as", PACKAGE, "ls", "-l", "databases/"])
    if listing.returncode != 0:
        if "No such file" in (listing.stderr + listing.stdout):
            log("INFO", "skipped: databases/ does not exist yet -- mining.db has never been created")
            return 0
        log("ERROR", f"run-as failed on {serial} (app not debuggable -- release build installed?): "
                     f"{listing.stderr.strip()}")
        return 1

    sizes = {}
    for line in listing.stdout.splitlines():
        parts = line.split()
        if len(parts) >= 6:
            sizes[parts[-1]] = int(parts[4]) if parts[4].isdigit() else 0
    if DB_NAME not in sizes:
        log("INFO", f"skipped: {DB_NAME} not created yet on {serial}")
        return 0
    journal_name = DB_NAME + "-journal"
    journal_bytes = sizes.get(journal_name, 0)

    if not force_stop(adb, serial):
        return 1

    for attempt in (1, 2):
        snap_dir = unique_backup_dir(datetime.now().strftime("%Y%m%d-%H%M%S"))
        ok = pull_db_file(adb, serial, f"databases/{DB_NAME}", snap_dir / DB_NAME)
        if ok and journal_bytes > 0:
            # Copy the rollback journal alongside so sqlite can complete/finalize
            # the transaction on verify, same layout as the existing manual backups.
            ok = pull_db_file(adb, serial, f"databases/{journal_name}", snap_dir / journal_name)
            if not ok:
                log("WARN", f"{journal_name} ({journal_bytes} B uncommitted) could not be pulled")
        if ok and verify_snapshot(snap_dir / DB_NAME):
            log("INFO", f"backup created: {snap_dir / DB_NAME} "
                        f"({(snap_dir / DB_NAME).stat().st_size} bytes, integrity ok, device {serial})")
            removed = prune(BACKUP_DIR, KEEP)
            for name in removed:
                log("INFO", f"pruned old snapshot: backups/{name}")
            return 0
        log("WARN", f"snapshot attempt {attempt} failed verification; retrying from a clean stop")
        shutil.rmtree(snap_dir, ignore_errors=True)
        if attempt == 1:
            time.sleep(1)
            if not force_stop(adb, serial):
                return 1

    log("ERROR", "could not produce a verified backup of mining.db -- test run blocked")
    return 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as e:  # any unexpected failure must block the test run
        log("ERROR", f"backup failed: {e}")
        sys.exit(1)
