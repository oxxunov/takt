// Проверка в CI (на ПК): модель Sidon грузится, отдаёт ровно 3× сэмплов (16 → 48 кГц),
// звук не пустой и не «взорвался». Вход — сырой float32 моно 16 кГц.
#include <chrono>
#include <cmath>
#include <cstdio>
#include <vector>
#include "sidon.h"

int main(int argc, char **argv) {
    if (argc < 4) { std::fprintf(stderr, "usage: host_check model.gguf in16k.f32 out48k.f32\n"); return 2; }
    FILE *f = std::fopen(argv[2], "rb");
    if (!f) return 3;
    std::vector<float> x;
    float buf[4096];
    size_t n;
    while ((n = std::fread(buf, sizeof(float), 4096, f)) > 0) x.insert(x.end(), buf, buf + n);
    std::fclose(f);

    auto prm = sidon_context_default_params();
    prm.n_threads = 4;
    prm.use_gpu = false;
    auto t0 = std::chrono::steady_clock::now();
    sidon_context *ctx = sidon_init_from_file(argv[1], prm);
    if (!ctx) { std::fprintf(stderr, "FAIL: model did not load\n"); return 4; }
    auto t1 = std::chrono::steady_clock::now();
    std::vector<float> y = sidon_restore(ctx, x.data(), (int) x.size());
    auto t2 = std::chrono::steady_clock::now();
    sidon_free(ctx);

    double rms = 0, peak = 0;
    for (float v: y) { rms += (double) v * v; peak = std::fmax(peak, std::fabs(v)); }
    rms = y.empty() ? 0 : std::sqrt(rms / y.size());
    double sec = x.size() / 16000.0;
    double run = std::chrono::duration<double>(t2 - t1).count();
    std::printf("in %.2f s, out %zu samples (expected %zu), rms %.4f, peak %.3f, load %.1f s, run %.1f s (x%.2f realtime)\n",
                sec, y.size(), x.size() * 3, rms, peak,
                std::chrono::duration<double>(t1 - t0).count(), run, run / sec);
    if (FILE *o = std::fopen(argv[3], "wb")) { std::fwrite(y.data(), sizeof(float), y.size(), o); std::fclose(o); }
    if (y.size() != x.size() * 3) { std::fprintf(stderr, "FAIL: wrong length\n"); return 5; }
    if (!(rms > 1e-3) || !(peak < 4.0)) { std::fprintf(stderr, "FAIL: silent or broken output\n"); return 6; }
    std::printf("OK\n");
    return 0;
}
