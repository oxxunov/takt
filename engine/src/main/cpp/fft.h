// fft.h — минимальный radix-2 in-place FFT. Без внешних зависимостей.
#pragma once

#include <vector>
#include <cmath>
#include <cstddef>

namespace rg {

class Fft {
public:
    explicit Fft(size_t n) : n_(n) {
        // Таблица twiddle-факторов и bit-reversal перестановки считаются один раз.
        rev_.resize(n_);
        size_t bits = 0;
        while ((size_t(1) << bits) < n_) ++bits;
        for (size_t i = 0; i < n_; ++i) {
            size_t r = 0;
            for (size_t b = 0; b < bits; ++b)
                if (i & (size_t(1) << b)) r |= size_t(1) << (bits - 1 - b);
            rev_[i] = r;
        }
        cosT_.resize(n_ / 2);
        sinT_.resize(n_ / 2);
        for (size_t i = 0; i < n_ / 2; ++i) {
            double a = -2.0 * M_PI * double(i) / double(n_);
            cosT_[i] = float(std::cos(a));
            sinT_[i] = float(std::sin(a));
        }
    }

    size_t size() const { return n_; }

    // re/im — массивы длины n. Преобразование in-place.
    void forward(float* re, float* im) const {
        for (size_t i = 0; i < n_; ++i) {
            size_t r = rev_[i];
            if (r > i) {
                std::swap(re[i], re[r]);
                std::swap(im[i], im[r]);
            }
        }
        for (size_t len = 2; len <= n_; len <<= 1) {
            size_t half = len >> 1;
            size_t step = n_ / len;
            for (size_t i = 0; i < n_; i += len) {
                size_t t = 0;
                for (size_t j = 0; j < half; ++j, t += step) {
                    float wr = cosT_[t], wi = sinT_[t];
                    size_t a = i + j, b = a + half;
                    float xr = re[b] * wr - im[b] * wi;
                    float xi = re[b] * wi + im[b] * wr;
                    re[b] = re[a] - xr;
                    im[b] = im[a] - xi;
                    re[a] += xr;
                    im[a] += xi;
                }
            }
        }
    }

private:
    size_t n_;
    std::vector<size_t> rev_;
    std::vector<float> cosT_, sinT_;
};

} // namespace rg
