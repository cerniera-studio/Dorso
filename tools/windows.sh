#!/usr/bin/env bash
# Windows (adaptive quality, 20 Nov 2026; Sveglia's tools/windows.sh is the template). The book in a
# pop-up window on the main screen's shape (859 x 896 dp), resized and turned. Writes windows.txt;
# a line starting ::error:: fails the run.
#
# A narrow window on the main screen (half of it in split screen, or a pop-up window) is not the
# phone folding: the book must stay open, laid out for the window it has, on the same sentence.
#   1. Wide pop-up window: two pages, open.
#   2. Shrunk to under 600 dp: one page, still open, same place.
#   3. Turned: still open, same place.
#   4. Widened again: two pages, open, same place.
# What is on screen is read from files/layout.json, which the reader writes after every layout.
#
# Pop-up windows need freeform support, which the emulator only reads at boot: this reboots it.
PKG=com.cerniera.dorso
out=windows.txt; : > $out
note() { echo "::notice::$*" | tee -a $out; }
fail() { echo "::error::$*" | tee -a $out; }
warn() { echo "::warning::$*" | tee -a $out; }
shot() { timeout 20 adb exec-out screencap -p > "shot-win-$1.png"; }

adb install -r Dorso.apk
adb shell settings put global enable_freeform_support 1
adb shell settings put global force_resizable_activities 1
adb reboot
adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; do sleep 2; done
sleep 30
adb shell input keyevent 82
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 0
for a in window_animation_scale transition_animation_scale animator_duration_scale; do adb shell settings put global $a 0; done
# No "isn't responding" dialogs, and three-button navigation, so a stalled launcher can't hold up
# input (as seen in Fold Taskbar's and Solco's runs)
adb shell settings put global hide_error_dialogs 1
adb shell cmd overlay enable com.android.internal.systemui.navbar.threebutton
adb shell am force-stop com.google.android.apps.nexuslauncher
adb logcat -c

task() {  # "<id> <mode>" of the task whose affinity is exactly $1
  adb shell dumpsys activity activities | python3 -c "
import sys, re
for line in sys.stdin:
    m = re.search(r'\* Task\{\S+ #(\d+) .*?A=\d+:(\S+) .*?mode=(\S+)', line)
    if m and m.group(2) == '$1': print(m.group(1), m.group(3)); break"
}
layout() {  # read a few times: run-as can come back empty while the window settles
  for try in 1 2 3 4 5; do
    l=$(timeout 15 adb shell run-as $PKG cat files/layout.json 2>/dev/null)
    [ -n "$l" ] && { echo "$l"; return; }; sleep 2
  done
}
fresh() {  # wait for a new layout after a change (the file is cleared first)
  for try in $(seq 1 15); do l=$(layout); [ -n "$l" ] && { echo "$l"; return; }; sleep 1; done
}
clear_layout() { timeout 15 adb shell run-as $PKG rm -f files/layout.json; }

# check <name> <expected mode> <layout before> <layout now>
check() {
  python3 - "$@" <<'PY' | tee -a windows.txt
import json, sys
name, want, a, b = sys.argv[1:5]
try:
    old, new = json.loads(a), json.loads(b)
except Exception as e:
    print(f"::warning::{name}: no layout could be read ({e}); not counted"); sys.exit(0)
bad = []
if new["closed"]: bad.append("the book shut, as if the phone had folded")
if new["mode"] != want: bad.append(f"{new['mode']}, expected {want}")
cpl = new["charsPerLine"]
o, n = old["offset"], new["offset"]
if not (n <= o <= n + int(cpl * 1.3)):
    bad.append(f"place moved: was at {o} ('{old['firstWords'][:24]}'), now the page starts at {n} ('{new['firstWords'][:24]}')")
if new["textOverSpine"]: bad.append("text over the spine")
msg = f"{name}: {new['mode']}, view {new['view'][0]}x{new['view'][1]}, {cpl} a line, starts '{new['firstWords'][:30]}'"
print(("::error::" + msg + " | " + "; ".join(bad)) if bad else ("::notice::" + msg))
PY
}

# ---- The main screen's shape, the book opened in a pop-up window, part way in
adb shell wm density 280; adb shell wm size 1503x1568; sleep 3
adb shell am start -W --windowingMode 5 -n $PKG/.MainActivity >/dev/null; sleep 4   # seeds the shelf
adb shell am start -W -n $PKG/.MainActivity --es open sample --es at 2200 >/dev/null; sleep 4
set -- $(task $PKG); id=$1; mode=$2
echo "task: $id $mode" | tee -a $out
if [ "$mode" != freeform ]; then
  warn "Pop-up window: the emulator would not open one (mode ${mode:-none}); checks skipped"
elif ! adb shell dumpsys activity activities | grep ResumedActivity | grep -q ReaderActivity; then
  warn "The book didn't open in the pop-up window; checks skipped"
  shot no-reader
else
  # 1. Wide (1300 px, about 743 dp)
  clear_layout; timeout 20 adb shell am task resize "$id" 0 0 1300 1568; sleep 2
  wide=$(fresh); shot wide
  if [ -z "$wide" ]; then warn "No layout in the wide pop-up window; checks skipped"
  else
    python3 -c "import json,sys; d=json.loads(sys.argv[1]); print('wide:', d['mode'], 'closed', d['closed'], d['view'])" "$wide" | tee -a $out
    if python3 -c "import json,sys; sys.exit(0 if json.loads(sys.argv[1])['closed'] else 1)" "$wide"; then
      fail "Wide pop-up window on the main screen: the book is shut, as if the phone were folded"
    fi
    # 2. Narrow (740 px, about 423 dp): half the main screen
    clear_layout; timeout 20 adb shell am task resize "$id" 0 0 740 1568; sleep 2
    narrow=$(fresh); shot narrow
    check "Pop-up window shrunk to 740 px" SINGLE "$wide" "$narrow"
    # 3. Turned
    clear_layout; adb shell settings put system user_rotation 1; sleep 3
    turned=$(fresh); shot turned
    check "Narrow pop-up window, phone turned" SINGLE "$narrow" "$turned"
    adb shell settings put system user_rotation 0; sleep 3
    # 4. Widened again
    back=$(layout)
    clear_layout; timeout 20 adb shell am task resize "$id" 0 0 1400 1568; sleep 2
    wide2=$(fresh); shot wide-again
    check "Pop-up window widened again" SIDE "${back:-$turned}" "$wide2"
  fi
fi
timeout 20 adb shell am force-stop $PKG
timeout 20 adb shell wm size reset; timeout 20 adb shell wm density reset

{ echo "logcat after the windows test"; timeout 60 adb logcat -d; } > app-log-windows.txt
if grep -A2 "FATAL EXCEPTION" app-log-windows.txt | grep -q "Process: $PKG"; then fail "Dorso crashed in the windows test"; grep -A20 "FATAL EXCEPTION" app-log-windows.txt | head -40; fi
grep -q "^::error::" $out && exit 1
exit 0
