// Player Pure - USB Audio Class streaming straight to the DAC (isochronous OUT over usbfs), bypassing Android's mixer.
#pragma once
#include <stdint.h>
#include <limits.h>
#include <atomic>
#include <mutex>
#include <condition_variable>
#include <vector>
#include "pp_dsd.h"

namespace pp {

/**
 * How many audio frames go into each isochronous packet. Same method as Linux snd-usb-audio: a Q16.16 rate in
 * frames per (micro)frame, corrected by the DAC's feedback endpoint for asynchronous DACs.
 */
struct PacketClock {
    uint32_t freqn = 0, freqm = 0, freqmax = 0;
    int shift = INT_MIN;          // feedback format shift, detected from the first feedback value
    uint32_t phase = 0;
    int datainterval = 0;         // log2 of (micro)frames per packet
    int maxFrames = 0;

    void init(int rate, bool highSpeed, int dataInterval, int maxFramesPerPacket) {
        uint64_t r = (uint64_t) rate;
        freqn = (uint32_t) (highSpeed ? ((r << 10) + 62) / 125 : ((r << 13) + 62) / 125);
        freqm = freqn; freqmax = freqn + (freqn >> 1);
        datainterval = dataInterval; maxFrames = maxFramesPerPacket; phase = 0; shift = INT_MIN;
    }
    int next() {
        phase = (phase & 0xffff) + (freqm << datainterval);
        int n = (int) (phase >> 16);
        return n > maxFrames ? maxFrames : n;
    }
    /** One feedback packet (3 bytes 10.14 or 4 bytes 16.16, either may be mis-scaled by the DAC). */
    bool feedback(const uint8_t* p, int len) {
        uint32_t f;
        if (len >= 4) f = ((uint32_t) p[0] | ((uint32_t) p[1] << 8) | ((uint32_t) p[2] << 16) | ((uint32_t) p[3] << 24)) & 0x0fffffff;
        else if (len == 3) f = (uint32_t) p[0] | ((uint32_t) p[1] << 8) | ((uint32_t) p[2] << 16);
        else return false;
        if (f == 0) return false;
        if (shift == INT_MIN) {
            int s = 0;
            while (f < freqn - freqn / 4) { f <<= 1; s++; }
            while (f > freqn + freqn / 2) { f >>= 1; s--; }
            shift = s;
        } else if (shift >= 0) f <<= shift; else f >>= -shift;
        if (f >= freqn - freqn / 8 && f <= freqmax) { freqm = f; return true; }
        shift = INT_MIN;
        return false;
    }
};

struct UsbConfig {
    int dataEp = 0, dataMaxPacket = 0, dataInterval = 0;   // dataInterval: log2 of (micro)frames per packet
    int fbEp = 0, fbMaxPacket = 0;                          // fbEp 0 = no explicit feedback endpoint
    bool highSpeed = true;
    int rate = 0, channels = 2, subslot = 4, bits = 24;     // what the DAC receives
    int dsd = 0;                                            // 0 PCM, 1 DoP, 2 native DSD (input is DSD frames for 1 and 2)
    bool dsdSwap = false;                                   // native DSD: newest byte first
    int urbs = 12, urbMicros = 4000;
};

class UsbEngine {
public:
    UsbEngine(int fd, const UsbConfig& c);
    ~UsbEngine();
    /** Streams until stop() or an error. Returns 0, or -errno (-ENODEV when the DAC was unplugged). */
    int run();
    /** Queues audio: PCM = int32 MSB-aligned frames, DoP mode = DSD frames (channels bytes each; even count).
     *  gainQ16 65536 = untouched (bit-perfect). Blocks while the buffer is full; returns frames taken. */
    int write(const void* src, int frames, int gainQ16);
    void flush();
    void interrupt();             // makes a blocked write() return now
    void stop();
    void setActive(bool a) { active_ = a; }   // only count dropouts while music should be playing
    void setPaused(bool p) { paused_ = p; }   // send silence and keep the queued audio for later
    int64_t queuedFrames();       // input frames waiting
    int inputFrameBytes() const { return cfg_.dsd ? cfg_.channels : cfg_.channels * 4; }
    /** DSD frames per output frame: 2 for DoP, 4 for native DSD, 1 for PCM. */
    int dsdGroup() const { return cfg_.dsd == 1 ? 2 : cfg_.dsd == 2 ? 4 : 1; }

    std::atomic<int64_t> played{0};      // input frames sent to the DAC
    std::atomic<int64_t> underruns{0}, packets{0}, feedbacks{0}, badFeedback{0};
    std::atomic<uint32_t> lastFeedback{0};
    std::atomic<int> error{0};

private:
    struct Slot;
    void fill(Slot* s);
    size_t takeRing(uint8_t* dst, size_t bytes);
    void toDevice(const int32_t* src, int samples, uint8_t* dst, int gainQ16);

    int fd_;
    UsbConfig cfg_;
    PacketClock clock_;
    DopPacker dop_;
    NativeDsdPacker native_;
    int devFrameBytes_, ringFrameBytes_;
    std::vector<uint8_t> ring_;
    int64_t head_ = 0, tail_ = 0;        // bytes written / read (monotonic)
    std::mutex m_;
    std::condition_variable cv_;
    std::atomic<bool> stopping_{false}, interrupted_{false}, active_{false}, paused_{false};
    std::vector<Slot*> slots_;
    std::vector<uint8_t> scratch_, wscratch_;   // streaming thread / writer thread
    std::vector<int32_t> scratch32_;
};

/** Bus speed from usbfs: 1 low, 2 full, 3 high, 5 super, or -errno. */
int usbSpeed(int fd);

/** Gives an interface back to Android's own USB audio driver (USBDEVFS_CONNECT). */
int usbReattach(int fd, int iface);

} // namespace pp
