import numpy as np, wave, sys, os
SR=44100
def band(x, lo, hi, tilt=0.0):
    X=np.fft.rfft(x); f=np.fft.rfftfreq(len(x),1/SR)
    m=((f>=lo)&(f<=hi)).astype(float)
    # soft edges
    m=np.where(f<lo, np.exp(-((lo-f)/(lo*0.35+1))**2), m)
    m=np.where(f>hi, np.exp(-((f-hi)/(hi*0.25))**2), m)
    m*= (np.maximum(f,1)/1000.0)**tilt
    return np.fft.irfft(X*m, len(x))
def leaf(seed, dur=0.52, land=0.40):
    r=np.random.default_rng(seed); n=int(SR*dur); t=np.arange(n)/SR
    # the swish: air over paper, brighter as it speeds mid-turn
    sw=band(r.standard_normal(n), 700, 7500, tilt=-0.35)
    peak=0.17+0.03*r.random()
    env=np.where(t<peak, (t/peak)**1.6, np.exp(-(np.maximum(t-peak,0)/0.11)**1.3))
    env*=np.clip((land+0.02-t)/0.06,0,1)**0.5*0.85+0.15
    flutter=band(r.standard_normal(n), 8, 40); flutter/=np.abs(flutter).max()
    sw*=env*(0.75+0.25*flutter)
    # paper crackle: sparse clicks, high and short
    cr=np.zeros(n); k=r.choice(n, 26, replace=False)
    cr[k]=r.standard_normal(26)*(env[k]**0.7)
    cr=band(cr, 2500, 11000)
    # the landing: a soft low flap
    ln=np.zeros(n); i0=int(land*SR); m=n-i0
    lt=np.arange(m)/SR
    flap=band(r.standard_normal(n), 120, 900, tilt=-0.6)[:m]*np.exp(-lt/0.028)*(1-np.exp(-lt/0.003))
    ln[i0:]=flap
    y=sw/np.abs(sw).max()*0.55 + cr/max(np.abs(cr).max(),1e-9)*0.22 + ln/np.abs(ln).max()*0.42
    fade=np.minimum(1, (n-np.arange(n))/(0.03*SR)); y*=fade
    y=y/np.abs(y).max()*0.6
    return (y*32767).astype(np.int16)
out=sys.argv[1]; os.makedirs(out, exist_ok=True)
for i,seed in enumerate([7,21,48]):
    w=wave.open(f"{out}/leaf_{i+1}.wav","wb"); w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
    w.writeframes(leaf(seed, dur=0.52+0.04*i, land=0.38+0.03*i).tobytes()); w.close()
print("ok")
