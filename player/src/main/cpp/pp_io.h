// Player Pure - byte sources for the decoders.
#pragma once
#include <stdint.h>
#include <stddef.h>
#include <string.h>
#include <unistd.h>
#include <sys/stat.h>
#include <vector>

namespace pp {

/** Random-access byte source. */
struct Io {
    virtual ~Io() {}
    virtual size_t read(void* dst, size_t n) = 0;
    virtual bool seek(int64_t pos) = 0;   // absolute position
    virtual int64_t tell() = 0;
    virtual int64_t size() = 0;

    bool readFully(void* dst, size_t n) { return read(dst, n) == n; }
    bool skip(int64_t n) { return seek(tell() + n); }
};

/** A file descriptor (owned, closed on delete) with a 64 KB read-ahead buffer: USB hard drives like large reads. */
class FdIo : public Io {
public:
    explicit FdIo(int fd) : fd_(fd), buf_(65536) {
        struct stat st;
        size_ = fstat(fd, &st) == 0 ? (int64_t) st.st_size : -1;
    }
    ~FdIo() override { if (fd_ >= 0) close(fd_); }

    size_t read(void* dst, size_t n) override {
        uint8_t* out = (uint8_t*) dst;
        size_t done = 0;
        while (done < n) {
            if (pos_ >= bufStart_ && pos_ < bufStart_ + bufLen_) {
                size_t avail = (size_t) (bufStart_ + bufLen_ - pos_);
                size_t take = avail < n - done ? avail : n - done;
                memcpy(out + done, buf_.data() + (pos_ - bufStart_), take);
                done += take; pos_ += take;
                continue;
            }
            size_t want = n - done;
            if (want >= buf_.size()) {            // big read: straight into the caller's buffer
                ssize_t r = pread(fd_, out + done, want, pos_);
                if (r <= 0) break;
                done += (size_t) r; pos_ += r;
                continue;
            }
            ssize_t r = pread(fd_, buf_.data(), buf_.size(), pos_);
            if (r <= 0) break;
            bufStart_ = pos_; bufLen_ = r;
        }
        return done;
    }
    bool seek(int64_t pos) override { if (pos < 0) return false; pos_ = pos; return true; }
    int64_t tell() override { return pos_; }
    int64_t size() override { return size_; }

private:
    int fd_;
    int64_t pos_ = 0, size_ = -1;
    std::vector<uint8_t> buf_;
    int64_t bufStart_ = 0, bufLen_ = 0;
};

/** A window [start, start+len) of another source, e.g. the FLAC stream after an ID3 tag. Does not own the parent. */
class SubIo : public Io {
public:
    SubIo(Io* parent, int64_t start, int64_t len) : p_(parent), start_(start), len_(len) { p_->seek(start); }
    size_t read(void* dst, size_t n) override {
        int64_t left = len_ - (p_->tell() - start_);
        if (left <= 0) return 0;
        if ((int64_t) n > left) n = (size_t) left;
        return p_->read(dst, n);
    }
    bool seek(int64_t pos) override { return pos >= 0 && p_->seek(start_ + pos); }
    int64_t tell() override { return p_->tell() - start_; }
    int64_t size() override { return len_; }
private:
    Io* p_;
    int64_t start_, len_;
};

static inline uint32_t be32(const uint8_t* p) { return ((uint32_t) p[0] << 24) | ((uint32_t) p[1] << 16) | ((uint32_t) p[2] << 8) | p[3]; }
static inline uint64_t be64(const uint8_t* p) { return ((uint64_t) be32(p) << 32) | be32(p + 4); }
static inline uint16_t be16(const uint8_t* p) { return (uint16_t) ((p[0] << 8) | p[1]); }
static inline uint32_t le32(const uint8_t* p) { return ((uint32_t) p[3] << 24) | ((uint32_t) p[2] << 16) | ((uint32_t) p[1] << 8) | p[0]; }
static inline uint64_t le64(const uint8_t* p) { return ((uint64_t) le32(p + 4) << 32) | le32(p); }

} // namespace pp
