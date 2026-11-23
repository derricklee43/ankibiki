PARKED: STREMIO AUTO-SCRUB / WATCH-TIME INGESTION -- PICK-UP SUMMARY
=====================================================================
PARKED BY USER RULING 2026-11-23: "Let's go this route for now. Park the
idea of automatic scrubbing based off watching." The ACTIVE design is
docs/stremio-mining-console-design.md (manual Mining Console). This file
was pruned of the scrub machinery (watch-time capture spikes, event
transports, progress producers, superseded decision lists) and keeps:
  §0  verified facts, preserved verbatim as the evidence record -- this
      section is NOT to be rewritten, only appended to (dated corrections
      like 0.4's are the exception);
  §A  what was pruned and why;
  §B  pick-up notes: what a resumed auto-scrub design must re-answer;
  §C  the media-extraction design, which is DEFERRED (cards phase, console
      §11.3), NOT parked -- it survives here verbatim because it is
      orthogonal to the scrub question.
Original citations: Stremio's own source (stremio-core, stremio-web,
stremio-video, stremio-shell, cloned Nov 23 2026) and dated external notes.
Provenance: review-and-supersede of the third-party spike design
C:\Users\derri\Downloads\stremio_real_debrid_spike_design.txt (2026-11-23).
Nothing in this file is approved for implementation.

0. VERIFIED FACTS THAT REWRITE THE ORIGINAL ASSUMPTIONS
--------------------------------------------------------

0.1 The single-resume-slot problem: CONFIRMED, and worse than described.
    (Previous review cited CrossWatch, a third-party tracker. That was fair to
    challenge. These citations are from Stremio's own code.)

    a) One slot per series. "Continue Watching" is derived purely from
       libraryItem.state.time_offset > 0 (stremio-core
       src/models/continue_watching_preview.rs:59-85; src/types/library/
       library_item.rs:58). Each libraryItem has exactly one state.video_id and
       one state.time_offset. There is no per-episode history collection.
    b) 70% auto-advance. During playback, once cumulative time_watched >
       0.7 * duration (WATCHED_THRESHOLD_COEF, src/constants.rs:62), the player
       flags the episode watched, sets the watched bit, and on episode switch
       resets time_offset to 1 under the NEXT video's id
       (src/models/player.rs:604-681, advance_to_video library_item.rs:144-152;
       library_item_state_update player.rs:1489-1508). At session end,
       position > 0.9 * duration (CREDITS_THRESHOLD_COEF constants.rs:63) also
       zeroes the offset (item_state_update player.rs:1235+).
       CONSEQUENCE: a lazy poller never observes the true end position of any
       fully-watched episode. The last ~10-30% of every finished episode is
       erased from cloud state before the next sync.
    c) But time_offset is a monotonic high-water mark within a video:
       it is only written when the new time is GREATER (player.rs:650-653 --
       their comment: "if you seek forward, time will be < time_offset, this is
       the only thing we can guard against!"). Backward seeking never shrinks
       it. This is exactly the "watched up to here" semantic ingest needs --
       for the video that is currently active.
    d) Cloud writes during playback are debounced to every 180 seconds
       (PUSH_TO_LIBRARY_EVERY, player.rs:46), plus immediate pushes on
       pause/stop/episode-switch paths. So even a watch-time poller sees ~3 min
       granularity mid-session (self-heals, because of (c)), but a poller
       racing the 70% flag can see an already-advanced slot.
    e) Watched state is a base64 bitfield (state.watched) with NO per-episode
       timestamps. "Which episodes did I watch since the last sync?" is not
       directly answerable from one read.
    f) The salvage policy for the device route: store our own snapshot of the
       watched bitfield + offsets per video_id between polls; transitions
       (unwatched -> watched, or slot advance) observed by US mean "episode
       complete, ingest 100%". Episodes already watched before our first poll
       are unrecoverable by polling.
    [PARKING CONTEXT: the Mining Console removes the need for this salvage
    policy for TEXT mining -- the human declares scope per episode. The
    policy only resurfaces if scrub-based auto-ingestion is resumed.]

0.2 Desktop Stremio architecture (decides what "watch-time capture on Windows"
    can hook into):
    - The Windows client exists: stremio-shell = Qt/C++ wrapper embedding
      libmpv (mpv.cpp, vo=libmpv) + a QtWebEngine view of the stremio-web UI +
      a bundled Node "server.js" process (server-url.txt, v4.20.16).
    - NO external mpv IPC socket is configured (no input-ipc-server anywhere in
      stremio-shell; ShellVideo.js talks to QML in-process). So an external
      companion cannot read mpv properties (time-pos, sid, sub-delay) by design.
    - The player's true state IS reachable in-process by other means, unverified:
      (i) the desktop UI is Chromium-based (QtWebEngine): launching the app with
          QTWEBENGINE_CHROMIUM_FLAGS="--remote-debugging-port=9222" may expose
          CDP, same hooks as the browser build; (ii) server.js may expose local
          REST surface worth probing (it handled the old 11470 API).
    - stremio-web subtitle/audio selection state lives in the page
      (selectedSubtitlesTrackId, subtitlesDelay -> mpv "sid"/"sub-delay" per
      stremio-video ShellVideo.js:11-32). A browser extension (or CDP) reading
      the page gets GROUND TRUTH: which subtitle file was actually selected,
      its URL, the user's delay offset, and the stream URL the player is
      pulling (the RD link, minted from this machine's IP -- see 0.4).
    - CONSEQUENCE: watch-time capture's killer feature is not fresher offsets,
      it is subtitle ground truth. The lazy-requery design can never know which
      of "a bunch of versions" you picked, nor your offset; the player can.

0.3 Android media extraction -- the actual task surface (not "all of ffmpeg").
    Your instinct is right: this product needs exactly FIVE capabilities,
    pinned by the P4 contract:
      T1. HTTPS + Range random access to a remote MKV (redirects, TLS).
      T2. Demux Matroska; select a given video + audio track by id.
      T3. Decode ONE FRAME inside a cue's window (h264/hevc/av1/vp9).
      T4. Decode + re-encode ~1.5-4s of one audio track to a small clip.
      T5. Encode the still frame to webp/jpeg.
    NOT needed: subtitle rendering (captions drawn by app per your ruling),
    muxing anything, codec/transcode matrices, streaming servers.

    Three ways to do T1-T5 on Android, and the longevity question:
      (a) NATIVE PLATFORM APIS (Media3 et al.) -- no ffmpeg at all:
          - T3 has a ONE-CALL platform path: MediaMetadataRetriever
            .setDataSource(httpUrl) + .getFrameAtTime(t, seek flag), decoded
            by hardware codecs. (Accuracy flag vs nearest-keyframe: measure.)
          - T4 = MediaExtractor (also accepts http URLs) + MediaCodec decode
            + AAC encode over the window: ~150-250 lines, all platform APIs.
          - T1: platform HTTP stack handles TLS + Range transparently.
          - Longevity: EXCELLENT -- first-class APIs Google ships; hardware
            decoders (h264/hevc/av1 on your Pixel 10) = thermal/battery win.
          - Known unknowns to measure: RD host Range behavior via
            MediaExtractor; bandwidth waste on long-GOP AV1/HEVC when seeking;
            opus-in-matroska decode availability on-device (codec-dependent).
      (b) COMMUNITY FFMPEG-KIT FORK (ffmpegkit-maintained "8.1.7"):
          - Context: FFmpegKit retired Jan 6 2025; com.arthenica binaries
            pulled from Maven Central Apr 1 2025 -- the original dependency
            literally no longer resolves. The fork claims drop-in parity,
            SDK 35 + 16 KB pages.
          - Longevity: RISK = the packaging layer (single community
            maintainer rebuilding FFmpeg releases), not FFmpeg itself
            (immortal). You'd adopt the risk of that one person stopping.
          - Variant caveats: TLS requires the 'https'/'full' flavor (min
            lacks network crypto; verify against the fork's BUILD.md);
            libass is GPL/'full'-only and UNNEEDED post-ruling.
          - Full-fat APK cost per ABI is the tax; measure the real number.
      (c) SELF-BUILT MINIMAL FFMPEG .so + JNI:
          - Configure ONLY T1-T5: matroska demuxer, http+tls protocol, video
            decode via --enable-mediacodec (platform hardware h264/hevc INSIDE
            ffmpeg) or one software decoder, native aac encoder, webp encoder.
          - Longevity: YOUR ritual -- belongs in scripts/ next to the other
            corpus builders you already maintain. FFmpeg core never bit-rot;
            the NDK build recipe is what you'd redo on upgrades.
          - Smallest APK delta + LGPL-config achievable + no third party to
            disappear. Cost: you own the JNI shim.
    Constraint that kills the naive fourth option: an app cannot EXEC a
    binary it wrote to its own data dir (W^X / SELinux, targetSdk 29+) --
    "download the ffmpeg CLI and run it" is illegal on modern Android.
    ffmpeg must be linked in as a .so or the work goes through (a).
    Also pin formats per platform reality: Android has NO mp3 encoder
    (decode-only) and opus encoding is device-dependent; aac/m4a is the
    safe on-device output (existing repo audio is mp3 from the PC corpus
    build -- different producer, no conflict). 16 KB page compliance
    (Play, Nov 2025) gates which builds ship at all.
    Remaining spike-measured numbers: p50/p95 click-to-file latency per
    option; bytes pulled per slice (long-GOP AV1 may make "download the
    ~3-min neighborhood over Range and slice locally" beat remote seeking);
    APK delta per ABI; battery/thermal.

0.4 Real-Debrid facts that constrain any lazy-slice design:
    - Unrestricted links are IP-tracked server-side; multi-IP pulls are logged
      and are a documented account-flagging pattern. Correct pattern: the
      machine that pulls the bytes mints its own link (pass its ip; you need
      the infoHash, not a /downloads filename match).
      (Source: debridmediamanager notes on RD behavior, 2025-era.)
      [UPDATE 2026-11-23: the console doc's §5.5 identity spine makes
      infoHash(+fileIdx) the file-level key, sourced from addon stream
      objects and RD /torrents directly; the filename-match approach of the
      original design is dead.]
    - Stream links are short-lived (hours). "Expired link handling"
      is the common case, not the edge case. Re-mint, fallback to text-only,
      or cache media eagerly at export time -- must be designed.
    - RD API: 250 requests/minute globally (429 beyond; refusals count
      toward the limit), ETag/If-None-Match supported -> delta polling
      /downloads is cheap; click-time use and 30-60s loops are both safe.
      CORRECTION (2026-11-23, round 3): the earlier "strictly rate-limited"
      worry was wrong; and /downloads doubles as a timestamped ACCESS LOG
      (which files were pulled when -- never how far), making it the
      salvage counter 0.1f wants. `POST /unrestrict/link` has a documented
      remote=1 flag that lifts account-sharing protection for remote
      traffic -- an official (cost-unverified) answer to IP-binding.
      Track metadata is NOT in RD either: it lives in the file bytes, and
      every player (including Stremio's) enumerates it by probing the file.

0.5 Stremio API surface notes:
    - authKey via link.stremio.com v2 flow is unverified but plausible; the
      datastore shape (collection "libraryItem", fields state.{video_id,
      time_offset, duration, lastWatched, timesWatched, flaggedWatched,
      watched}) matches stremio-core's LibraryItem serde types. time_offset
      arrives in ms from web/desktop (useExternalPlayerCallback multiplies
      position by 1000); verify the Android client's unit before use.
    - The cloud API is unofficial, unversioned, and can change under us.
      Every spike pins captured request/response fixtures (repo convention).
    - [UPDATE 2026-11-23: link-flow, datastoreGet, and
      AddonCollectionGet (api.strem.io, the user's installed addons WITH
      their config URLs) are all first-class stremio-core call paths --
      see console doc §4 B/C. Addon discovery needs no hardcoding.]

A. WHAT WAS PRUNED FROM THIS DOC (and why)
--------------------------------------------
  Removed (cruft relative to the parked idea, or superseded):
    - P1 PROGRESS PRODUCERS (browser-extension watcher, desktop-CDP watcher,
      cloud-poller event contract): belonged to watch-time/auto scrubbing.
    - Spike 1 "watch-time capture on the laptop" (old S1.1-S1.4): the
      browser-extension ground-truth experiment. PARKED.
    - Spike 2 "device text ingestion pipeline" (old S2.1-S2.4): SUPERSEDED
      by the console's S0 series, which does the same API plumbing with
      human-declared scope (S1.4's RD-link test survives, folded into the
      console's S0-D2).
    - Event transports (old D8: NDJSON-drop / ntfy push / phone-pulls-PC-
      server): only needed if a laptop watcher exists; moot while parked.
      Recorded lesson worth keeping: "a server the phone calls" was always
      ONE OPTIONAL branch, ranked last; nothing in the active route uses it.
    - Old decision points D1-D5, D8 (capture target, ingestion posture,
      alignment source, salvage acceptability, media posture): unanswerable
      until the scrub idea is picked up again; re-derive then. D6/D7
      rulings survive verbatim in §C / console doc.

B. PICK-UP NOTES -- IF AUTO-SCRUB RESUMES
-------------------------------------------
  1) The console first de-risks almost everything: auth/library/addon
     plumbing (S0-A), watched-bitfield decode (S0-B), subtitle resolution
     (S0-C/S0-D tiers), the ingest seam + ledger + v14 ceremony (S0-E),
     background batching (S0-F). A resumed scrub design adds ONE thing that
     the console does not have: a trusted "watched until X" signal.
  2) The blocking facts have not changed and are settled (§0.1): Stremio
     cloud state is a resume pointer that the 70%/90% logic erases on
     completion; a lazy poller cannot reconstruct finished-episode tails;
     per-episode timestamps do not exist. Any resumed design therefore
     starts from exactly one of:
       (i) watch-time capture (laptop watcher: browser-extension route
           proven-by-spike or rejected; desktop CDP-flag route was never
           verified -- QTWEBENGINE_CHROMIUM_FLAGS hypothesis untested),
       (ii) observed-transition salvage with OUR snapshots (console M8
           already stores exactly these snapshots -- resume is cheap here),
       (iii) RD /downloads timestamps as the which-file-when ledger
           (console M9), combined with (i)/(ii) for how-far.
  3) Ground truth (which subtitle version + delay + audio track the player
     actually used) remains the one thing ONLY watch-time capture can
     provide; the console's confirm-once settings are the accepted
     substitute while parked.
  4) Unit/cadence measurements still owed before building: per-client
     time_offset units, the 180s push behavior observed live, whether
     datastoreGet tolerates a 30-60s loop from the phone without
     rate/behavioral surprises.
  5) Preserve the module discipline: resumed capture must emit INTO the
     console's existing contracts (subtitle bundle, ledger, settings) --
     it gets no second pipeline.

C. CARDS-WITH-MEDIA PHASE -- DEFERRED, NOT PARKED (console §11.3)
------------------------------------------------------------------
  Surviving slice contract (was P4; the media phase's request/result):
       request {utterance_id, cue window [t_start_ms, t_end_ms],
                audio_track_id (from console settings: ground truth when
                probed, hint when not), frame_at_ms (default window
                midpoint), caption_text (from the stored cue)}
            -> result {image_path, audio_path}
              or {status: link_expired | offline_backend | failed}.
       Backends: B-on-device (native platform path or linked-in ffmpeg --
       see 0.3's three-way bake-off), B-pc-laptop (desktop ffmpeg over LAN).
       Backend choice is per-utterance at click time, never baked into the
       DB. Identity comes from the console's §5.5 spine: cue -> videoId ->
       infoHash(+fileIdx) -> freshly minted link (0.4 rules), no filename
       regex anywhere.
       USER RULING 2026-11-23 (round 2, still binding): caption font and
       position do NOT matter. The two binding requirements: (i) the AUDIO
       SLICE matches the subtitle line, (ii) the frame comes from WITHIN
       the cue's shown window. Caption text is drawn by the app from the
       stored cue (libass/GPL path unnecessary).
  Export note (was P5): media must be on-device at export time
  (AnkiRepository.addMediaFromUri path); formats lean aac/m4a + webp,
  pinned by the old S3.4 playback test when the phase starts.
  Measurement plan when the phase starts (formerly Spike 3, unchanged):
   S3.1 Three on-device implementations of T1-T5 (0.3 surface) on identical
        inputs -- native (MediaMetadataRetriever.getFrameAtTime + Media-
        Extractor/MediaCodec decode->AAC), fork (ffmpegkit-maintained,
        only the T1-T5 command shapes), self-build (minimal configure +
        --enable-mediacodec as JNI .so). Axes for all three on a real
        device over real RD links: p50/p95 click-to-file latency; bytes
        pulled per slice; battery/thermal; correctness vs the S3.2 PC
        baseline; LONGEVITY (Google / one fork maintainer / you). Native
        is the only option that cannot vanish; fork and self-build exist
        to plug native's holes (e.g. opus-in-mkv decode gaps).
   S3.2 PC ffmpeg baseline (Windows): identical slices; latency, bytes,
        and the RD link lifecycle (mint-on-this-IP, reuse-after-N minutes,
        re-mint-by-infoHash).
   S3.3 Media-correctness per the ruling: audio slice audibly matches the
        stored cue on dub/sub releases (right aid; test hint vs ground-truth
        paths); frame lands inside the cue window.
   S3.4 Audio format: AnkiDroid playback test of m4a/opus-in-ogg/mp3.
   PASS: a backend reaches p95 <= ~2s click-to-media with <= ~50 MB per
   slice and aid-correct audio. KILL/fallback: all backends > 5s p95 or
   packaging cost disproportionate -> text cards with a deep-link into
   Stremio at the utterance timestamp instead.
