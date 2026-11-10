// Player Pure - lossless decoders: FLAC and WAV/AIFF (dr_libs), ALAC in MP4 (Apple's decoder), DSF and DFF (raw DSD).
#include "pp_decoder.h"

#define DR_FLAC_IMPLEMENTATION
#define DR_FLAC_NO_STDIO
#define DR_FLAC_NO_OGG
#include "third_party/dr_flac.h"
#define DR_WAV_IMPLEMENTATION
#define DR_WAV_NO_STDIO
#include "third_party/dr_wav.h"
#include "ALACDecoder.h"
#include "ALACBitUtilities.h"

#include <vector>
#include <memory>

namespace pp {

static size_t cbRead(void* u, void* b, size_t n) { return ((Io*) u)->read(b, n); }
static int64_t base(Io* io, int origin) { return origin == 0 ? 0 : origin == 1 ? io->tell() : io->size(); }
static drflac_bool32 flacSeek(void* u, int off, drflac_seek_origin o) {
    Io* io = (Io*) u; return io->seek(base(io, o == DRFLAC_SEEK_SET ? 0 : o == DRFLAC_SEEK_CUR ? 1 : 2) + off);
}
static drflac_bool32 flacTell(void* u, drflac_int64* c) { *c = ((Io*) u)->tell(); return DRFLAC_TRUE; }
static drwav_bool32 wavSeek(void* u, int off, drwav_seek_origin o) {
    Io* io = (Io*) u; return io->seek(base(io, o == DRWAV_SEEK_SET ? 0 : o == DRWAV_SEEK_CUR ? 1 : 2) + off);
}
static drwav_bool32 wavTell(void* u, drwav_int64* c) { *c = ((Io*) u)->tell(); return DRWAV_TRUE; }

// ---------------------------------------------------------------- FLAC
struct FlacDecoder : Decoder {
    Io* root; Io* io; drflac* f = nullptr;
    FlacDecoder(Io* r, Io* i) : root(r), io(i) {}
    ~FlacDecoder() override { if (f) drflac_close(f); if (io != root) delete io; delete root; }
    int64_t read(void* dst, int64_t frames) override {
        return (int64_t) drflac_read_pcm_frames_s32(f, (drflac_uint64) frames, (drflac_int32*) dst);
    }
    bool seek(int64_t frame) override { return drflac_seek_to_pcm_frame(f, (drflac_uint64) frame); }
};

// ---------------------------------------------------------------- WAV, AIFF, RF64, W64
struct WavDecoder : Decoder {
    Io* io; drwav w; bool ok = false;
    explicit WavDecoder(Io* i) : io(i) { memset(&w, 0, sizeof(w)); }
    ~WavDecoder() override { if (ok) drwav_uninit(&w); delete io; }
    int64_t read(void* dst, int64_t frames) override {
        return (int64_t) drwav_read_pcm_frames_s32(&w, (drwav_uint64) frames, (drwav_int32*) dst);
    }
    bool seek(int64_t frame) override { return drwav_seek_to_pcm_frame(&w, (drwav_uint64) frame); }
};

// ---------------------------------------------------------------- ALAC in MP4 / M4A
struct Box { uint32_t type; int64_t start, size, body; };   // body = offset of the payload

static bool readBox(Io* io, int64_t pos, int64_t limit, Box& b) {
    uint8_t h[16];
    if (pos + 8 > limit || !io->seek(pos) || !io->readFully(h, 8)) return false;
    int64_t size = be32(h);
    b.type = be32(h + 4); b.start = pos; b.body = pos + 8;
    if (size == 1) { if (!io->readFully(h + 8, 8)) return false; size = (int64_t) be64(h + 8); b.body = pos + 16; }
    else if (size == 0) size = limit - pos;
    if (size < b.body - pos || pos + size > limit) return false;
    b.size = size;
    return true;
}
static constexpr uint32_t FCC(const char* s) { return ((uint32_t) (uint8_t) s[0] << 24) | ((uint32_t) (uint8_t) s[1] << 16) | ((uint32_t) (uint8_t) s[2] << 8) | (uint8_t) s[3]; }

struct Mp4Track {
    bool sound = false, alac = false;
    std::vector<uint8_t> cookie;
    uint32_t timescale = 0;
    std::vector<uint32_t> sizes;            // per sample (packet)
    std::vector<uint64_t> chunkOffsets;
    std::vector<uint32_t> stscFirst, stscCount;
    uint64_t sttsTotal = 0;
};

static bool readAll(Io* io, const Box& b, std::vector<uint8_t>& out, int64_t max = 64 << 20) {
    int64_t n = b.start + b.size - b.body;
    if (n < 0 || n > max) return false;
    out.resize((size_t) n);
    return io->seek(b.body) && io->readFully(out.data(), (size_t) n);
}

static void parseStbl(Io* io, int64_t pos, int64_t end, Mp4Track& t) {
    Box b; std::vector<uint8_t> d;
    while (readBox(io, pos, end, b)) {
        pos = b.start + b.size;
        if (b.type == FCC("stsd")) {
            if (!readAll(io, b, d) || d.size() < 8 + 36) continue;
            uint32_t fmt = be32(d.data() + 12);
            if (fmt == FCC("alac")) {
                t.alac = true;
                size_t entryEnd = 8 + be32(d.data() + 8);
                uint16_t ver = be16(d.data() + 8 + 16);
                size_t child = 8 + 8 + 28 + (ver == 1 ? 16 : ver == 2 ? 36 : 0);
                while (child + 8 <= entryEnd && child + 8 <= d.size()) {
                    uint32_t cs = be32(d.data() + child), ct = be32(d.data() + child + 4);
                    if (cs < 8 || child + cs > d.size()) break;
                    if (ct == FCC("alac")) { t.cookie.assign(d.begin() + child, d.begin() + child + cs); break; }
                    child += cs;
                }
            }
        } else if (b.type == FCC("stts")) {
            if (!readAll(io, b, d) || d.size() < 8) continue;
            uint32_t n = be32(d.data() + 4);
            for (uint32_t i = 0; i < n && 8 + i * 8 + 8 <= d.size(); i++)
                t.sttsTotal += (uint64_t) be32(d.data() + 8 + i * 8) * be32(d.data() + 12 + i * 8);
        } else if (b.type == FCC("stsc")) {
            if (!readAll(io, b, d) || d.size() < 8) continue;
            uint32_t n = be32(d.data() + 4);
            for (uint32_t i = 0; i < n && 8 + i * 12 + 12 <= d.size(); i++) {
                t.stscFirst.push_back(be32(d.data() + 8 + i * 12));
                t.stscCount.push_back(be32(d.data() + 12 + i * 12));
            }
        } else if (b.type == FCC("stsz")) {
            if (!readAll(io, b, d) || d.size() < 12) continue;
            uint32_t fixed = be32(d.data() + 4), n = be32(d.data() + 8);
            if (n > (64u << 20)) continue;
            t.sizes.resize(n);
            for (uint32_t i = 0; i < n; i++) {
                if (fixed) t.sizes[i] = fixed;
                else if (12 + i * 4 + 4 <= d.size()) t.sizes[i] = be32(d.data() + 12 + i * 4);
            }
        } else if (b.type == FCC("stco") || b.type == FCC("co64")) {
            if (!readAll(io, b, d) || d.size() < 8) continue;
            uint32_t n = be32(d.data() + 4);
            bool w = b.type == FCC("co64");
            for (uint32_t i = 0; i < n; i++) {
                size_t o = 8 + (size_t) i * (w ? 8 : 4);
                if (o + (w ? 8 : 4) > d.size()) break;
                t.chunkOffsets.push_back(w ? be64(d.data() + o) : be32(d.data() + o));
            }
        }
    }
}

static void parseContainer(Io* io, int64_t pos, int64_t end, Mp4Track& t, std::vector<Mp4Track>& out) {
    Box b; std::vector<uint8_t> d;
    while (readBox(io, pos, end, b)) {
        pos = b.start + b.size;
        int64_t e = b.start + b.size;
        if (b.type == FCC("trak")) {
            Mp4Track nt; parseContainer(io, b.body, e, nt, out);
            if (nt.sound) out.push_back(std::move(nt));
        } else if (b.type == FCC("mdia") || b.type == FCC("minf")) {
            parseContainer(io, b.body, e, t, out);
        } else if (b.type == FCC("stbl")) {
            parseStbl(io, b.body, e, t);
        } else if (b.type == FCC("hdlr")) {
            if (readAll(io, b, d, 4096) && d.size() >= 12 && be32(d.data() + 8) == FCC("soun")) t.sound = true;
        } else if (b.type == FCC("mdhd")) {
            if (readAll(io, b, d, 4096) && d.size() >= 24) t.timescale = d[0] == 1 ? be32(d.data() + 20) : be32(d.data() + 12);
        }
    }
}

struct AlacDecoder : Decoder {
    Io* io; ALACDecoder dec;
    std::vector<uint64_t> offsets; std::vector<uint32_t> sizes;
    uint32_t frameLength = 4096;
    size_t packet = 0;                      // next packet to decode
    std::vector<uint8_t> in, raw;
    std::vector<int32_t> pcm; size_t pcmPos = 0, pcmFrames = 0;
    explicit AlacDecoder(Io* i) : io(i) {}
    ~AlacDecoder() override { delete io; }

    bool decodeNext() {
        pcmPos = pcmFrames = 0;
        if (packet >= offsets.size()) return false;
        uint32_t sz = sizes[packet];
        in.resize(sz + 8);
        if (!io->seek((int64_t) offsets[packet]) || !io->readFully(in.data(), sz)) return false;
        packet++;
        BitBuffer bb; BitBufferInit(&bb, in.data(), sz);
        uint32_t got = 0;
        int bytesPer = bits == 16 ? 2 : bits == 32 ? 4 : 3;
        raw.resize((size_t) frameLength * channels * bytesPer + 64);
        if (dec.Decode(&bb, raw.data(), frameLength, channels, &got) != 0) return false;
        pcm.resize((size_t) got * channels);
        const uint8_t* r = raw.data();
        for (size_t i = 0; i < (size_t) got * channels; i++) {
            if (bytesPer == 2) pcm[i] = (int32_t) ((uint32_t) (uint16_t) (r[2 * i] | (r[2 * i + 1] << 8)) << 16);
            else if (bytesPer == 3) pcm[i] = (int32_t) (((uint32_t) r[3 * i] << 8) | ((uint32_t) r[3 * i + 1] << 16) | ((uint32_t) r[3 * i + 2] << 24));
            else pcm[i] = (int32_t) le32(r + 4 * i);
        }
        pcmFrames = got;
        return true;
    }
    int64_t read(void* dst, int64_t frames) override {
        int32_t* out = (int32_t*) dst; int64_t done = 0;
        while (done < frames) {
            if (pcmPos >= pcmFrames && !decodeNext()) break;
            size_t take = pcmFrames - pcmPos;
            if ((int64_t) take > frames - done) take = (size_t) (frames - done);
            memcpy(out + done * channels, pcm.data() + pcmPos * channels, take * channels * 4);
            pcmPos += take; done += take;
        }
        return done;
    }
    bool seek(int64_t frame) override {
        packet = (size_t) (frame / frameLength);
        pcmPos = pcmFrames = 0;
        if (packet >= offsets.size()) return frame == totalFrames;
        if (!decodeNext()) return false;
        pcmPos = (size_t) (frame % frameLength);
        if (pcmPos > pcmFrames) pcmPos = pcmFrames;
        return true;
    }
};

static Decoder* openMp4(Io* io, std::string& err) {
    std::vector<Mp4Track> tracks; Mp4Track top;
    int64_t pos = 0, end = io->size(); Box b;
    while (readBox(io, pos, end, b)) {
        if (b.type == FCC("moov")) parseContainer(io, b.body, b.start + b.size, top, tracks);
        pos = b.start + b.size;
    }
    for (auto& t : tracks) {
        if (!t.alac || t.cookie.empty() || t.sizes.empty() || t.chunkOffsets.empty() || t.stscFirst.empty()) continue;
        auto* d = new AlacDecoder(io);
        if (d->dec.Init(t.cookie.data(), (uint32_t) t.cookie.size()) != 0) { d->io = nullptr; delete d; continue; }
        const ALACSpecificConfig& c = d->dec.mConfig;
        d->codec = CODEC_ALAC; d->rate = (int) c.sampleRate; d->channels = c.numChannels; d->bits = c.bitDepth;
        d->frameLength = c.frameLength ? c.frameLength : 4096;
        // sample (packet) file offsets from the chunk table
        size_t s = 0;
        for (size_t ci = 0; ci < t.chunkOffsets.size() && s < t.sizes.size(); ci++) {
            uint32_t chunkNo = (uint32_t) ci + 1, per = t.stscCount[0];
            for (size_t k = 0; k < t.stscFirst.size() && t.stscFirst[k] <= chunkNo; k++) per = t.stscCount[k];
            uint64_t off = t.chunkOffsets[ci];
            for (uint32_t j = 0; j < per && s < t.sizes.size(); j++, s++) {
                d->offsets.push_back(off); d->sizes.push_back(t.sizes[s]); off += t.sizes[s];
            }
        }
        uint64_t frames = t.sttsTotal;
        if (t.timescale && t.timescale != (uint32_t) d->rate) frames = frames * d->rate / t.timescale;
        d->totalFrames = frames ? (int64_t) frames : (int64_t) d->offsets.size() * d->frameLength;
        if (d->channels < 1 || d->rate < 1 || (d->bits != 16 && d->bits != 20 && d->bits != 24 && d->bits != 32)) {
            d->io = nullptr; delete d; err = "unsupported ALAC format"; delete io; return nullptr;
        }
        return d;
    }
    delete io;
    err = "not-lossless";   // AAC and other codecs in MP4: Android decodes them
    return nullptr;
}

// ---------------------------------------------------------------- DSF (Sony DSD Stream File)
static uint8_t bitrev(uint8_t b) {
    b = (uint8_t) (((b & 0xF0) >> 4) | ((b & 0x0F) << 4));
    b = (uint8_t) (((b & 0xCC) >> 2) | ((b & 0x33) << 2));
    return (uint8_t) (((b & 0xAA) >> 1) | ((b & 0x55) << 1));
}

struct DsfDecoder : Decoder {
    Io* io; int64_t dataStart = 0; uint32_t block = 4096; bool lsbFirst = true;
    int64_t pos = 0, loadedGroup = -1; std::vector<uint8_t> grp;
    explicit DsfDecoder(Io* i) : io(i) {}
    ~DsfDecoder() override { delete io; }
    int64_t read(void* dst, int64_t frames) override {
        uint8_t* out = (uint8_t*) dst; int64_t done = 0;
        while (done < frames && pos < totalFrames) {
            int64_t g = pos / block; uint32_t off = (uint32_t) (pos % block);
            if (g != loadedGroup) {
                grp.resize((size_t) block * channels);
                if (!io->seek(dataStart + g * (int64_t) block * channels)) break;
                size_t got = io->read(grp.data(), grp.size());
                if (got < grp.size()) memset(grp.data() + got, 0x69, grp.size() - got);
                if (lsbFirst) for (auto& v : grp) v = bitrev(v);
                loadedGroup = g;
            }
            int64_t take = block - off;
            if (take > frames - done) take = frames - done;
            if (take > totalFrames - pos) take = totalFrames - pos;
            for (int64_t i = 0; i < take; i++)
                for (int c = 0; c < channels; c++) out[(done + i) * channels + c] = grp[(size_t) c * block + off + i];
            done += take; pos += take;
        }
        return done;
    }
    bool seek(int64_t frame) override { if (frame < 0 || frame > totalFrames) return false; pos = frame; return true; }
};

static Decoder* openDsf(Io* io, std::string& err) {
    uint8_t h[28 + 52 + 12];
    if (!io->seek(0) || !io->readFully(h, sizeof(h)) || memcmp(h + 28, "fmt ", 4) || memcmp(h + 80, "data", 4)) {
        delete io; err = "damaged DSF file"; return nullptr;
    }
    const uint8_t* f = h + 28 + 12;
    auto* d = new DsfDecoder(io);
    d->codec = CODEC_DSF; d->dsd = true; d->bits = 1;
    uint32_t formatId = le32(f + 4), chanNum = le32(f + 12), rate = le32(f + 16), bps = le32(f + 20);
    uint64_t samples = le64(f + 24); uint32_t block = le32(f + 32);
    d->channels = (int) chanNum; d->rate = (int) rate; d->lsbFirst = bps == 1; d->block = block;
    d->dataStart = 28 + 52 + 12; d->totalFrames = (int64_t) (samples / 8);
    if (formatId != 0 || chanNum < 1 || chanNum > 6 || rate < 2822400 || block == 0 || block > (1u << 20) || (bps != 1 && bps != 8)) {
        delete d; err = "unsupported DSF format"; return nullptr;
    }
    return d;
}

// ---------------------------------------------------------------- DFF (Philips DSDIFF)
struct DffDecoder : Decoder {
    Io* io; int64_t dataStart = 0, pos = 0;
    explicit DffDecoder(Io* i) : io(i) {}
    ~DffDecoder() override { delete io; }
    int64_t read(void* dst, int64_t frames) override {
        if (frames > totalFrames - pos) frames = totalFrames - pos;
        if (frames <= 0) return 0;
        if (!io->seek(dataStart + pos * channels)) return -1;
        size_t got = io->read(dst, (size_t) (frames * channels));
        int64_t n = (int64_t) got / channels;
        pos += n;
        return n;
    }
    bool seek(int64_t frame) override { if (frame < 0 || frame > totalFrames) return false; pos = frame; return true; }
};

static Decoder* openDff(Io* io, std::string& err) {
    uint8_t h[16];
    if (!io->seek(0) || !io->readFully(h, 16) || memcmp(h + 12, "DSD ", 4)) { delete io; err = "damaged DFF file"; return nullptr; }
    int64_t end = 12 + (int64_t) be64(h + 4);
    if (io->size() > 0 && end > io->size()) end = io->size();
    int64_t pos = 16; uint32_t rate = 0; int ch = 0; bool dst = false; int64_t dataStart = -1, dataLen = 0;
    while (pos + 12 <= end) {
        uint8_t c[12];
        if (!io->seek(pos) || !io->readFully(c, 12)) break;
        int64_t sz = (int64_t) be64(c + 4), body = pos + 12;
        if (sz < 0 || body + sz > end + 1) sz = end - body;
        if (!memcmp(c, "PROP", 4)) {
            int64_t p = body + 4, pe = body + sz;   // skip "SND "
            while (p + 12 <= pe) {
                uint8_t s[16];
                if (!io->seek(p) || !io->readFully(s, 12)) break;
                int64_t ssz = (int64_t) be64(s + 4);
                if (ssz < 0) break;
                if (!memcmp(s, "FS  ", 4) && io->readFully(s, 4)) rate = be32(s);
                else if (!memcmp(s, "CHNL", 4) && io->readFully(s, 2)) ch = be16(s);
                else if (!memcmp(s, "CMPR", 4) && io->readFully(s, 4)) dst = memcmp(s, "DSD ", 4) != 0;
                p += 12 + ssz + (ssz & 1);
            }
        } else if (!memcmp(c, "DSD ", 4)) {
            dataStart = body; dataLen = sz;
        } else if (!memcmp(c, "DST ", 4)) {
            dst = true;
        }
        pos = body + sz + (sz & 1);
    }
    if (dst) { delete io; err = "DST-compressed DFF is not supported"; return nullptr; }
    if (dataStart < 0 || ch < 1 || ch > 6 || rate < 2822400) { delete io; err = "unsupported DFF format"; return nullptr; }
    auto* d = new DffDecoder(io);
    d->codec = CODEC_DFF; d->dsd = true; d->bits = 1; d->rate = (int) rate; d->channels = ch;
    d->dataStart = dataStart; d->totalFrames = dataLen / ch;
    return d;
}

// ---------------------------------------------------------------- detection
Decoder* openDecoder(Io* io, std::string& err) {
    uint8_t h[16] = {0};
    if (!io->seek(0) || io->read(h, 16) < 12) { delete io; err = "file too short"; return nullptr; }
    int64_t start = 0;
    if (!memcmp(h, "ID3", 3)) {   // some FLAC files carry an ID3v2 tag in front
        start = 10 + (((int64_t) (h[6] & 0x7F) << 21) | ((h[7] & 0x7F) << 14) | ((h[8] & 0x7F) << 7) | (h[9] & 0x7F));
        if (h[5] & 0x10) start += 10;
        if (!io->seek(start) || io->read(h, 16) < 12) { delete io; err = "file too short"; return nullptr; }
    }
    io->seek(start);
    if (!memcmp(h, "fLaC", 4)) {
        Io* sub = start ? new SubIo(io, start, io->size() - start) : io;
        auto* d = new FlacDecoder(io, sub);
        d->f = drflac_open(cbRead, flacSeek, flacTell, sub, nullptr);
        if (!d->f) { delete d; err = "damaged FLAC file"; return nullptr; }
        d->codec = CODEC_FLAC; d->rate = (int) d->f->sampleRate; d->channels = d->f->channels; d->bits = d->f->bitsPerSample;
        d->totalFrames = d->f->totalPCMFrameCount ? (int64_t) d->f->totalPCMFrameCount : -1;
        return d;
    }
    if (start == 0 && (!memcmp(h, "RIFF", 4) || !memcmp(h, "RF64", 4) || !memcmp(h, "riff", 4) || !memcmp(h, "RIFX", 4) ||
                       (!memcmp(h, "FORM", 4) && (!memcmp(h + 8, "AIFF", 4) || !memcmp(h + 8, "AIFC", 4))))) {
        auto* d = new WavDecoder(io);
        d->ok = drwav_init(&d->w, cbRead, wavSeek, wavTell, io, nullptr);
        if (!d->ok || d->w.channels < 1) { delete d; err = "damaged or unsupported WAV/AIFF file"; return nullptr; }
        d->codec = d->w.container == drwav_container_aiff ? CODEC_AIFF : CODEC_WAV;
        d->rate = (int) d->w.sampleRate; d->channels = d->w.channels; d->bits = d->w.bitsPerSample;
        d->isFloat = d->w.translatedFormatTag == DR_WAVE_FORMAT_IEEE_FLOAT;
        d->totalFrames = (int64_t) d->w.totalPCMFrameCount;
        return d;
    }
    if (start == 0 && !memcmp(h, "DSD ", 4)) return openDsf(io, err);
    if (start == 0 && !memcmp(h, "FRM8", 4)) return openDff(io, err);
    if (start == 0 && !memcmp(h + 4, "ftyp", 4)) return openMp4(io, err);
    delete io;
    err = "not-lossless";
    return nullptr;
}

} // namespace pp
