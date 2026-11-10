// Player Pure - isochronous USB audio streaming over usbfs. Android hands the app the DAC's file descriptor
// (UsbDeviceConnection) after the user allows it; interfaces, alternate settings and the sample rate are set from
// Kotlin with the normal Android USB API, and this file only moves audio packets.
#include "pp_usb.h"
#include <errno.h>
#include <string.h>
#include <stdlib.h>
#include <sys/ioctl.h>
#include <linux/usbdevice_fs.h>

namespace pp {

struct UsbEngine::Slot {
    usbdevfs_urb* urb = nullptr;
    std::vector<uint8_t> buf;
    bool fb = false, inFlight = false;
    int packets = 0;
};

UsbEngine::UsbEngine(int fd, const UsbConfig& c) : fd_(fd), cfg_(c) {
    devFrameBytes_ = c.channels * c.subslot;
    ringFrameBytes_ = c.dsd ? c.channels : devFrameBytes_;
    int64_t ringFrames = c.dsd ? c.rate : c.rate / 4;   // 250-500 ms (DSD modes: 2 or 4 DSD frames per USB frame)
    native_.swap = c.dsdSwap;
    if (ringFrames < 4096) ringFrames = 4096;
    ring_.resize((size_t) (ringFrames * ringFrameBytes_));
}

UsbEngine::~UsbEngine() {
    for (Slot* s : slots_) { free(s->urb); delete s; }
}

int64_t UsbEngine::queuedFrames() {
    std::lock_guard<std::mutex> l(m_);
    return (head_ - tail_) / ringFrameBytes_;
}

void UsbEngine::flush() {
    std::lock_guard<std::mutex> l(m_);
    played += (head_ - tail_) / ringFrameBytes_;   // dropped audio counts as gone, so positions stay in step
    tail_ = head_;
    cv_.notify_all();
}

void UsbEngine::interrupt() {
    interrupted_ = true;
    cv_.notify_all();
}

void UsbEngine::stop() {
    stopping_ = true;
    cv_.notify_all();
}

void UsbEngine::toDevice(const int32_t* src, int samples, uint8_t* dst, int gainQ16) {
    const int sub = cfg_.subslot;
    for (int i = 0; i < samples; i++) {
        int32_t v = src[i];
        if (gainQ16 != 65536) v = (int32_t) (((int64_t) v * gainQ16 + 32768) >> 16);
        uint32_t u = (uint32_t) v;
        switch (sub) {
            case 4: dst[0] = (uint8_t) u; dst[1] = (uint8_t) (u >> 8); dst[2] = (uint8_t) (u >> 16); dst[3] = (uint8_t) (u >> 24); break;
            case 3: dst[0] = (uint8_t) (u >> 8); dst[1] = (uint8_t) (u >> 16); dst[2] = (uint8_t) (u >> 24); break;
            case 2: dst[0] = (uint8_t) (u >> 16); dst[1] = (uint8_t) (u >> 24); break;
            default: dst[0] = (uint8_t) (u >> 24); break;
        }
        dst += sub;
    }
}

int UsbEngine::write(const void* src, int frames, int gainQ16) {
    const uint8_t* in = (const uint8_t*) src;
    const size_t cap = ring_.size();
    int done = 0;
    while (done < frames) {
        std::unique_lock<std::mutex> l(m_);
        size_t space = cap - (size_t) (head_ - tail_);
        int n = (int) (space / ringFrameBytes_);
        n -= n % dsdGroup();
        if (n <= 0) {
            if (stopping_ || interrupted_) break;
            cv_.wait_for(l, std::chrono::milliseconds(20));
            if (stopping_ || interrupted_) break;
            continue;
        }
        if (n > frames - done) n = frames - done;
        size_t pos = (size_t) (head_ % (int64_t) cap), bytes = (size_t) n * ringFrameBytes_;
        if (cfg_.dsd) {
            const uint8_t* s = in + (size_t) done * cfg_.channels;
            size_t first = bytes < cap - pos ? bytes : cap - pos;
            memcpy(ring_.data() + pos, s, first);
            memcpy(ring_.data(), s + first, bytes - first);
        } else {
            const int32_t* s = (const int32_t*) in + (size_t) done * cfg_.channels;
            if (pos + bytes <= cap) {
                toDevice(s, n * cfg_.channels, ring_.data() + pos, gainQ16);
            } else {
                wscratch_.resize(bytes);
                toDevice(s, n * cfg_.channels, wscratch_.data(), gainQ16);
                size_t first = cap - pos;
                memcpy(ring_.data() + pos, wscratch_.data(), first);
                memcpy(ring_.data(), wscratch_.data() + first, bytes - first);
            }
        }
        head_ += (int64_t) bytes;
        done += n;
    }
    interrupted_ = false;
    return done;
}

size_t UsbEngine::takeRing(uint8_t* dst, size_t bytes) {
    std::lock_guard<std::mutex> l(m_);
    const size_t cap = ring_.size();
    size_t avail = (size_t) (head_ - tail_);
    size_t unit = cfg_.dsd ? (size_t) cfg_.channels * dsdGroup() : (size_t) devFrameBytes_;
    size_t n = bytes < avail ? bytes : avail;
    n -= n % unit;
    size_t pos = (size_t) (tail_ % (int64_t) cap);
    size_t first = n < cap - pos ? n : cap - pos;
    memcpy(dst, ring_.data() + pos, first);
    memcpy(dst + first, ring_.data(), n - first);
    tail_ += (int64_t) n;
    cv_.notify_all();
    return n;
}

void UsbEngine::fill(Slot* s) {
    usbdevfs_urb* u = s->urb;
    int sizes[128], total = 0;
    for (int i = 0; i < s->packets; i++) { sizes[i] = clock_.next(); total += sizes[i]; }
    const int ch = cfg_.channels;
    bool starved;
    const bool hold = paused_;
    if (cfg_.dsd == 0) {
        size_t want = (size_t) total * devFrameBytes_;
        size_t got = hold ? 0 : takeRing(s->buf.data(), want);
        if (got < want) memset(s->buf.data() + got, 0, want - got);
        played += (int64_t) (got / devFrameBytes_);
        starved = got < want;
    } else if (cfg_.dsd == 2) {
        size_t want = (size_t) total * 4 * ch;
        scratch_.resize(want);
        size_t got = hold ? 0 : takeRing(scratch_.data(), want);
        int full = (int) (got / (4 * ch));
        native_.pack(scratch_.data(), full, ch, s->buf.data());
        NativeDsdPacker::silence(total - full, ch, s->buf.data() + (size_t) full * ch * 4);
        played += (int64_t) full * 4;
        starved = full < total;
    } else {
        size_t want = (size_t) total * 2 * ch;
        scratch_.resize(want);
        size_t got = hold ? 0 : takeRing(scratch_.data(), want);
        int full = (int) (got / (2 * ch));
        scratch32_.resize((size_t) total * ch);
        dop_.pack(scratch_.data(), full, ch, scratch32_.data());
        dop_.silence(total - full, ch, scratch32_.data() + (size_t) full * ch);
        toDevice(scratch32_.data(), total * ch, s->buf.data(), 65536);
        played += (int64_t) full * 2;
        starved = full < total;
    }
    if (starved && active_ && !hold && total > 0) underruns++;
    u->buffer_length = total * devFrameBytes_;
    for (int i = 0; i < s->packets; i++) {
        u->iso_frame_desc[i].length = (unsigned) (sizes[i] * devFrameBytes_);
        u->iso_frame_desc[i].actual_length = 0;
        u->iso_frame_desc[i].status = 0;
    }
    packets += s->packets;
}

static usbdevfs_urb* newUrb(int packets) {
    size_t sz = sizeof(usbdevfs_urb) + (size_t) packets * sizeof(usbdevfs_iso_packet_desc);
    usbdevfs_urb* u = (usbdevfs_urb*) calloc(1, sz);
    return u;
}

int UsbEngine::run() {
    const int packetMicros = (cfg_.highSpeed ? 125 : 1000) << cfg_.dataInterval;
    int perUrb = cfg_.urbMicros / packetMicros;
    if (perUrb < 1) perUrb = 1;
    if (perUrb > 128) perUrb = 128;
    int maxFrames = cfg_.dataMaxPacket / devFrameBytes_;
    clock_.init(cfg_.rate, cfg_.highSpeed, cfg_.dataInterval, maxFrames);
    if ((int) ((clock_.freqn << cfg_.dataInterval) >> 16) + 1 > maxFrames) { error = -EINVAL; return -EINVAL; }

    for (int i = 0; i < cfg_.urbs; i++) {
        Slot* s = new Slot();
        s->packets = perUrb;
        s->buf.resize((size_t) perUrb * cfg_.dataMaxPacket);
        s->urb = newUrb(perUrb);
        slots_.push_back(s);
    }
    if (cfg_.fbEp) {
        for (int i = 0; i < 2; i++) {
            Slot* s = new Slot();
            s->fb = true; s->packets = 1;
            s->buf.resize((size_t) (cfg_.fbMaxPacket < 4 ? 4 : cfg_.fbMaxPacket));
            s->urb = newUrb(1);
            slots_.push_back(s);
        }
    }

    int inflight = 0, rc = 0;
    auto submit = [&](Slot* s) -> int {
        usbdevfs_urb* u = s->urb;
        u->type = USBDEVFS_URB_TYPE_ISO;
        u->flags = USBDEVFS_URB_ISO_ASAP;
        u->buffer = s->buf.data();
        u->number_of_packets = s->packets;
        u->usercontext = s;
        u->status = 0; u->actual_length = 0; u->error_count = 0; u->start_frame = 0;
        if (s->fb) {
            u->endpoint = (unsigned char) cfg_.fbEp;
            u->buffer_length = (int) s->buf.size();
            u->iso_frame_desc[0].length = (unsigned) s->buf.size();
            u->iso_frame_desc[0].actual_length = 0;
            u->iso_frame_desc[0].status = 0;
        } else {
            u->endpoint = (unsigned char) cfg_.dataEp;
            fill(s);
        }
        if (ioctl(fd_, USBDEVFS_SUBMITURB, u) < 0) return -errno;
        s->inFlight = true; inflight++;
        return 0;
    };

    for (Slot* s : slots_) {
        if ((rc = submit(s)) != 0) break;
    }
    while (rc == 0 && !stopping_) {
        usbdevfs_urb* u = nullptr;
        if (ioctl(fd_, USBDEVFS_REAPURB, &u) < 0) {
            if (errno == EINTR) continue;
            rc = -errno;
            break;
        }
        Slot* s = (Slot*) u->usercontext;
        s->inFlight = false; inflight--;
        if (u->status == -ENODEV || u->status == -ESHUTDOWN) { rc = -ENODEV; break; }
        if (stopping_) break;
        if (s->fb && u->status == 0 && u->iso_frame_desc[0].status == 0) {
            int len = (int) u->iso_frame_desc[0].actual_length;
            if (len >= 3) {
                uint32_t raw = s->buf[0] | (s->buf[1] << 8) | (s->buf[2] << 16) | (len >= 4 ? (uint32_t) s->buf[3] << 24 : 0u);
                lastFeedback = raw;
                if (clock_.feedback(s->buf.data(), len)) feedbacks++; else badFeedback++;
            }
        }
        rc = submit(s);
    }
    // stop: cancel what is still queued and collect it back
    for (Slot* s : slots_) if (s->inFlight) ioctl(fd_, USBDEVFS_DISCARDURB, s->urb);
    while (inflight > 0) {
        usbdevfs_urb* u = nullptr;
        if (ioctl(fd_, USBDEVFS_REAPURB, &u) < 0) { if (errno == EINTR) continue; break; }
        ((Slot*) u->usercontext)->inFlight = false;
        inflight--;
    }
    stopping_ = true;
    cv_.notify_all();
    error = rc;
    return rc;
}

int usbSpeed(int fd) {
#ifdef USBDEVFS_GET_SPEED
    int r = ioctl(fd, USBDEVFS_GET_SPEED);
    return r < 0 ? -errno : r;
#else
    return -ENOSYS;
#endif
}

int usbReattach(int fd, int iface) {
    struct usbdevfs_ioctl cmd;
    memset(&cmd, 0, sizeof(cmd));
    cmd.ifno = iface;
    cmd.ioctl_code = USBDEVFS_CONNECT;
    cmd.data = nullptr;
    return ioctl(fd, USBDEVFS_IOCTL, &cmd) < 0 ? -errno : 0;
}

} // namespace pp
