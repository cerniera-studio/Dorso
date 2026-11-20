# Dorso's page sounds

Real recordings of book pages, from Kenney's **RPG Audio** pack (kenney.nl/assets/rpg-audio),
released under **Creative Commons CC0** (public domain; credit not required, given gladly:
sounds by Kenney.nl). The originals are kept here (`bookFlip2.ogg`, `bookFlip3.ogg`).

Three synthesised attempts (filtered noise; grains; resonant micro-clicks matched to a reference
recording's measurements) all sounded mechanical on the Fold, so Dorso uses real paper.

Each is equalised towards the reference recording Mechack chose (magazine pages: energy mostly
1.5-6 kHz, body around 1 kHz, little above 6 kHz), then given a 2 ms fade in and 15 ms fade out
and peak-normalised to 0.6:

    ffmpeg -i bookFlip2.ogg -ac 1 -ar 44100 \
      -af "highpass=f=180:poles=2,equalizer=f=2200:t=q:w=1.0:g=4,equalizer=f=1100:t=q:w=1.2:g=2,equalizer=f=8500:t=q:w=1.0:g=-6,lowpass=f=12000" \
      leaf_1.wav            # likewise bookFlip3 -> leaf_2.wav

`leaf_1` is a full turn (lift, rustle, crackles as it goes over, 0.43 s); `leaf_2` a quick flip
(0.23 s). Paper plays them at slightly varied pitch.
