#!/bin/bash
# The Fold's four shapes at its own dp (the emulator can't go past 1920 px, so the density is lowered
# to match): cover 411×960 dp and main 859×896 dp, each upright and turned.
#
# The book is opened once, part way in, on the upright cover. Then the shape changes under it
# without reopening, as folding and turning do on the phone. After each change files/layout.json
# must show: the expected arrangement, a comfortable measure, no text over the spine, and the line
# that was being read still starting the left page (Dorso keeps your place by sentence).
PKG=com.cerniera.dorso
exec > >(tee walk.txt) 2>&1
adb install -r Dorso.apk
adb logcat -c
fail=0
crashed() { adb logcat -d | grep -A2 "FATAL EXCEPTION" | grep -q "Process: $PKG"; }
size() { adb shell wm size "$1x$2"; adb shell wm density 280; sleep 3; }
layout() { adb shell run-as $PKG cat files/layout.json 2>/dev/null; }

# The shelf, seeded with the sample read
size 719 1680
adb shell am start -W -n $PKG/.MainActivity >/dev/null; sleep 5
adb exec-out screencap -p > shot-0-shelf.png

# Open the sample at character 2200 (the second section)
adb shell am start -W -n $PKG/.MainActivity --es open sample --es at 2200 >/dev/null; sleep 5
prev=$(layout); echo "opened: $prev"

check() {  # name expected-mode
  sleep 2
  now=$(layout)
  adb exec-out screencap -p > "shot-$1.png"
  python3 - "$1" "$2" "$prev" "$now" <<'PY' || fail=1
import json, sys
name, want, a, b = sys.argv[1:5]
try:
    old, new = json.loads(a), json.loads(b)
except Exception as e:
    print(f"::error::{name}: no layout ({e})"); sys.exit(1)
bad = []
if new["mode"] != want: bad.append(f"mode {new['mode']}, expected {want}")
cpl = new["charsPerLine"]
if not 30 <= cpl <= 80: bad.append(f"{cpl} characters a line")
if new["textOverSpine"]: bad.append("text over the spine")
if new["textOverCutout"]: bad.append("text under the camera hole")
# The place: the old line's first character must now lie on the first line of the left page
o, n = old["offset"], new["offset"]
if not (n <= o <= n + int(cpl * 1.3)): bad.append(f"place moved: was at {o} ('{old['firstWords'][:24]}'), now page starts at {n} ('{new['firstWords'][:24]}')")
vw, vh = new["view"]
for r in new["text"]:
    if r[0] < 0 or r[1] < 0 or r[2] > vw or r[3] > vh: bad.append(f"text box {r} outside {vw}x{vh}")
msg = f"{name}: {new['mode']}, {new['pages']} pages, {cpl} a line, starts '{new['firstWords'][:30]}'"
print(("::error::" + msg + " | " + "; ".join(bad)) if bad else ("::notice::" + msg))
sys.exit(1 if bad else 0)
PY
  if crashed; then echo "::error::crash in $1"; fail=1; fi
  prev=$now
}
check cover-upright SINGLE
size 1503 1568; check main-upright SIDE
size 1568 1503; check main-turned SIDE
size 1680 719;  check cover-turned SIDE
size 719 1680;  check cover-again SINGLE

# Turning: a tap on the right turns the page forward; the top edge brings the trim
before=$(layout | python3 -c "import json,sys; print(json.load(sys.stdin)['offset'])")
adb shell input tap 650 900; sleep 1.5
after=$(layout | python3 -c "import json,sys; print(json.load(sys.stdin)['offset'])")
if [ "$after" -gt "$before" ]; then echo "::notice::turned: $before -> $after"; else echo "::error::page did not turn ($before -> $after)"; fail=1; fi
adb exec-out screencap -p > shot-turned.png
adb shell input tap 360 60; sleep 1
adb exec-out screencap -p > shot-trim.png
adb shell input keyevent KEYCODE_BACK; sleep 2
adb exec-out screencap -p > shot-shelf-after.png

# A real Medium link, shared in. Medium answers a plain download with 403 (Cloudflare), so this
# goes through the hidden browser. A data-centre runner can be challenged harder than a phone, so a
# miss is a warning; a crash still fails the run.
MEDIUM="https://medium.com/change-your-mind/doing-nothing-09c8f365bff1"
adb shell am start -W -n $PKG/.MainActivity -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT "$MEDIUM" >/dev/null
got=""
for i in $(seq 1 25); do
  sleep 2
  got=$(adb shell run-as $PKG sh -c 'grep -l "09c8f365bff1" files/reads/*.json' 2>/dev/null | head -1)
  [ -n "$got" ] && break
done
adb exec-out screencap -p > shot-medium.png
if [ -n "$got" ]; then
  words=$(adb shell run-as $PKG cat "$got" | python3 -c "import json,sys; a=json.load(sys.stdin); print(a.get('title',''), '|', sum(len(b.get('t','').split()) for b in a.get('blocks',[])), 'words')" 2>/dev/null)
  echo "::notice::Medium read saved: $words"
else
  echo "::warning::Medium read not saved within 50 s (see shot-medium.png)"
fi
adb shell input keyevent KEYCODE_BACK; sleep 1

if crashed; then echo "::error::Dorso crashed"; fail=1; fi
{ echo "Dorso log"; adb logcat -d -s AndroidRuntime:E; } > app-log.txt
adb shell wm size reset; adb shell wm density reset
exit $fail
