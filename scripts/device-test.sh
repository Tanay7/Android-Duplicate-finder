#!/usr/bin/env bash
# On-device test, run by CI inside an Android emulator:
#   device-test.sh APK TESTDATA [DEBUG_APK]      (TESTDATA from scripts/make_test_photos.py)
#   1. install the APK; copy the test photos to the phone, plus a screen recording and a copy of it
#   2. scan chosen folders (the app's test extras) and check the groups it logs: only the ticked
#      folders may be searched
#   3. through the real buttons and the system's own dialog: move the ticked files to the trash,
#      check they've left the media index and the kept copies haven't, then Undo and check they're back
#   4. Android closing the app while its results are open: they must come back
#   5. with DEBUG_APK (a test build that dies on purpose while reading one photo, as a crashing
#      decoder would): the photo must be left out by the third scan, and that scan must finish
#   6. GUI exploration with monkey (15,000 events), including rotation
# Fails if a check fails, or the app crashes or stops responding (ANR) at any point.
set -u
APK=$1
DATA=$2
DEBUG_APK=${3:-}
PKG=io.github.tanay7.photocleaner
HERE=$(cd "$(dirname "$0")" && pwd)
FAILS=0
fail() { echo "FAIL: $*"; FAILS=$((FAILS + 1)); }
ok() { echo "ok: $*"; }

adb wait-for-device
echo "SDK $(adb shell getprop ro.build.version.sdk | tr -d '\r'), ABIs $(adb shell getprop ro.product.cpu.abilist | tr -d '\r')"
# right after boot the package manager can report "not enough space" until storage settles: retry
for i in $(seq 1 30); do
  out=$(adb install -r "$APK" 2>&1) && { echo "$out" | tail -1; break; }
  echo "install attempt $i: $(echo "$out" | grep -m1 -oE 'not enough space|Exception[^:]*|Failure[^ ]*' || echo "$out" | tail -1)"
  [ "$i" = 30 ] && { echo "FAIL: APK did not install"; echo "$out" | tail -20; exit 1; }
  sleep 5
done
for p in READ_MEDIA_IMAGES READ_MEDIA_VIDEO ACCESS_MEDIA_LOCATION POST_NOTIFICATIONS; do adb shell pm grant $PKG android.permission.$p; done

# -- test files -------------------------------------------------------------------------------
# All but Pictures/CrashTest: those arrive for the crash test, so no earlier scan has read them.
files() { (cd "$DATA" && find . -type f | sed 's|^\./||' | grep -v '^Pictures/CrashTest/'); }
crashfiles() { (cd "$DATA" && find . -type f | sed 's|^\./||' | grep '^Pictures/CrashTest/'); }
files | while IFS= read -r f; do
  adb push "$DATA/$f" "/sdcard/$f" </dev/null >/dev/null || echo "push failed: $f"
done
adb shell mkdir -p /sdcard/Movies /sdcard/Download
VIDEOS=0
if adb shell screenrecord --time-limit 3 /sdcard/Movies/clip.mp4 >/dev/null 2>&1 && \
   adb shell 'cp /sdcard/Movies/clip.mp4 "/sdcard/Download/clip (1).mp4"'; then
  VIDEOS=1
fi
# Files written through /sdcard reach the media index on their own; ask for a scan too, to be sure.
# (adb shell would swallow the rest of the list if it could read it: its input is closed.)
(files; [ $VIDEOS = 1 ] && printf 'Movies/clip.mp4\nDownload/clip (1).mp4\n') | while IFS= read -r f; do
  adb shell "am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d 'file:///sdcard/$f'" </dev/null >/dev/null 2>&1
done
indexed() {  # every image the media index lists, as folder/name
  adb shell content query --uri content://media/external/images/media --projection relative_path:_display_name 2>/dev/null |
    sed -nE 's/.*relative_path=([^,]*), _display_name=(.*)$/\1\2/p' | tr -d '\r'
}
sized() {  # images Android has finished indexing (their size is known)
  adb shell content query --uri content://media/external/images/media --projection _size 2>/dev/null | grep -cE '_size=[1-9]'
}
want=$(files | wc -l)
wantSized=$(files | while IFS= read -r f; do [ -s "$DATA/$f" ] && echo "$f"; done | wc -l)     # an empty file never gets a size
# Right after boot the media index can miss a file that was written while it was still starting:
# such a file is written again, and the index asked to look at the storage.
again() {  # again LIST: push the files of LIST (one per line) that the index doesn't have
  comm -23 <(echo "$1" | sort) <(indexed | sort) | while IFS= read -r f; do
    [ -n "$f" ] || continue
    echo "not in the media index, written again: $f"
    adb shell "rm -f '/sdcard/$f'" </dev/null >/dev/null 2>&1
    adb push "$DATA/$f" "/sdcard/$f" </dev/null >/dev/null || echo "push failed: $f"
  done
  adb shell content call --uri content://media --method scan_volume --arg external_primary </dev/null >/dev/null 2>&1
}
for i in $(seq 1 90); do
  have=$(indexed | wc -l)
  done_=$(sized)
  [ "$have" -ge "$want" ] && [ "$done_" -ge "$wantSized" ] && break
  [ $((i % 8)) = 0 ] && again "$(files)"
  sleep 2
done
echo "media index: $have images, $done_ with their size (want $want, $wantSized), videos=$VIDEOS"
[ "$have" -ge "$want" ] || { fail "test photos missing from the media index"; indexed; }
if [ $VIDEOS = 1 ]; then
  for i in $(seq 1 30); do
    v=$(adb shell content query --uri content://media/external/video/media --projection _display_name 2>/dev/null | grep -c 'clip')
    [ "$v" -ge 2 ] && break
    sleep 2
  done
  [ "$v" -ge 2 ] || { echo "videos not indexed: skipping the video checks"; VIDEOS=0; }
fi

adb logcat -c
adb logcat -v threadtime > logcat.txt 2>/dev/null &   # stream: the ring buffer could drop early crashes
LOGPID=$!
shot() { adb exec-out screencap -p > "screen-$1.png" 2>/dev/null || true; }
tap() {  # tap the first view whose text or description matches $1
  local xy
  for i in 1 2 3 4 5; do xy=$(python3 "$HERE/ui.py" "$1") && break; sleep 2; done
  if [ -z "${xy:-}" ]; then echo "nothing on screen matches '$1'; on screen:"; python3 "$HERE/ui.py" --dump | head -40; return 1; fi
  adb shell input tap $xy
}
field() { echo "$1" | grep -oE "(^| )$2=[0-9]+" | grep -oE '[0-9]+$'; }

# -- scans of chosen folders ------------------------------------------------------------------
# scan FOLDERS: tick only those folders (comma-separated, * for all), scan, print the RESULT line
scan() {
  local n0 line
  n0=$(grep -c ' RESULT ' logcat.txt)
  adb shell "am start -n $PKG/.MainActivity --es folders '$1' --ez autoscan true" >/dev/null
  for i in $(seq 1 180); do
    line=$(grep ' RESULT ' logcat.txt | sed -n "$((n0 + 1))p")
    [ -n "$line" ] && break
    sleep 1
  done
  echo "${line#*RESULT }"
}
expect() {  # expect LINE NAME VALUE
  local got
  got=$(field "$1" "$2")
  if [ "$got" = "$3" ]; then ok "$2=$3"; else fail "$2 is ${got:-missing}, expected $3"; fi
}

adb shell am start -W -n $PKG/.MainActivity >/dev/null; sleep 3; shot 1-folders

echo "== only Pictures/Copies and Download are ticked"
r=$(scan 'Pictures/Copies,Download'); echo "$r"
expect "$r" photos 2
expect "$r" videos $VIDEOS
expect "$r" groups 1
expect "$r" identical 1
expect "$r" suggested 1
sleep 2; shot 2-results-two-folders
grep -q 'GROUP identical KEEP:Pictures/Copies/IMG_0001.jpg TRASH:Download/IMG_0001 (1).jpg' logcat.txt &&
  ok "the copy with a copy name goes" || fail "wrong copy kept in Pictures/Copies + Download"

expect "$r" original 1            # the files' own bytes, not copies with the location blanked out

echo "== only DCIM/Camera is ticked"
r=$(scan 'DCIM/Camera'); echo "$r"
expect "$r" photos 11
expect "$r" groups 1
expect "$r" bursts 1
expect "$r" suggested 4
if grep 'GROUP bursts' logcat.txt | tail -1 | grep -q 'KEEP:DCIM/Camera/BURST_03.jpg'; then
  fail "the blurred burst frame was kept"
else
  ok "a sharp burst frame is kept"
fi
# five pages of a document, photographed two seconds apart: alike in layout only
if grep ' GROUP ' logcat.txt | grep -q 'DOC_0'; then
  fail "pages of a document were taken for a burst or for look-alikes"
else
  ok "pages photographed one after the other are not a burst"
fi

echo "== everything is ticked"
r=$(scan '*'); echo "$r"
sleep 1   # the lines for each group follow the result
expect "$r" photos 26
expect "$r" videos $((2 * VIDEOS))
expect "$r" groups $((3 + VIDEOS))
expect "$r" lookalike 1
expect "$r" bursts 1
expect "$r" poor 1
expect "$r" videogroups $VIDEOS
expect "$r" undecodable 2          # the truncated photo and the text file: reported, not fatal
expect "$r" unreadable 0
expect "$r" quarantined 0
[ "$(field "$r" threads)" -ge 1 ] && ok "read on $(field "$r" threads) threads, $(field "$r" cores) cores" || fail "no thread count"
PHOTOS_TICKED=9                    # 4 copies of the original, the edit's small copy, 4 burst frames
TICKED=$((PHOTOS_TICKED + VIDEOS))
expect "$r" suggested $TICKED
grep ' GROUP lookalike ' logcat.txt | tail -1 | grep -q 'KEEP:DCIM/Camera/IMG_0001.jpg' &&
  ok "the camera original is kept" || fail "the camera original isn't the kept copy"
grep ' GROUP lookalike ' logcat.txt | tail -1 | grep -q 'TRASH:Pictures/Edited/turned.jpg' &&
  ok "the turned copy is found" || fail "the turned copy wasn't matched"
# an edit is a version of its own: kept as well, and its own copy is what goes
grep ' GROUP lookalike ' logcat.txt | tail -1 | grep -q 'KEEP:Pictures/Edited/brighter.jpg' &&
  ok "the brighter edit is kept as well" || fail "the brighter edit isn't kept as a version of its own"
grep ' GROUP lookalike ' logcat.txt | tail -1 | grep -q 'TRASH:Pictures/Edited/brighter_small.jpg' &&
  ok "the edit's small copy goes" || fail "the edit's small copy isn't ticked"
last=$(grep -n ' RESULT ' logcat.txt | tail -1 | cut -d: -f1)
if tail -n +"$last" logcat.txt | grep ' GROUP ' | grep -qE 'DOC_0|settings_'; then
  fail "different pages or screens were grouped"
else
  ok "different pages and screens with the same layout are not grouped"
fi
if tail -n +"$last" logcat.txt | grep ' GROUP poor ' | grep -q 'settings_'; then
  fail "a white screenshot was listed as badly exposed"
else
  ok "screenshots are not judged as photos"
fi
sleep 2; shot 3-results

# -- the viewer, the options and the about box --------------------------------------------------
tap 'Tap to view' && { sleep 3; shot 4-viewer; adb shell input swipe 900 1200 200 1200 150; sleep 2; shot 5-viewer-next; adb shell input keyevent KEYCODE_BACK; sleep 2; }
adb shell input keyevent KEYCODE_BACK; sleep 2   # back to the folders
tap 'What to look for' && { sleep 2; shot 6-options; adb shell input keyevent KEYCODE_BACK; sleep 1; }
tap '^About$' && { sleep 2; shot 7-about; adb shell input keyevent KEYCODE_BACK; sleep 1; }

# -- to the trash and back --------------------------------------------------------------------
before=$(indexed)
tap 'View results' && sleep 2
if tap "^Move $TICKED to trash$"; then
  sleep 3; shot 8-trash-confirmation
  tap '^Allow$' || fail "no Allow button in the system's trash dialog"
  sleep 4; shot 9-moved-to-trash
  after=$(indexed)
  gone=$(comm -23 <(echo "$before" | sort) <(echo "$after" | sort))
  echo "left the media index:"; echo "$gone"
  [ "$(echo "$gone" | grep -c .)" = "$PHOTOS_TICKED" ] && ok "$PHOTOS_TICKED photos in the trash" || fail "$(echo "$gone" | grep -c .) photos left the index, expected $PHOTOS_TICKED"
  echo "$after" | grep -qx 'DCIM/Camera/IMG_0001.jpg' && ok "the kept original is still there" || fail "the kept original is gone"
  echo "$after" | grep -qx 'Pictures/Edited/brighter.jpg' && ok "the kept edit is still there" || fail "the kept edit is gone"
  echo "$after" | grep -qx 'Download/IMG_0001 (1).jpg' && fail "the copy is still there" || ok "the copy is gone"
  if tap '^Undo$'; then
    sleep 3
    tap '^Allow$' || fail "no Allow button in the system's restore dialog"
    sleep 4; shot 10-undone
    back=$(indexed | wc -l)
    [ "$back" = "$(echo "$before" | wc -l)" ] && ok "Undo brought them all back" || fail "after Undo the index has $back images, expected $(echo "$before" | wc -l)"
  else
    fail "no Undo button after moving to the trash"
  fi
else
  fail "no 'Move $TICKED to trash' button"
fi

echo "== Android closes the app while its results are open"
python3 "$HERE/ui.py" '^Results$' >/dev/null || { tap 'View results' && sleep 2; }
n0=$(grep -c ' RESULT ' logcat.txt)
adb shell input keyevent KEYCODE_HOME; sleep 3
adb shell am kill $PKG; sleep 3
if adb shell pidof $PKG >/dev/null 2>&1; then
  echo "skipped: Android kept the process alive, so there was nothing to bring back"
  adb shell am start -n $PKG/.MainActivity >/dev/null; sleep 2
else
  adb shell am start -n $PKG/.MainActivity >/dev/null
  for i in $(seq 1 60); do [ "$(grep -c ' RESULT ' logcat.txt)" -gt "$n0" ] && break; sleep 1; done
  sleep 3; shot 12-results-restored
  if [ "$(grep -c ' RESULT ' logcat.txt)" -gt "$n0" ] && python3 "$HERE/ui.py" '^Results$' >/dev/null; then
    ok "the results came back after Android closed the app"
  else
    fail "the results didn't come back after Android closed the app"
  fi
fi
adb shell input keyevent KEYCODE_BACK; sleep 1

if [ -n "$DEBUG_APK" ] && [ -f "$DEBUG_APK" ]; then
  echo "== a photo that makes the app die is left out by the third scan"
  adb install -r "$DEBUG_APK" >/dev/null 2>&1 || fail "the test build did not install"
  # three new photos, one of which the test build dies on
  n0=$(indexed | wc -l)
  crashfiles | while IFS= read -r f; do
    adb push "$DATA/$f" "/sdcard/$f" </dev/null >/dev/null || echo "push failed: $f"
    adb shell "am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d 'file:///sdcard/$f'" </dev/null >/dev/null 2>&1
  done
  for i in $(seq 1 60); do
    [ "$(indexed | grep -c '^Pictures/CrashTest/')" -ge "$(crashfiles | wc -l)" ] && [ "$(sized)" -ge "$((n0 + $(crashfiles | wc -l)))" ] && break
    [ $((i % 8)) = 0 ] && again "$(crashfiles)"
    sleep 2
  done
  [ "$(indexed | grep -c '^Pictures/CrashTest/')" -ge 3 ] || fail "the crash-test photos are missing from the media index"
  adb shell am start -W -n $PKG/.MainActivity >/dev/null; sleep 3
  for round in 1 2; do
    n0=$(grep -c 'TEST dying' logcat.txt)
    adb shell "am start -n $PKG/.MainActivity --es folders 'Pictures/CrashTest' --ez autoscan true" >/dev/null
    for i in $(seq 1 60); do [ "$(grep -c 'TEST dying' logcat.txt)" -gt "$n0" ] && break; sleep 1; done
    [ "$(grep -c 'TEST dying' logcat.txt)" -gt "$n0" ] && ok "scan $round: the app died while reading the photo" || fail "scan $round: the app didn't reach the photo"
    sleep 5
    adb shell am start -W -n $PKG/.MainActivity >/dev/null; sleep 3
  done
  shot 13-after-a-crash
  r=$(scan 'Pictures/CrashTest'); echo "$r"
  grep 'last exit' logcat.txt | tail -2
  expect "$r" photos 3
  expect "$r" quarantined 1
  expect "$r" groups 0
  [ "$(grep -c 'TEST dying' logcat.txt)" = "2" ] && ok "the photo was not read a third time" || fail "the photo was read again"
  adb shell input keyevent KEYCODE_BACK; sleep 1
  # the build that ships again, for the rest
  adb install -r "$APK" >/dev/null 2>&1 || fail "the release build did not install again"
  adb shell am start -W -n $PKG/.MainActivity >/dev/null; sleep 3
else
  echo "(no test build given: the crash test is skipped)"
fi

adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 1; sleep 3; shot 11-landscape
adb shell settings put system user_rotation 0; sleep 2

# -- random GUI exploration -------------------------------------------------------------------
start() { adb shell am start -W -n $PKG/.MainActivity >/dev/null; sleep "${1:-3}"; }
for seed in 11 22 33 44 55; do
  adb shell monkey -p $PKG -s $seed --throttle 40 --pct-syskeys 2 --pct-rotation 3 -v 3000 > monkey-$seed.txt 2>&1
  grep -h "Events injected" monkey-$seed.txt || true
  start 2
done
adb shell am force-stop $PKG
sleep 2; kill $LOGPID 2>/dev/null
crash=$(grep -A3 "FATAL EXCEPTION" logcat.txt | grep -c "Process: $PKG")
anr=$(grep -c "ANR in $PKG" logcat.txt)
mon=$(grep -l "CRASH: $PKG\|NOT RESPONDING: $PKG" monkey-*.txt | wc -l)
echo "crashes=$crash anrs=$anr monkey-reports=$mon checks-failed=$FAILS"
if [ "$crash" -gt 0 ] || [ "$anr" -gt 0 ] || [ "$mon" -gt 0 ]; then
  grep -B2 -A40 "FATAL EXCEPTION" logcat.txt | grep -A40 "Process: $PKG" | head -120
  grep -h -A30 "CRASH: $PKG" monkey-*.txt | head -80
  if [ "$anr" -gt 0 ] || grep -q "NOT RESPONDING: $PKG" monkey-*.txt; then
    echo "---- ANR reports (logcat) ----"; grep -A25 "ANR in $PKG" logcat.txt | head -120
    echo "---- monkey ----"; grep -h -B3 -A25 "NOT RESPONDING: $PKG" monkey-*.txt | head -120
  fi
  exit 1
fi
[ "$FAILS" -gt 0 ] && { echo "$FAILS checks failed"; grep -E ' (RESULT|GROUP) ' logcat.txt; exit 1; }
echo "DEVICE TEST PASSED"
