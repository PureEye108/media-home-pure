// Player Pure - DSD helpers: DSD to PCM conversion (for DACs without DoP) and DoP packing.
#pragma once
#include <stdint.h>
#include <vector>

namespace pp {

/**
 * Converts DSD (1 byte per channel per frame, MSB = oldest bit) to 24-bit PCM in int32 MSB-aligned samples.
 * Linear-phase FIR low-pass (Blackman-Harris windowed sinc) evaluated with 8-bit lookup tables, then decimation.
 * Gain +3 dB: the SACD 0 dB reference (50% modulation) lands at -3 dBFS and the +3 dB SACD maximum at full scale.
 */
class DsdToPcm {
public:
    DsdToPcm(int dsdRate, int channels, int outRate);
    bool ok() const { return decimBytes_ > 0; }
    int outRate() const { return outRate_; }
    /** Converts srcFrames DSD frames; returns the number of PCM frames written to dst. */
    int process(const uint8_t* src, int srcFrames, int32_t* dst);
    void reset();
    /** PCM frames produced per DSD frame is 1/decimBytes. */
    int decimBytes() const { return decimBytes_; }
    /** The low-pass filter (normalised to unity gain, without the +3 dB), or empty if the rates don't divide. */
    static std::vector<double> design(int dsdRate, int outRate);
private:
    int channels_, outRate_, decimBytes_ = 0, nBytes_ = 0;
    std::vector<float> lut_;                   // [nBytes][256]
    std::vector<uint8_t> hist_;               // per channel, ring of nBytes (stored twice for contiguous reads)
    int histPos_ = 0, phase_ = 0;
    uint32_t rng_ = 0x12345678;
};

/**
 * DoP (DSD over PCM v1.1). Each PCM frame carries 16 DSD bits per channel in a 24-bit word: marker byte
 * (0x05/0xFA, alternating every frame) then the older DSD byte then the newer one. Input is DSD frames
 * (one byte per channel, interleaved); two DSD frames make one DoP frame. Output is int32 MSB-aligned.
 */
struct DopPacker {
    bool markerHigh = false;   // next marker: false -> 0x05, true -> 0xFA
    void pack(const uint8_t* src, int dopFrames, int channels, int32_t* dst);
    /** DoP silence (DSD idle pattern 0x69) with continuing markers. */
    void silence(int dopFrames, int channels, int32_t* dst);
};

/**
 * Native DSD for XMOS-based DACs (Topping and others): the DAC's "raw data" setting takes 32-bit slots holding
 * 4 DSD bytes of one channel (32 DSD samples), oldest byte first on the wire (ALSA calls this DSD_U32_BE).
 * Input is DSD frames (one byte per channel, interleaved); four DSD frames make one output frame.
 * [swap] sends the newest byte first instead, for DACs that expect that order.
 */
struct NativeDsdPacker {
    bool swap = false;
    void pack(const uint8_t* src, int outFrames, int channels, uint8_t* dst) const;
    /** DSD idle pattern (0x69) for [outFrames] frames. */
    static void silence(int outFrames, int channels, uint8_t* dst);
};

} // namespace pp
