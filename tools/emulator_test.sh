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

# The leaf: take the page by its edge, hold it part way over (photographed), then let it go.
# Past halfway it must fall over (forward: the place moves on; back: it moves back).
offset() { layout | python3 -c "import json,sys; print(json.load(sys.stdin)['offset'])"; }
drag() {  # name y x-from x-hold x-to want(+/-)
  b=$(offset)
  adb shell input motionevent DOWN $3 $2
  n=6; for i in $(seq 1 $n); do adb shell input motionevent MOVE $(( $3 + ($4 - $3) * i / n )) $(( $2 - 160 * i / n )); done
  sleep 0.4; adb exec-out screencap -p > "shot-leaf-$1.png"
  adb shell input motionevent MOVE $5 $2
  adb shell input motionevent UP $5 $2
  sleep 1.5
  a=$(offset)
  if { [ "$6" = "+" ] && [ "$a" -gt "$b" ]; } || { [ "$6" = "-" ] && [ "$a" -lt "$b" ]; }; then echo "::notice::leaf $1: $b -> $a"
  else echo "::error::leaf $1 did not turn ($b -> $a, wanted $6)"; fail=1; fi
  if crashed; then echo "::error::crash turning $1"; fail=1; fi
}
drag cover-forward 900 690 380 90 +
drag cover-back 900 20 300 650 -
size 1503 1568; sleep 2
drag main-back 800 60 620 1400 -
drag main-forward 800 1450 900 120 +
size 719 1680; sleep 2

# Folding shut: the book closes and the jacket shows. Take its edge and open it (held part way,
# photographed): past halfway it opens onto the page you were on, which must not have moved.
closedq() { layout | python3 -c "import json,sys; print(json.load(sys.stdin).get('closed'))"; }
adb exec-out screencap -p > shot-jacket.png
if [ "$(closedq)" = "True" ]; then echo "::notice::folded shut: the jacket shows"; else echo "::error::folding shut did not close the book"; fail=1; fi
b=$(offset)
adb shell input motionevent DOWN 690 1300
for i in 1 2 3 4 5 6; do adb shell input motionevent MOVE $(( 690 - 70 * i )) $(( 1300 - 50 * i )); done
sleep 0.4; adb exec-out screencap -p > shot-jacket-opening.png
adb shell input motionevent MOVE 40 1000
adb shell input motionevent UP 40 1000
sleep 1.6
adb exec-out screencap -p > shot-jacket-open.png
if [ "$(closedq)" = "False" ] && [ "$(offset)" = "$b" ]; then echo "::notice::jacket opened onto the same page"; else echo "::error::jacket did not open cleanly (closed=$(closedq), offset $b -> $(offset))"; fail=1; fi
if crashed; then echo "::error::crash opening the jacket"; fail=1; fi

# Dog-ear: fold the page's top corner down (photographed mid-fold), let go: the page is marked.
# Unfolded it's still there on its page; a tap on the fold smooths it out.
marksq() { layout | python3 -c "import json,sys; print(json.load(sys.stdin).get('marks'))"; }
adb shell input motionevent DOWN 712 14
for i in 1 2 3 4 5 6; do adb shell input motionevent MOVE $(( 712 - 24 * i )) $(( 14 + 26 * i )); done
sleep 0.3; adb exec-out screencap -p > shot-dogear-folding.png
adb shell input motionevent UP 568 170
sleep 1; adb exec-out screencap -p > shot-dogear.png
if [ "$(marksq)" = "1" ]; then echo "::notice::dog-ear: page marked"; else echo "::error::dog-ear did not mark the page (marks=$(marksq))"; fail=1; fi
size 1503 1568; sleep 2; adb exec-out screencap -p > shot-dogear-main.png
size 719 1680; sleep 2
adb shell input tap 360 900; sleep 1.5        # the book shut on folding: open the jacket
adb shell input tap 704 22; sleep 1
if [ "$(marksq)" = "0" ]; then echo "::notice::dog-ear: a tap smoothed it out"; else echo "::error::tap on the fold did not unmark (marks=$(marksq))"; fail=1; fi
if crashed; then echo "::error::crash with the dog-ear"; fail=1; fi
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
for i in $(seq 1 40); do
  sleep 2
  got=$(adb shell "run-as $PKG sh -c 'grep -l medium.com files/reads/*.json'" 2>/dev/null | tr -d '\r' | head -1)
  [ -n "$got" ] && break
done
adb exec-out screencap -p > shot-medium.png
if [ -n "$got" ]; then
  words=$(adb shell "run-as $PKG cat $got" | python3 -c "import json,sys; a=json.load(sys.stdin); print(a.get('title',''), '|', sum(len(b.get('t','').split()) for b in a.get('blocks',[])), 'words')" 2>/dev/null)
  echo "::notice::Medium read saved: $words"
else
  echo "::warning::Medium read not saved within 80 s (see shot-medium.png)"
fi
adb shell input keyevent KEYCODE_BACK; sleep 1

# Signing in: the shelf lists the accounts; the New York Times sign-in page opens inside Dorso
adb shell am start -W -n $PKG/.MainActivity >/dev/null; sleep 3
adb shell input swipe 360 1400 360 300 300; sleep 1
adb exec-out screencap -p > shot-accounts.png
adb shell am start -W -n $PKG/.MainActivity --es signin nyt >/dev/null; sleep 10
adb exec-out screencap -p > shot-signin.png
if adb shell dumpsys activity activities | grep -q "SignInActivity"; then echo "::notice::sign-in page opened"; else echo "::warning::sign-in page not in front"; fi
adb shell input keyevent KEYCODE_BACK; sleep 1; adb shell input keyevent KEYCODE_BACK; sleep 1

if crashed; then echo "::error::Dorso crashed"; fail=1; fi
{ echo "Dorso log"; adb logcat -d -s AndroidRuntime:E; } > app-log.txt
adb shell wm size reset; adb shell wm density reset
exit $fail
