#!/usr/bin/env bash
# SPDX-License-Identifier: MPL-2.0
# Frame-timing harness for Compose performance work.
#
# Drives a scripted interaction on a connected device and reports the frame
# statistics gfxinfo collected for it, so before/after claims rest on numbers
# instead of impressions. Release builds only: debug builds are not a meaningful
# performance target, and LibVLC is deliberately degraded on them.
#
#   scripts/measure-jank.sh --device <serial> --scenario <name> [--runs N]
#   scripts/measure-jank.sh --device <serial> --scenario <name> --metric memory
#   scripts/measure-jank.sh --list
#
# Results are written under .local/perf/ (git-ignored working evidence). Restate
# anything that informs a decision in a tracked file.
set -euo pipefail

PATH="$PATH:$HOME/Library/Android/sdk/platform-tools"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PERF_DIR="$REPO_ROOT/.local/perf"
CONFIG="$PERF_DIR/config.env"

MOBILE_PKG="com.jellyscope"
MOBILE_ACTIVITY="com.jellyscope/.android.MainActivity"
TV_PKG="com.jellyscope.tv"

RUNS=5
PHASE="${PHASE:-baseline}"

die() { echo "error: $*" >&2; exit 1; }
note() { echo "  · $*" >&2; }

usage() {
    cat >&2 <<'USAGE'
usage: measure-jank.sh --device <serial> --scenario <name> [--runs N] [--phase label]
                       [--metric frames|memory]
       measure-jank.sh --list

Scenarios (mobile): player-controls-toggle player-strip-scroll player-debug-scroll
                    player-idle library-grid-scroll library-recommended-scroll
Scenarios (tv):     tv-grid-focus

--metric memory reports total PSS and Java/native heap instead of frame timing.
It exists for item 4.4 (poster decode size), which frame timing cannot answer.
Use it A/B — same scenario, two builds — because only the difference is meaningful.
USAGE
}

# --- device helpers ---------------------------------------------------------

sh_() { adb -s "$DEVICE" shell "$@"; }

screen_size() { sh_ wm size | awk -F' ' '{print $NF}' | tr -d '\r'; }

# A throttled or power-saving device produces numbers that mean nothing, so the
# state is recorded with every result and a bad state aborts the run.
device_profile() {
    local w_h battery thermal
    w_h="$(screen_size)"
    battery="$(sh_ settings get global low_power | tr -d '\r')"
    thermal="$(sh_ dumpsys thermalservice 2>/dev/null | grep -m1 'Thermal Status' | tr -d '\r' || true)"
    echo "device=$DEVICE size=$w_h android=$(sh_ getprop ro.build.version.release | tr -d '\r')"
    echo "low_power=$battery ${thermal:-thermal=unknown}"
    echo "package=$PKG versionName=$(sh_ dumpsys package "$PKG" | grep -m1 versionName | tr -d ' \r')"
    [ "$battery" = "1" ] && die "battery saver is on — results would be meaningless"
    return 0
}

resumed_activity() {
    sh_ dumpsys activity activities | grep -m1 'topResumedActivity' | tr -d '\r'
}

# Assert the intended surface is actually on screen. A harness that silently
# measures the wrong screen would poison every downstream decision, so a failed
# assertion discards the run rather than reporting it.
#
# The needle is an extended regex matched against the whole dump, so a scenario
# can accept either of two legitimate states (e.g. the play/pause button, whose
# description depends on whether playback happens to be running at dump time).
# It must be a *positive* assertion: "the dump contains no text" is consistent
# with the player being up, but equally consistent with a blank screen.
assert_screen() {
    local needle="$1" dump
    sh_ uiautomator dump /sdcard/jank-ui.xml >/dev/null 2>&1 || return 1
    dump="$(sh_ cat /sdcard/jank-ui.xml 2>/dev/null || true)"
    grep -qE "$needle" <<<"$dump"
}

# The player scenarios cannot be asserted from the view hierarchy: with the
# controls auto-hidden the tree carries no text at all, and holding them open
# long enough to dump would change what is being measured. The MediaSession is
# a positive assertion of the thing that actually matters — this package is
# playing, and the position is advancing — and it needs no idle window.
assert_media_playing() {
    local line pos
    line="$(sh_ dumpsys media_session 2>/dev/null | tr -d '\r' \
        | grep -A10 "package=$PKG\$" | grep -m1 'state=PlaybackState')"
    grep -q 'state=PLAYING' <<<"$line" || return 1
    pos="$(sed -nE 's/.*position=([0-9]+).*/\1/p' <<<"$line")"
    [ -n "$pos" ] && [ "$pos" -gt 0 ]
}

# Assertions are given time to become true before a run is discarded. Reaching
# the intended state is not instantaneous — the pinned item resumes mid-file and
# transcodes, so PLAYING can be 20s after the tap — and discarding a run that was
# merely still starting would throw away good measurements. What is NOT allowed
# is measuring without the state ever holding.
ASSERT_TIMEOUT_S=40

wait_for() {
    local deadline=$(( SECONDS + ASSERT_TIMEOUT_S ))
    while [ "$SECONDS" -lt "$deadline" ]; do
        "$@" && return 0
        sleep 2
    done
    return 1
}

# Returns the failed assertion's name, or nothing if the state is as expected.
assert_state() {
    if [ "$ASSERT_MEDIA" = 1 ] && ! wait_for assert_media_playing; then
        echo "media_session PLAYING"
        return
    fi
    if [ -n "$ASSERT" ] && ! wait_for assert_screen "$ASSERT"; then
        echo "ui node $ASSERT"
    fi
}

launch_cold() {
    sh_ am force-stop "$PKG"
    sleep 1
    # The TV app declares only a leanback launcher, so the phone category finds
    # no activity there and would leave the app unlaunched.
    sh_ monkey -p "$PKG" -c "$LAUNCH_CATEGORY" 1 >/dev/null 2>&1
    sleep 6
}

tap() { sh_ input tap "$1" "$2"; sleep "${3:-1}"; }
key() { sh_ input keyevent "$1"; sleep "${2:-0.4}"; }
swipe() { sh_ input swipe "$1" "$2" "$3" "$4" "${5:-300}"; sleep "${6:-1}"; }

# --- scenarios --------------------------------------------------------------
# Each scenario is <navigate> + <assert> + <interact>. Navigation starts from a
# cold launch so a restored back stack can never change what is measured.

# Bottom-nav, inner-tab and control coordinates are resolved from a UI dump
# rather than hardcoded, so a different screen size or a moved control fails
# loudly instead of tapping empty space and measuring the wrong surface.
# Matches on either the visible text or the content description, because the
# player's controls are icon buttons with no text at all.
node_center() {
    local label="$1" bounds
    sh_ uiautomator dump /sdcard/jank-ui.xml >/dev/null 2>&1 || return 1
    bounds="$(sh_ cat /sdcard/jank-ui.xml 2>/dev/null | tr '>' '\n' \
        | grep -m"${2:-1}" -E "(text|content-desc)=\"$label\"" | tail -1 \
        | sed 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\].*/\1 \2 \3 \4/')"
    [ -n "$bounds" ] || return 1
    awk '{printf "%d %d", ($1+$3)/2, ($2+$4)/2}' <<<"$bounds"
}

tap_node() {
    local xy
    xy="$(node_center "$1" "${2:-1}")" || die "could not locate node '$1' — the harness would otherwise measure the wrong screen"
    tap $xy "${3:-2}"
}

# Like tap_node, but only if the node exists. Used where the target may be
# legitimately absent (a control the current item does not offer).
tap_node_if_present() {
    local xy
    xy="$(node_center "$1" "${2:-1}")" || return 1
    tap $xy "${3:-2}"
}

# A node inside a horizontal scroller can be present in the semantics tree but
# laid out past the right edge, where tapping its reported bounds would hit
# something else. Fling the strip to its end first, then resolve.
scroll_strip_to_end() {
    for _ in $(seq 1 4); do
        swipe "$STRIP_FROM" "$STRIP_Y" "$STRIP_TO" "$STRIP_Y" 200 0.6
    done
    sleep 1
}

# The bottom-nav destination is tapped by its accessibility label rather than
# the visible text, which also appears as an inner view tab on the destination.
# The library itself is then picked explicitly: the app persists the last browsed
# library, so without this a run measures whatever was last opened by hand.
nav_library_screen() {
    launch_cold
    tap_node "Open Library tab" 1 4
    tap_node "Choose library" 1 2
    tap_node "$MOBILE_LIBRARY" 1 4
}

# The Library destination opens on the grid view, so "grid" needs no tab tap and
# "recommended" does. The two views are told apart by controls that exist on only
# one of them — the tab labels are present in both, so asserting on those would
# pass on either view and silently measure the wrong surface.
nav_library_grid() {
    nav_library_screen
    tap_node "Library" 1 3          # inner view tab (first match; bottom nav is later)
}

nav_library_recommended() {
    nav_library_screen
    tap_node "Recommended" 1 3
}

nav_player() {
    launch_cold
    # Resolve the Home hero Play button rather than guessing coordinates; the
    # pinned item is whatever Home surfaces first, stable within a session.
    tap_node "Play" 1 10
    # The pinned item resumes mid-file and transcodes, so playback can take
    # ~20s to start. Wait here rather than letting the caller measure a
    # still-buffering player; a run that never starts is discarded downstream.
    wait_for assert_media_playing || true
}

nav_player_debug() {
    nav_player
    # The overlay is opened while paused. Playing back, the controls auto-hide
    # after PLAYER_CONTROLS_AUTO_HIDE_MS (4s) and a uiautomator dump on a live
    # video surface routinely takes longer than that, so a resolve-then-tap
    # would tap where the button no longer is.
    player_pause
    scroll_strip_to_end
    tap "$DEBUG_BTN_X" "$DEBUG_BTN_Y" 2
    player_resume
}

# --- player geometry --------------------------------------------------------
# Resolved once, from the live view hierarchy, while playback is paused.
#
# Two reasons this cannot be done per-run or from constants:
#  * A dump needs the controls on screen, and while playing they auto-hide in
#    4s — less than a dump on a video surface reliably takes. Paused, the
#    controls stay pinned open, so the hierarchy is stable.
#  * The earlier constant (STRIP_Y=H-260) landed in the gap between the seek bar
#    and the control strip, so "strip scroll" was dragging the video surface —
#    a horizontal drag the player interprets as a gesture, not a strip fling.
# Layout is fixed for the session, so resolving once and reusing is safe; a
# failed resolution aborts rather than measuring an unknown surface.

player_pause() { key 127 2; }    # KEYCODE_MEDIA_PAUSE — handled by the MediaSession
player_resume() { key 126 2; }   # KEYCODE_MEDIA_PLAY

# Bounds of every scrollable container, one per line as "top bottom left right".
scrollable_bounds() {
    sh_ uiautomator dump /sdcard/jank-ui.xml >/dev/null 2>&1 || return 1
    sh_ cat /sdcard/jank-ui.xml 2>/dev/null | tr '>' '\n' | grep 'scrollable="true"' \
        | sed -nE 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\2 \4 \1 \3/p'
}

resolve_player_geometry() {
    note "resolving player geometry (paused)"
    nav_player
    wait_for assert_media_playing ||
        die "playback never started, so the control geometry cannot be resolved"
    player_pause
    # The control strip is the scrollable nearest the bottom of the screen.
    local strip
    strip="$(scrollable_bounds | sort -n | tail -1)"
    [ -n "$strip" ] || die "no scrollable control strip found — refusing to guess its position"
    read -r s_top s_bottom s_left s_right <<<"$strip"
    STRIP_Y=$(( (s_top + s_bottom) / 2 ))
    STRIP_FROM=$(( s_right - 40 )); STRIP_TO=$(( s_left + 40 ))
    [ "$STRIP_Y" -gt $((H / 2)) ] || die "resolved strip y=$STRIP_Y is not in the bottom half — wrong node"
    note "strip y=$STRIP_Y x=$STRIP_FROM..$STRIP_TO"

    # The debug toggle is the last button in that strip, so it starts off-screen.
    scroll_strip_to_end
    local xy
    xy="$(node_center "Playback info")" || die "could not locate the debug overlay toggle"
    read -r DEBUG_BTN_X DEBUG_BTN_Y <<<"$xy"
    note "debug toggle at $DEBUG_BTN_X,$DEBUG_BTN_Y"

    # Open it once to resolve its scroll viewport, then close it again so the
    # measured runs start from the same state the app cold-launches into.
    tap "$DEBUG_BTN_X" "$DEBUG_BTN_Y" 2
    local overlay
    overlay="$(scrollable_bounds | sort -n | head -1)"
    [ -n "$overlay" ] || die "debug overlay did not open"
    read -r o_top o_bottom o_left o_right <<<"$overlay"
    [ "$o_top" -lt $((H / 2)) ] || die "resolved overlay top y=$o_top is not in the upper half — wrong node"
    DEBUG_X=$(( (o_left + o_right) / 2 ))
    DEBUG_FROM=$(( o_bottom - 60 )); DEBUG_TO=$(( o_top + 60 ))
    note "overlay x=$DEBUG_X y=$DEBUG_FROM..$DEBUG_TO"
    tap "$DEBUG_BTN_X" "$DEBUG_BTN_Y" 1
    player_resume
}

interact_debug_scroll() {
    for _ in $(seq 1 6); do
        swipe "$DEBUG_X" "$DEBUG_FROM" "$DEBUG_X" "$DEBUG_TO" 220 0.7
        swipe "$DEBUG_X" "$DEBUG_TO" "$DEBUG_X" "$DEBUG_FROM" 220 0.7
    done
}

interact_controls_toggle() {
    for _ in $(seq 1 8); do tap "$CENTER_X" "$CENTER_Y" 1; done
}

interact_strip_scroll() {
    tap "$CENTER_X" "$CENTER_Y" 1   # reveal controls
    # Scrolling the strip signals keep-alive, so the overlay stays up for the
    # whole sequence without further taps.
    for _ in $(seq 1 6); do
        swipe "$STRIP_FROM" "$STRIP_Y" "$STRIP_TO" "$STRIP_Y" 250 1
        swipe "$STRIP_TO" "$STRIP_Y" "$STRIP_FROM" "$STRIP_Y" 250 1
    done
}

interact_idle() {
    sleep 6                          # controls hidden, pure per-tick cost
    tap "$CENTER_X" "$CENTER_Y" 1    # controls visible
    sleep 6
}

interact_grid_scroll() {
    for _ in $(seq 1 8); do
        swipe "$CENTER_X" "$((H - 300))" "$CENTER_X" 300 220 1
    done
}

interact_tv_focus() {
    for _ in $(seq 1 24); do key DPAD_RIGHT 0.25; done
    for _ in $(seq 1 6); do key DPAD_DOWN 0.3; done
    for _ in $(seq 1 24); do key DPAD_LEFT 0.25; done
}

# --- TV navigation ----------------------------------------------------------
# The Cube has no touchscreen, so navigation is D-pad only. The number of DOWN
# presses is derived from the drawer's actual vertical order in a live dump
# rather than hardcoded: a reordered or renamed destination then fails loudly
# instead of activating the wrong library and measuring the wrong grid.

tv_drawer_order() {
    sh_ uiautomator dump /sdcard/jank-ui.xml >/dev/null 2>&1 || return 1
    sh_ cat /sdcard/jank-ui.xml 2>/dev/null | tr '>' '\n' \
        | sed -nE 's/.*content-desc="([^"]+)".*bounds="\[([0-9]+),([0-9]+)\]\[[0-9]+,[0-9]+\]".*/\2 \3 \1/p' \
        | awk '$1 == 0 { print }' | sort -k2 -n | cut -d' ' -f3-
}

# Steps from the cold-launch destination (Home) to the target destination.
tv_drawer_steps() {
    local target="$1" order home_idx target_idx
    order="$(tv_drawer_order)" || return 1
    home_idx="$(grep -nxF "Home" <<<"$order" | head -1 | cut -d: -f1)"
    target_idx="$(grep -nxF "$target" <<<"$order" | head -1 | cut -d: -f1)"
    [ -n "$home_idx" ] && [ -n "$target_idx" ] || return 1
    echo $((target_idx - home_idx))
}

tv_open_destination() {
    local target="$1" steps
    key DPAD_LEFT 1.2                 # open the drawer; focus lands on Home
    steps="$(tv_drawer_steps "$target")" ||
        die "could not place '$target' in the TV drawer — refusing to guess a D-pad count"
    if [ "$steps" -lt 0 ]; then
        for _ in $(seq 1 $(( -steps ))); do key DPAD_UP 0.4; done
    elif [ "$steps" -gt 0 ]; then
        for _ in $(seq 1 "$steps"); do key DPAD_DOWN 0.4; done
    fi
    key DPAD_CENTER 4
}

nav_tv_grid() {
    launch_cold
    tv_open_destination "$TV_LIBRARY"
}

# --- measurement ------------------------------------------------------------

one_run() {
    sh_ dumpsys gfxinfo "$PKG" reset >/dev/null 2>&1
    "$INTERACT"
    sh_ dumpsys gfxinfo "$PKG" 2>/dev/null | tr -d '\r'
}

parse() {
    local raw="$1"
    local total janky p50 p90 p95 p99
    total="$(grep -m1 'Total frames rendered' <<<"$raw" | awk '{print $NF}')"
    janky="$(grep -m1 '^Janky frames:' <<<"$raw" | sed 's/.*(\(.*\)%)/\1/')"
    p50="$(grep -m1 '50th percentile' <<<"$raw" | awk '{print $NF}' | tr -d 'ms')"
    p90="$(grep -m1 '90th percentile' <<<"$raw" | awk '{print $NF}' | tr -d 'ms')"
    p95="$(grep -m1 '95th percentile' <<<"$raw" | awk '{print $NF}' | tr -d 'ms')"
    p99="$(grep -m1 '99th percentile' <<<"$raw" | awk '{print $NF}' | tr -d 'ms')"
    echo "${total:-0} ${janky:-0} ${p50:-0} ${p90:-0} ${p95:-0} ${p99:-0}"
}

median() { sort -n | awk '{v[NR]=$1} END{ if(NR==0){print 0} else if(NR%2){print v[(NR+1)/2]} else {print (v[NR/2]+v[NR/2+1])/2} }'; }

# --- memory metric (item 4.4) ------------------------------------------------
# Frame timing cannot answer "does a smaller poster decode use less memory", so
# 4.4 gets its own metric. It is only meaningful A/B — the same scenario run
# against two builds — because the absolute numbers depend on everything else
# the process is doing.
#
# Deliberately NOT the Graphics/Bitmap delta: those fields are unreliable here
# (Graphics reads near-zero or omits bitmap bytes on this device), so a naive
# delta would report a confident "no change" from a field that never moves.
# Java heap, native heap and total PSS are the fields actually populated.
one_run_memory() {
    "$INTERACT"                      # warm-up scroll: populate the image cache
    "$INTERACT"                      # the measured scroll
    sleep 2
    sh_ dumpsys meminfo "$PKG" 2>/dev/null | tr -d '\r'
}

parse_memory() {
    local raw="$1" java native graphics pss
    # The colon is load-bearing: "Native Heap" without it matches the per-region
    # table row above, which reports a different number.
    java="$(grep -m1 'Java Heap:' <<<"$raw" | awk '{print $3}')"
    native="$(grep -m1 'Native Heap:' <<<"$raw" | awk '{print $3}')"
    graphics="$(grep -m1 'Graphics:' <<<"$raw" | awk '{print $2}')"
    pss="$(grep -m1 'TOTAL PSS:' <<<"$raw" | awk '{print $3}')"
    echo "${pss:-0} ${java:-0} ${native:-0} ${graphics:-0}"
}

# --- main -------------------------------------------------------------------

DEVICE=""; SCENARIO=""; METRIC="frames"
while [ $# -gt 0 ]; do
    case "$1" in
        --device) DEVICE="$2"; shift 2 ;;
        --scenario) SCENARIO="$2"; shift 2 ;;
        --runs) RUNS="$2"; shift 2 ;;
        --phase) PHASE="$2"; shift 2 ;;
        --metric) METRIC="$2"; shift 2 ;;
        --list) usage; exit 0 ;;
        -h|--help) usage; exit 0 ;;
        *) die "unknown argument: $1" ;;
    esac
done
case "$METRIC" in frames|memory) ;; *) die "unknown metric: $METRIC" ;; esac

[ -n "$DEVICE" ] || { usage; die "--device is required"; }
[ -n "$SCENARIO" ] || { usage; die "--scenario is required"; }
mkdir -p "$PERF_DIR"
# Pinned content (item id / library) lives here so runs are comparable. It names
# server content, so it stays local and uncommitted.
[ -f "$CONFIG" ] && . "$CONFIG"

case "$SCENARIO" in
    tv-*) PKG="$TV_PKG"; LAUNCH_CATEGORY=android.intent.category.LEANBACK_LAUNCHER ;;
    *) PKG="$MOBILE_PKG"; LAUNCH_CATEGORY=android.intent.category.LAUNCHER ;;
esac

SIZE="$(screen_size)"; W="${SIZE%x*}"; H="${SIZE#*x}"
CENTER_X=$((W / 2)); CENTER_Y=$((H / 2))
# Strip and overlay coordinates are deliberately NOT defaulted here: they are
# resolved from the live hierarchy by resolve_player_geometry, and a scenario
# that needs them without resolving must fail loudly rather than fall back to a
# constant that once pointed at the wrong part of the screen.

# TV scenarios name server content, so the labels live in config.env alongside
# the pinned mobile content rather than in this file.
MOBILE_LIBRARY="${MOBILE_LIBRARY:-}"
TV_LIBRARY="${TV_LIBRARY:-}"
TV_GRID_ASSERT="${TV_GRID_ASSERT:-}"

# Every scenario asserts state that only holds on the surface being measured:
# the MediaSession for the player, a named node for everything else. The debug
# overlay asserts both — it is not gated on the controls being visible, so its
# rows survive in the hierarchy while playback continues.
ASSERT_MEDIA=0
NEEDS_PLAYER_GEOMETRY=0
case "$SCENARIO" in
    player-controls-toggle) NAV=nav_player; INTERACT=interact_controls_toggle; ASSERT=""; ASSERT_MEDIA=1 ;;
    player-strip-scroll)    NAV=nav_player; INTERACT=interact_strip_scroll;    ASSERT=""; ASSERT_MEDIA=1; NEEDS_PLAYER_GEOMETRY=1 ;;
    player-debug-scroll)    NAV=nav_player_debug; INTERACT=interact_debug_scroll; ASSERT='text="(Play method|Backend)"'; ASSERT_MEDIA=1; NEEDS_PLAYER_GEOMETRY=1 ;;
    player-idle)            NAV=nav_player; INTERACT=interact_idle;            ASSERT=""; ASSERT_MEDIA=1 ;;
    # Grid view asserts a control the Recommended view does not have, so the two
    # cannot be confused. The tab labels are present in both views and Compose does
    # not expose tab selection to uiautomator here, so neither can be asserted on.
    library-grid-scroll)
        [ -n "$MOBILE_LIBRARY" ] || die "library-grid-scroll needs MOBILE_LIBRARY in $CONFIG"
        NAV=nav_library_grid; INTERACT=interact_grid_scroll; ASSERT='content-desc="Open filters"' ;;
    library-recommended-scroll)
        [ -n "$MOBILE_LIBRARY" ] || die "library-recommended-scroll needs MOBILE_LIBRARY in $CONFIG"
        NAV=nav_library_recommended; INTERACT=interact_grid_scroll; ASSERT='text="Continue Watching"' ;;
    tv-grid-focus)
        [ -n "$TV_LIBRARY" ] && [ -n "$TV_GRID_ASSERT" ] ||
            die "tv-grid-focus needs TV_LIBRARY and TV_GRID_ASSERT in $CONFIG"
        NAV=nav_tv_grid; INTERACT=interact_tv_focus; ASSERT="$TV_GRID_ASSERT" ;;
    detail-episode-focus)
        # Deliberately not implemented. The episode strip lives in shared-ui's
        # detail screen (AdaptiveSeriesEpisodes), which the Android TV app does
        # not use — it has its own composables — so this is not a TV scenario.
        # On the phone it cannot be driven either: touch produces no focus
        # traversal at all, and injected D-pad focus does not descend into the
        # strip (it lands back on the Back button). Tapping cards does move the
        # focused episode, but a tap on the already-focused card plays it, so the
        # sequence is not safely repeatable.
        # The item it would have measured (4.3) is recorded as not measurable
        # with this tooling rather than given a number that means nothing.
        die "detail-episode-focus is not drivable on either device — see the comment in this script"
        ;;
    *) die "unknown scenario: $SCENARIO" ;;
esac

SUFFIX=""; [ "$METRIC" = memory ] && SUFFIX="-memory"
OUT="$PERF_DIR/${SCENARIO}-${PHASE}${SUFFIX}.txt"
{
    echo "# scenario=$SCENARIO phase=$PHASE metric=$METRIC runs=$RUNS"
    device_profile
    if [ "$METRIC" = memory ]; then
        echo "# columns: total_pss_kb java_heap_kb native_heap_kb graphics_kb"
    else
        echo "# columns: total_frames janky_pct p50 p90 p95 p99"
    fi
} > "$OUT"

note "scenario=$SCENARIO package=$PKG metric=$METRIC runs=$RUNS"

[ "$NEEDS_PLAYER_GEOMETRY" = 1 ] && resolve_player_geometry

# Warm-up pass, discarded: a cold image/disk cache would dominate the numbers.
# stderr is deliberately NOT swallowed — a navigation that cannot find its tap
# target must say so rather than silently measuring whatever is on screen.
note "warm-up"
"$NAV" >/dev/null || true
"$INTERACT" >/dev/null 2>&1 || true

declare -a A=() B=() C=() D=()
for i in $(seq 1 "$RUNS"); do
    note "run $i/$RUNS"
    "$NAV" >/dev/null || true
    failed="$(assert_state)"
    if [ -n "$failed" ]; then
        note "run $i discarded — state assertion failed: $failed"
        echo "run=$i DISCARDED (assertion failed: $failed)" >> "$OUT"
        continue
    fi
    if [ "$METRIC" = memory ]; then
        fields="$(parse_memory "$(one_run_memory)")"
        echo "run=$i $fields" >> "$OUT"
        read -r pss java native graphics <<<"$fields"
        A+=("$pss"); B+=("$java"); C+=("$native"); D+=("$graphics")
    else
        fields="$(parse "$(one_run)")"
        echo "run=$i $fields" >> "$OUT"
        read -r _t j _p50 p90 p95 p99 <<<"$fields"
        A+=("$j"); B+=("$p90"); C+=("$p95"); D+=("$p99")
    fi
done

if [ "${#A[@]}" -eq 0 ]; then
    echo "RESULT: no valid runs (every run failed its state assertion)" | tee -a "$OUT"
    exit 1
fi

{
    echo "MEDIAN over ${#A[@]} valid run(s):"
    if [ "$METRIC" = memory ]; then
        printf '  total_pss=%sKB java_heap=%sKB native_heap=%sKB graphics=%sKB\n' \
            "$(printf '%s\n' "${A[@]}" | median)" \
            "$(printf '%s\n' "${B[@]}" | median)" \
            "$(printf '%s\n' "${C[@]}" | median)" \
            "$(printf '%s\n' "${D[@]}" | median)"
        printf '  total_pss samples: %s\n' "${A[*]}"
    else
        printf '  janky%%=%s p90=%sms p95=%sms p99=%sms\n' \
            "$(printf '%s\n' "${A[@]}" | median)" \
            "$(printf '%s\n' "${B[@]}" | median)" \
            "$(printf '%s\n' "${C[@]}" | median)" \
            "$(printf '%s\n' "${D[@]}" | median)"
        printf '  janky%% samples: %s\n' "${A[*]}"
    fi
} | tee -a "$OUT"

note "written to $OUT"
