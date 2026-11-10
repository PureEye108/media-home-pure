// Player Pure - lossless decoders. Output is exactly the stored samples (no dither, no volume, no resampling).
#pragma once
#include "pp_io.h"
#include <string>

namespace pp {

enum Codec { CODEC_NONE = 0, CODEC_FLAC = 1, CODEC_WAV = 2, CODEC_AIFF = 3, CODEC_ALAC = 4, CODEC_DSF = 5, CODEC_DFF = 6 };

/**
 * PCM decoders write interleaved int32 samples, MSB-aligned (a 16-bit sample s becomes s << 16), so any bit depth
 * passes through unchanged. DSD decoders (dsd == true) write one byte per channel per frame, interleaved, oldest bit
 * first in the MSB; rate is the DSD bit rate (2822400 for DSD64) and a frame is 8 DSD samples.
 */
struct Decoder {
    int rate = 0, channels = 0, bits = 0, codec = CODEC_NONE;
    bool dsd = false, isFloat = false;
    int64_t totalFrames = 0;   // -1 if unknown
    virtual ~Decoder() {}
    virtual int64_t read(void* dst, int64_t frames) = 0;   // frames read; 0 = end; < 0 = error
    virtual bool seek(int64_t frame) = 0;
    int frameBytes() const { return dsd ? channels : channels * 4; }
};

/** Opens a decoder; takes ownership of io. Returns null with err set (err "not-lossless" = let Android decode it). */
Decoder* openDecoder(Io* io, std::string& err);

} // namespace pp
