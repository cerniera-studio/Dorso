"""
Dorso's page-turn sounds, synthesised. Paper isn't a hiss: it is hundreds of tiny dry crackles as
the fibres flex. So each take is built from grains, not smooth noise:

  lift    0-60 ms     a few sharp crackles as the page peels off the one beneath
  sweep   40-300 ms   a dense rustle of grains, thickest mid-turn, over a faint airy brush
  settle  ~300 ms     a short high whisper of paper sliding onto paper, and a tick or two

Everything sits above ~700 Hz: a page landing makes no thud.
Run: python3 tools/leaf_sound.py app/src/main/res/raw
"""
import numpy as np, wave, sys, os

SR = 44100

def band(x, lo, hi):
    X = np.fft.rfft(x); f = np.fft.rfftfreq(len(x), 1 / SR)
    m = np.ones_like(f)
    m *= 1 / (1 + (lo / np.maximum(f, 1)) ** 4)        # 4th-order-ish high-pass
    m *= 1 / (1 + (f / hi) ** 4)                        # and low-pass
    return np.fft.irfft(X * m, len(x))

def grains(r, n, rate, lo, hi, dur_ms=(0.15, 1.4), sigma=0.85):
    """Poisson crackles: rate[t] grains per second, each a tiny decaying burst of band-limited noise."""
    out = np.zeros(n)
    bank = band(r.standard_normal(SR), lo, hi); bank /= np.abs(bank).max()
    lam = max(rate.max(), 1.0)                          # thinning: candidates at the top rate, kept in proportion
    t = 0
    while True:
        t += int(r.exponential(SR / lam)) + 1
        if t >= n: break
        if r.random() > rate[t] / lam: continue
        d = int(SR * r.uniform(*dur_ms) / 1000) + 8
        s = r.integers(0, SR - d)
        g = bank[s:s + d] * np.exp(-np.arange(d) / (d / 4.5)) * r.lognormal(0, sigma)
        e = min(n, t + d); out[t:e] += g[:e - t]
    return out

def bump(t, start, peak, end):
    up = np.clip((t - start) / (peak - start), 0, 1); dn = np.clip((end - t) / (end - peak), 0, 1)
    return np.where(t < peak, np.sin(up * np.pi / 2) ** 2, np.sin(dn * np.pi / 2) ** 2)

def take(seed, length=0.42, settle=0.30):
    r = np.random.default_rng(seed)
    n = int(SR * length); t = np.arange(n) / SR
    # Lift: a handful of sharp crackles
    lift = grains(r, n, 220 * bump(t, 0.0, 0.015, 0.065), 2200, 9500, (0.1, 0.6), 1.0)
    # Sweep: the rustle, thickest mid-turn
    sweep_env = bump(t, 0.03, 0.15 + 0.03 * r.random(), settle + 0.01)
    sweep = grains(r, n, 2600 * sweep_env, 1500, 8500, (0.2, 1.6), 0.8)
    sweep += 0.5 * grains(r, n, 900 * sweep_env, 3500, 12000, (0.08, 0.5), 0.9)  # finer, brighter grit
    # A faint airy brush under it, its colour rising and falling with the page's speed
    air = band(r.standard_normal(n), 1100, 5500) * sweep_env
    # Settle: paper sliding onto paper, high and short, then a tick or two
    sl = band(r.standard_normal(n), 3000, 9000) * bump(t, settle - 0.01, settle + 0.012, settle + 0.07)
    ticks = grains(r, n, 120 * bump(t, settle, settle + 0.01, settle + 0.05), 2500, 9000, (0.1, 0.4), 0.6)
    y = (lift / max(np.abs(lift).max(), 1e-9) * 0.35 + sweep / np.abs(sweep).max() * 0.85 +
         air / np.abs(air).max() * 0.10 + sl / np.abs(sl).max() * 0.10 + ticks / max(np.abs(ticks).max(), 1e-9) * 0.08)
    y = band(y, 700, 14000)                              # no thud
    y *= np.minimum(1, np.arange(n) / (0.002 * SR)) * np.minimum(1, (n - np.arange(n)) / (0.04 * SR))
    return (y / np.abs(y).max() * 0.6 * 32767).astype(np.int16)

if __name__ == "__main__":
    out = sys.argv[1]; os.makedirs(out, exist_ok=True)
    for i, seed in enumerate([11, 29, 53]):
        w = wave.open(f"{out}/leaf_{i + 1}.wav", "wb"); w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes(take(seed, length=0.40 + 0.03 * i, settle=0.28 + 0.025 * i).tobytes()); w.close()
    print("ok")
