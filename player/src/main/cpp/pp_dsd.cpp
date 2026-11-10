// Player Pure - DSD to PCM conversion and DoP packing.
#include "pp_dsd.h"
#include <math.h>
#include <string.h>

namespace pp {

std::vector<double> DsdToPcm::design(int dsdRate, int outRate) {
    std::vector<double> h;
    if (dsdRate <= 0 || outRate <= 0 || dsdRate % (outRate * 8) != 0) return h;
    int decimBytes = dsdRate / (outRate * 8);
    int n = 32 * decimBytes * 8;
    // Blackman-Harris main lobe is about 8/N wide: put the cut-off below the new Nyquist frequency by half of that.
    double width = 8.0 * dsdRate / n;
    double fc = outRate / 2.0 - width / 2.0 - 1000.0;
    if (fc > 50000.0) fc = 50000.0;
    if (fc < 15000.0) return h;
    h.resize(n);
    double sum = 0, wc = 2.0 * fc / dsdRate, mid = (n - 1) / 2.0;
    for (int i = 0; i < n; i++) {
        double x = i - mid;
        double sinc = x == 0 ? wc : sin(M_PI * wc * x) / (M_PI * x);
        double t = 2.0 * M_PI * i / (n - 1);
        double w = 0.35875 - 0.48829 * cos(t) + 0.14128 * cos(2 * t) - 0.01168 * cos(3 * t);
        h[i] = sinc * w; sum += h[i];
    }
    for (double& v : h) v /= sum;
    return h;
}

DsdToPcm::DsdToPcm(int dsdRate, int channels, int outRate) : channels_(channels), outRate_(outRate) {
    if (channels < 1) return;
    std::vector<double> h = design(dsdRate, outRate);
    if (h.empty()) return;
    int nBytes = (int) h.size() / 8;
    const double gain = 1.4125375446;   // +3 dB
    lut_.assign((size_t) nBytes * 256, 0.f);
    for (int k = 0; k < nBytes; k++)
        for (int v = 0; v < 256; v++) {
            double acc = 0;
            for (int b = 0; b < 8; b++) acc += h[k * 8 + b] * gain * (((v >> (7 - b)) & 1) ? 1.0 : -1.0);
            lut_[(size_t) k * 256 + v] = (float) acc;
        }
    decimBytes_ = dsdRate / (outRate * 8); nBytes_ = nBytes;
    reset();
}

void DsdToPcm::reset() {
    hist_.assign((size_t) channels_ * nBytes_ * 2, 0x69);
    histPos_ = 0; phase_ = 0;
}

int DsdToPcm::process(const uint8_t* src, int srcFrames, int32_t* dst) {
    if (!ok()) return 0;
    int out = 0;
    for (int f = 0; f < srcFrames; f++) {
        for (int c = 0; c < channels_; c++) {
            uint8_t* hc = hist_.data() + (size_t) c * nBytes_ * 2;
            hc[histPos_] = hc[histPos_ + nBytes_] = src[(size_t) f * channels_ + c];
        }
        histPos_ = histPos_ + 1 == nBytes_ ? 0 : histPos_ + 1;
        if (++phase_ < decimBytes_) continue;
        phase_ = 0;
        for (int c = 0; c < channels_; c++) {
            const uint8_t* win = hist_.data() + (size_t) c * nBytes_ * 2 + histPos_;   // oldest first
            const float* lut = lut_.data();
            float acc = 0.f;
            for (int k = 0; k < nBytes_; k++, lut += 256) acc += lut[win[k]];
            // TPDF dither at 24 bits
            rng_ ^= rng_ << 13; rng_ ^= rng_ >> 17; rng_ ^= rng_ << 5; uint32_t r1 = rng_;
            rng_ ^= rng_ << 13; rng_ ^= rng_ >> 17; rng_ ^= rng_ << 5; uint32_t r2 = rng_;
            double d = ((double) r1 + (double) r2) / 4294967296.0 - 1.0;
            double s = floor(acc * 8388607.0 + d + 0.5);
            if (s > 8388607.0) s = 8388607.0;
            if (s < -8388608.0) s = -8388608.0;
            dst[(size_t) out * channels_ + c] = (int32_t) ((uint32_t) (int32_t) s << 8);
        }
        out++;
    }
    return out;
}

void DopPacker::pack(const uint8_t* src, int dopFrames, int channels, int32_t* dst) {
    for (int j = 0; j < dopFrames; j++) {
        uint32_t m = markerHigh ? 0xFAu : 0x05u;
        markerHigh = !markerHigh;
        const uint8_t* a = src + (size_t) 2 * j * channels;
        const uint8_t* b = a + channels;
        for (int c = 0; c < channels; c++)
            dst[(size_t) j * channels + c] = (int32_t) ((m << 24) | ((uint32_t) a[c] << 16) | ((uint32_t) b[c] << 8));
    }
}

void DopPacker::silence(int dopFrames, int channels, int32_t* dst) {
    for (int j = 0; j < dopFrames; j++) {
        uint32_t m = markerHigh ? 0xFAu : 0x05u;
        markerHigh = !markerHigh;
        for (int c = 0; c < channels; c++) dst[(size_t) j * channels + c] = (int32_t) ((m << 24) | 0x696900u);
    }
}

void NativeDsdPacker::pack(const uint8_t* src, int outFrames, int channels, uint8_t* dst) const {
    for (int j = 0; j < outFrames; j++) {
        const uint8_t* f = src + (size_t) 4 * j * channels;
        for (int c = 0; c < channels; c++) {
            uint8_t* o = dst + ((size_t) j * channels + c) * 4;
            if (!swap) { o[0] = f[c]; o[1] = f[channels + c]; o[2] = f[2 * channels + c]; o[3] = f[3 * channels + c]; }
            else       { o[3] = f[c]; o[2] = f[channels + c]; o[1] = f[2 * channels + c]; o[0] = f[3 * channels + c]; }
        }
    }
}

void NativeDsdPacker::silence(int outFrames, int channels, uint8_t* dst) {
    memset(dst, 0x69, (size_t) outFrames * channels * 4);
}

} // namespace pp
