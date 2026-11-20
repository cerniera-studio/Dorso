"""
Dorso's page-turn sounds, synthesised to match a reference recording of magazine pages being
turned (measured, not copied). What a page turn is, measured:

  * not a continuous rustle but 4-7 distinct crackles, each 20-40 ms, 80-200 ms apart, with
    near-quiet paper between them; often a crisp one as the page lifts and another as it lands
  * the energy mostly 1.5-6 kHz (about two thirds), some body at 0.6-1.5 kHz, little above 6 kHz
  * about 120-260 sharp peaks a second

So each crackle is a cluster of micro-clicks, each click a tiny impulse ringing through two or three
paper resonances (1-4.5 kHz, quickly damped); between them, a faint rustle.
Run: python3 tools/leaf_sound.py app/src/main/res/raw   (prints its measurements)
"""
import numpy as np, wave, sys, os

SR = 44100

def band(x, lo, hi, order=4):
    X = np.fft.rfft(x); f = np.fft.rfftfreq(len(x), 1 / SR)
    m = 1 / (1 + (lo / np.maximum(f, 1)) ** order) / (1 + (f / hi) ** order)
    return np.fft.irfft(X * m, len(x))

def click(r, n_ms=7.0):
    """One micro-click: an impulse ringing through a few damped paper resonances."""
    n = int(SR * n_ms / 1000); t = np.arange(n) / SR
    y = np.zeros(n)
    for _ in range(r.integers(2, 4)):
        fc = np.exp(r.uniform(np.log(1000), np.log(4800)))
        decay = r.uniform(0.0008, 0.0025)
        y += r.uniform(.4, 1) * np.sin(2 * np.pi * fc * t + r.uniform(0, 6.3)) * np.exp(-t / decay)
    # a little broadband grit in the attack
    y[:int(SR * .0006)] += r.standard_normal(int(SR * .0006)) * .5
    return y

def crackle(r, length_ms, clicks):
    n = int(SR * length_ms / 1000) + int(SR * .008)
    y = np.zeros(n)
    times = np.sort(r.uniform(0, length_ms / 1000, clicks))
    for i, tt in enumerate(times):
        c = click(r) * r.lognormal(0, .5) * (1 - .5 * i / max(1, clicks))   # a crackle fades through
        s = int(tt * SR); e = min(n, s + len(c)); y[s:e] += c[:e - s]
    return y

def take(seed, length=0.62):
    r = np.random.default_rng(seed)
    n = int(SR * length); y = np.zeros(n)
    # The crackles: one as the page lifts, a few as it bends over, one as it lands
    k = r.integers(4, 7)
    starts = [0.012]
    while len(starts) < k - 1:
        starts.append(starts[-1] + r.uniform(.07, .14))
    starts.append(min(starts[-1] + r.uniform(.09, .15), length - .09))
    levels = [r.uniform(.8, 1.0)] + [r.uniform(.3, .65) for _ in range(k - 2)] + [r.uniform(.7, .95)]
    for st, lv in zip(starts, levels):
        c = crackle(r, r.uniform(18, 40), int(r.integers(4, 11)))
        s = int(st * SR); e = min(n, s + len(c)); y[s:e] += c[:e - s] / np.abs(c).max() * lv
    # Between them, faint paper: a soft rustle following the turn, mid-band
    t = np.arange(n) / SR
    env = np.clip(t / .03, 0, 1) * np.clip((starts[-1] + .04 - t) / .12, 0, 1)
    rustle = band(r.standard_normal(n), 900, 5000) * env
    y += rustle / np.abs(rustle).max() * .06
    # A breath of air as the page moves: soft, low-mid, under the whole turn (paper body, not a knock)
    air = band(r.standard_normal(n), 250, 800, order=2) * env
    y += air / np.abs(air).max() * .05
    y += band(r.standard_normal(n), 6000, 11000) * env * .012 * np.abs(y).max() / .7   # and a little sheen
    y = band(y, 220, 11000, order=2)                      # body kept, no thud, no hiss
    y *= np.minimum(1, (n - np.arange(n)) / (.03 * SR))
    return (y / np.abs(y).max() * .7 * 32767).astype(np.int16)

def measure(x):
    x = x.astype(float); X = np.abs(np.fft.rfft(x)) ** 2; f = np.fft.rfftfreq(len(x), 1 / SR); tot = X.sum()
    bands = [(0, 600), (600, 1500), (1500, 3000), (3000, 6000), (6000, 12000)]
    hp = np.fft.irfft(np.fft.rfft(x) * (f > 2000), len(x)); e = np.abs(hp)
    thr = e.std() * 3.5; peaks = np.sum((e[1:-1] > thr) & (e[1:-1] >= e[:-2]) & (e[1:-1] >= e[2:]))
    return '  '.join('%d-%dHz %.0f%%' % (lo, hi, 100 * X[(f >= lo) & (f < hi)].sum() / tot) for lo, hi in bands) + \
        '  peaks/s %.0f' % (peaks / (len(x) / SR))

if __name__ == "__main__":
    out = sys.argv[1]; os.makedirs(out, exist_ok=True)
    for i, seed in enumerate([5, 17, 31]):
        y = take(seed, length=.58 + .05 * i)
        w = wave.open(f"{out}/leaf_{i + 1}.wav", "wb"); w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes(y.tobytes()); w.close()
        print(f"leaf_{i + 1}: {len(y) / SR:.2f}s  {measure(y)}")
