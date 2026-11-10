# Writes the same 1-second DSD64 stereo signal as DSF (LSB-first, 4096-byte blocks) and DFF (MSB-first).
import math, struct, sys
R = 2822400; N = R  # 1 second
def modulate(freq, amp):
    out = bytearray(N // 8); i1 = i2 = 0.0; y = 1.0; byte = 0
    w = 2 * math.pi * freq / R
    for n in range(N):
        x = amp * math.sin(w * n)
        i1 += x - y; i2 += i1 - y
        y = 1.0 if i2 >= 0 else -1.0
        byte = (byte << 1) | (1 if y > 0 else 0)
        if n % 8 == 7: out[n // 8] = byte; byte = 0
    return out
L = modulate(1000, 0.5); Rch = modulate(3000, 0.25)
nb = len(L)
# DFF (MSB first, byte interleaved)
inter = bytearray(nb * 2); inter[0::2] = L; inter[1::2] = Rch
def chunk(cid, data): return cid + struct.pack('>Q', len(data)) + data + (b'\0' if len(data) & 1 else b'')
prop = b'SND ' + chunk(b'FS  ', struct.pack('>I', R)) + chunk(b'CHNL', struct.pack('>H', 2) + b'SLFTSRGT') + chunk(b'CMPR', b'DSD ' + b'\x0enot compressed\0')
body = b'DSD ' + chunk(b'FVER', struct.pack('>I', 0x01050000)) + chunk(b'PROP', prop) + chunk(b'DSD ', bytes(inter))
open('dsd64.dff', 'wb').write(b'FRM8' + struct.pack('>Q', len(body)) + body)
# DSF (LSB first, 4096-byte blocks per channel)
def rev(b): return int('{:08b}'.format(b)[::-1], 2)
tab = bytes(rev(i) for i in range(256))
Lr = L.translate(tab); Rr = Rch.translate(tab)
B = 4096; nblk = (nb + B - 1) // B
data = bytearray()
for k in range(nblk):
    for ch in (Lr, Rr):
        blk = ch[k*B:(k+1)*B]; data += blk + b'\0' * (B - len(blk))
fmt = struct.pack('<IIIIIIQII', 1, 0, 2, 2, R, 1, N, B, 0)
datachunk = b'data' + struct.pack('<Q', 12 + len(data)) + data
total = 28 + 12 + len(fmt) + len(datachunk)
hdr = b'DSD ' + struct.pack('<QQQ', 28, total, 0)
open('dsd64.dsf', 'wb').write(hdr + b'fmt ' + struct.pack('<Q', 12 + len(fmt)) + fmt + datachunk)
open('dsd64.raw', 'wb').write(inter)   # expected decoder output
print('ok', nb)
