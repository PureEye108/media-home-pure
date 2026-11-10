#!/bin/sh
# Makes the test files for run.sh (needs ffmpeg and python3). Output: hosttest/data
set -e
cd "$(dirname "$0")"; mkdir -p data; cd data
ffmpeg -loglevel error -y -f lavfi -i "aevalsrc=0.6*sin(2*PI*440*t)+0.3*sin(2*PI*5000*t)+0.05*(random(0)-0.5)|0.5*sin(2*PI*660*t)+0.05*(random(1)-0.5):s=96000:d=6" -c:a pcm_f64le src96.wav
ffmpeg -loglevel error -y -i src96.wav -ar 44100 -c:a flac -sample_fmt s16 f16_44.flac
ffmpeg -loglevel error -y -i src96.wav -c:a flac -sample_fmt s32 -bits_per_raw_sample 24 f24_96.flac
ffmpeg -loglevel error -y -i src96.wav -ar 192000 -c:a flac -sample_fmt s32 -bits_per_raw_sample 24 -ac 1 f24_192_mono.flac
ffmpeg -loglevel error -y -i src96.wav -c:a pcm_s24le w24_96.wav
ffmpeg -loglevel error -y -i src96.wav -ar 44100 -c:a pcm_s16be a16_44.aiff
ffmpeg -loglevel error -y -i src96.wav -ar 48000 -c:a pcm_s24be a24_48.aiff
ffmpeg -loglevel error -y -i src96.wav -ar 44100 -c:a alac -sample_fmt s16p alac16_44.m4a
ffmpeg -loglevel error -y -i src96.wav -c:a alac -sample_fmt s32p alac24_96.m4a
ffmpeg -loglevel error -y -i src96.wav -ar 352800 -c:a flac -sample_fmt s32 -bits_per_raw_sample 24 f24_352.flac
ffmpeg -loglevel error -y -i src96.wav -ar 44100 -c:a aac aac44.m4a
python3 - <<'PY'
body = b'TIT2' + (10).to_bytes(4, 'big') + b'\x00\x00' + b'\x00Test song'
n = len(body)
hdr = b'ID3\x03\x00\x00' + bytes([(n >> 21) & 127, (n >> 14) & 127, (n >> 7) & 127, n & 127])
open('id3_f16_44.flac', 'wb').write(hdr + body + open('f16_44.flac', 'rb').read())
PY
for f in f16_44.flac f24_96.flac f24_192_mono.flac w24_96.wav a16_44.aiff a24_48.aiff alac16_44.m4a alac24_96.m4a f24_352.flac id3_f16_44.flac; do
  ffmpeg -loglevel error -y -i $f -f s32le -c:a pcm_s32le $f.ref
done
python3 ../make_dsd.py
echo "test data ready"
