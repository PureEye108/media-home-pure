// Player Pure host tests: decoders must match ffmpeg bit for bit; DSD paths, DoP and packet timing checks.
// Build: see run.sh
#include "pp_decoder.h"
#include "pp_dsd.h"
#include "pp_usb.h"
#include <fcntl.h>
#include <math.h>
#include <stdio.h>
#include <string>
#include <vector>

using namespace pp;
static int failures = 0;
#define CHECK(c, ...) do { if (!(c)) { failures++; printf("  FAIL: " __VA_ARGS__); printf("\n"); } } while (0)

static std::vector<uint8_t> slurp(const std::string& p) {
    std::vector<uint8_t> v; FILE* f = fopen(p.c_str(), "rb"); if (!f) return v;
    fseek(f, 0, SEEK_END); v.resize(ftell(f)); fseek(f, 0, SEEK_SET);
    if (fread(v.data(), 1, v.size(), f) != v.size()) v.clear();
    fclose(f); return v;
}

static Decoder* open(const std::string& p, std::string& err) {
    int fd = ::open(p.c_str(), O_RDONLY);
    if (fd < 0) { err = "missing"; return nullptr; }
    return openDecoder(new FdIo(fd), err);
}

static void pcmCase(const std::string& dir, const char* name, int rate, int ch, int bits, int codec) {
    std::string err; Decoder* d = open(dir + "/" + name, err);
    printf("%-22s ", name);
    if (!d) { failures++; printf("FAIL open: %s\n", err.c_str()); return; }
    auto ref = slurp(dir + "/" + name + ".ref");
    const int32_t* r = (const int32_t*) ref.data();
    int64_t refFrames = (int64_t) ref.size() / 4 / ch;
    CHECK(d->rate == rate && d->channels == ch && d->bits == bits && d->codec == codec,
          "format %d/%d/%d codec %d", d->rate, d->channels, d->bits, d->codec);
    CHECK(d->totalFrames == refFrames, "totalFrames %lld vs ref %lld", (long long) d->totalFrames, (long long) refFrames);
    std::vector<int32_t> buf(4096 * ch);
    int64_t pos = 0, mism = 0;
    for (;;) {
        int64_t n = d->read(buf.data(), 4096);
        if (n <= 0) break;
        for (int64_t i = 0; i < n * ch; i++) if (pos * ch + i < (int64_t) ref.size() / 4 && buf[i] != r[pos * ch + i]) mism++;
        pos += n;
    }
    CHECK(pos == refFrames && mism == 0, "decoded %lld frames, %lld samples differ", (long long) pos, (long long) mism);
    // seek into the middle and compare again
    int64_t at = refFrames / 3 + 12345;
    CHECK(d->seek(at), "seek");
    int64_t n = d->read(buf.data(), 2000), bad = 0;
    for (int64_t i = 0; i < n * ch; i++) if (buf[i] != r[at * ch + i]) bad++;
    CHECK(n == 2000 && bad == 0, "after seek: %lld frames, %lld differ", (long long) n, (long long) bad);
    printf("%s (%lld frames, %d-bit %d Hz)\n", failures ? "" : "bit-exact", (long long) pos, bits, rate);
    delete d;
}

static std::vector<uint8_t> readAllDsd(Decoder* d) {
    std::vector<uint8_t> out, buf(8192 * d->channels);
    for (;;) { int64_t n = d->read(buf.data(), 8192); if (n <= 0) break; out.insert(out.end(), buf.begin(), buf.begin() + n * d->channels); }
    return out;
}

static double toneLevel(const std::vector<int32_t>& pcm, int ch, int c, int rate, double freq, double* residualDb) {
    // least-squares fit of a sine at freq over the steady part; returns amplitude (full scale = 1)
    size_t n = pcm.size() / ch, s0 = n / 4, s1 = n - n / 8;
    double sc = 0, ss = 0, cc = 0, xs = 0, xc = 0, ex = 0;
    for (size_t i = s0; i < s1; i++) {
        double x = pcm[i * ch + c] / 2147483648.0, w = 2 * M_PI * freq * i / rate;
        double si = sin(w), co = cos(w);
        ss += si * si; cc += co * co; sc += si * co; xs += x * si; xc += x * co; ex += x * x;
    }
    double det = ss * cc - sc * sc, a = (xs * cc - xc * sc) / det, b = (xc * ss - xs * sc) / det;
    double fitE = 0;
    for (size_t i = s0; i < s1; i++) {
        double x = pcm[i * ch + c] / 2147483648.0, w = 2 * M_PI * freq * i / rate;
        double e = x - a * sin(w) - b * cos(w); fitE += e * e;
    }
    double amp = sqrt(a * a + b * b);
    *residualDb = 10 * log10((fitE / (s1 - s0)) / (amp * amp / 2));
    return amp;
}

static void dsdCases(const std::string& dir) {
    std::string err;
    Decoder* dsf = open(dir + "/dsd64.dsf", err); printf("%-22s ", "dsd64.dsf");
    if (!dsf) { failures++; printf("FAIL %s\n", err.c_str()); return; }
    Decoder* dff = open(dir + "/dsd64.dff", err);
    if (!dff) { failures++; printf("FAIL dff %s\n", err.c_str()); delete dsf; return; }
    auto raw = slurp(dir + "/dsd64.raw");
    CHECK(dsf->dsd && dsf->rate == 2822400 && dsf->channels == 2 && dsf->codec == CODEC_DSF, "dsf format");
    CHECK(dff->dsd && dff->rate == 2822400 && dff->channels == 2 && dff->codec == CODEC_DFF, "dff format");
    auto a = readAllDsd(dsf), b = readAllDsd(dff);
    CHECK(a == raw, "DSF bytes differ from source (%zu vs %zu)", a.size(), raw.size());
    CHECK(b == raw, "DFF bytes differ from source (%zu vs %zu)", b.size(), raw.size());
    CHECK(dsf->seek(100000) && dff->seek(100000), "dsd seek");
    std::vector<uint8_t> x(2000 * 2), y(2000 * 2);
    dsf->read(x.data(), 2000); dff->read(y.data(), 2000);
    CHECK(x == y && memcmp(x.data(), raw.data() + 200000, 4000) == 0, "dsd seek data");
    printf("DSF and DFF identical to the source bitstream\n");

    for (int outRate : {176400, 88200, 352800}) {
        DsdToPcm conv(2822400, 2, outRate);
        CHECK(conv.ok(), "converter %d", outRate);
        if (!conv.ok()) continue;
        std::vector<int32_t> pcm(raw.size() / 2 / conv.decimBytes() * 2 + 16);
        int n = conv.process(raw.data(), (int) raw.size() / 2, pcm.data());
        pcm.resize((size_t) n * 2);
        double rl, rr;
        double l = toneLevel(pcm, 2, 0, outRate, 1000, &rl), r = toneLevel(pcm, 2, 1, outRate, 3000, &rr);
        printf("%-22s DSD64 -> %d Hz: 1 kHz at %.2f dBFS (resid %.1f dB), 3 kHz at %.2f dBFS (resid %.1f dB)\n", "dsd2pcm",
               outRate, 20 * log10(l), rl, 20 * log10(r), rr);
        // input 0.5 (SACD 0 dB) + 3 dB gain = -3 dBFS; 0.25 -> -9 dBFS
        CHECK(fabs(20 * log10(l) + 3.0) < 0.3 && fabs(20 * log10(r) + 9.0) < 0.3, "levels");
        CHECK(rl < -45 && rr < -40, "noise/distortion too high");   // the test file's 2nd-order modulator limits this
        // the filter itself: flat to 20 kHz, and everything that would fold back into 0-20 kHz well down
        auto h = DsdToPcm::design(2822400, outRate);
        auto mag = [&](double f) { double re = 0, im = 0; for (size_t i = 0; i < h.size(); i++) { double w = 2 * M_PI * f * i / 2822400.0; re += h[i] * cos(w); im -= h[i] * sin(w); } return 20 * log10(sqrt(re * re + im * im)); };
        double p20 = mag(20000), worst = -300;
        for (double f = outRate - 20000.0; f <= 1411200.0; f += 997.0) { double m = mag(f); if (m > worst) worst = m; }
        printf("%-22s filter: %.3f dB at 20 kHz, aliasing into 0-20 kHz below %.1f dB\n", "", p20, worst);
        CHECK(p20 > -0.5 && worst < -85, "filter response");
    }
    delete dsf; delete dff;
}

static void dopCase() {
    DopPacker p; uint8_t src[8] = {0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88};   // 4 DSD frames, 2 channels
    int32_t out[4];
    p.pack(src, 2, 2, out);
    CHECK((uint32_t) out[0] == 0x05113300u && (uint32_t) out[1] == 0x05224400u, "DoP frame 0: %08x %08x", out[0], out[1]);
    CHECK((uint32_t) out[2] == 0xFA557700u && (uint32_t) out[3] == 0xFA668800u, "DoP frame 1: %08x %08x", out[2], out[3]);
    p.silence(1, 2, out);
    CHECK((uint32_t) out[0] == 0x05696900u, "DoP silence marker continues");
    printf("%-22s markers alternate, idle pattern ok\n", "DoP");
}

static void nativeDsdCase() {
    // 8 DSD frames, 2 channels: L bytes 0x10..0x17, R bytes 0x20..0x27 -> 2 output frames of 4 bytes per channel
    uint8_t src[16];
    for (int k = 0; k < 8; k++) { src[2 * k] = (uint8_t) (0x10 + k); src[2 * k + 1] = (uint8_t) (0x20 + k); }
    uint8_t out[16];
    NativeDsdPacker p;
    p.pack(src, 2, 2, out);
    const uint8_t want[16] = {0x10, 0x11, 0x12, 0x13, 0x20, 0x21, 0x22, 0x23, 0x14, 0x15, 0x16, 0x17, 0x24, 0x25, 0x26, 0x27};
    CHECK(memcmp(out, want, 16) == 0, "native DSD order (oldest byte first, L then R)");
    p.swap = true; p.pack(src, 1, 2, out);
    const uint8_t sw[8] = {0x13, 0x12, 0x11, 0x10, 0x23, 0x22, 0x21, 0x20};
    CHECK(memcmp(out, sw, 8) == 0, "native DSD swapped order");
    NativeDsdPacker::silence(1, 2, out);
    CHECK(out[0] == 0x69 && out[7] == 0x69, "native DSD idle pattern");
    printf("%-22s byte order and idle pattern ok\n", "Native DSD");
}

static void clockCase() {
    const int rates[] = {44100, 48000, 88200, 96000, 176400, 192000, 352800, 384000, 705600, 768000};
    for (int r : rates) {
        PacketClock c; c.init(r, true, 0, 1024);
        long sum = 0; for (int i = 0; i < 8000; i++) sum += c.next();
        CHECK(labs(sum - r) <= 1, "HS %d Hz: %ld frames per second", r, sum);
        PacketClock f; f.init(r, false, 0, 1024);
        sum = 0; for (int i = 0; i < 1000; i++) sum += f.next();
        CHECK(labs(sum - r) <= 1, "FS %d Hz: %ld", r, sum);
    }
    // asynchronous DAC asking for slightly more (44100.5 Hz), high speed 16.16
    PacketClock c; c.init(44100, true, 0, 64);
    uint32_t fb = (uint32_t) llround(44100.5 / 8000.0 * 65536.0);
    uint8_t p[4] = {(uint8_t) fb, (uint8_t) (fb >> 8), (uint8_t) (fb >> 16), (uint8_t) (fb >> 24)};
    CHECK(c.feedback(p, 4) && c.shift == 0, "HS 16.16 feedback accepted");
    long sum = 0; for (int i = 0; i < 16000; i++) sum += c.next();
    CHECK(labs(sum - 88201) <= 1, "HS feedback rate: %ld per 2 s", sum);
    // full speed 10.14 in 3 bytes
    PacketClock f; f.init(48000, false, 0, 64);
    uint32_t v = (uint32_t) llround(48.0 * 16384.0);
    uint8_t q[3] = {(uint8_t) v, (uint8_t) (v >> 8), (uint8_t) (v >> 16)};
    CHECK(f.feedback(q, 3) && f.shift == 2, "FS 10.14 feedback shift %d", f.shift);
    // a DAC sending 16.16 per 1 ms frame at high speed (known quirk): auto-detected
    PacketClock g; g.init(96000, true, 0, 64);
    uint32_t w = (uint32_t) (96.0 * 65536.0);
    uint8_t z[4] = {(uint8_t) w, (uint8_t) (w >> 8), (uint8_t) (w >> 16), (uint8_t) (w >> 24)};
    CHECK(g.feedback(z, 4) && g.shift == -3, "HS per-frame quirk shift %d", g.shift);
    // service interval 2 microframes per packet
    PacketClock h; h.init(192000, true, 1, 128);
    sum = 0; for (int i = 0; i < 4000; i++) sum += h.next();
    CHECK(labs(sum - 192000) <= 1, "interval 2: %ld", sum);
    printf("%-22s packet sizes and feedback formats ok\n", "PacketClock");
}

int main(int argc, char** argv) {
    std::string dir = argc > 1 ? argv[1] : "data";
    pcmCase(dir, "f16_44.flac", 44100, 2, 16, CODEC_FLAC);
    pcmCase(dir, "id3_f16_44.flac", 44100, 2, 16, CODEC_FLAC);
    pcmCase(dir, "f24_96.flac", 96000, 2, 24, CODEC_FLAC);
    pcmCase(dir, "f24_192_mono.flac", 192000, 1, 24, CODEC_FLAC);
    pcmCase(dir, "f24_352.flac", 352800, 2, 24, CODEC_FLAC);
    pcmCase(dir, "w24_96.wav", 96000, 2, 24, CODEC_WAV);
    pcmCase(dir, "a16_44.aiff", 44100, 2, 16, CODEC_AIFF);
    pcmCase(dir, "a24_48.aiff", 48000, 2, 24, CODEC_AIFF);
    pcmCase(dir, "alac16_44.m4a", 44100, 2, 16, CODEC_ALAC);
    pcmCase(dir, "alac24_96.m4a", 96000, 2, 24, CODEC_ALAC);
    { std::string err; Decoder* d = open(dir + "/aac44.m4a", err);
      CHECK(!d && err == "not-lossless", "AAC must be handed to Android (%s)", err.c_str());
      printf("%-22s handed to Android's decoder\n", "aac44.m4a"); delete d; }
    dsdCases(dir);
    dopCase();
    nativeDsdCase();
    clockCase();
    printf(failures ? "\n%d FAILURE(S)\n" : "\nALL PASSED\n", failures);
    return failures ? 1 : 0;
}
