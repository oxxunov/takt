// crash_handler.cpp — перехват нативных падений (SIGSEGV и родственные).
//
// Падение в C++ убивает процесс мимо любых обработчиков Java: ни
// стектрейса, ни диалога не остаётся, и снаружи это выглядит просто как
// "приложение выкинуло". Здесь ставится обработчик сигналов, который
// успевает записать причину и трассировку на диск до смерти процесса.
//
// В обработчике сигнала можно вызывать только async-signal-safe функции.
// Поэтому здесь никакого printf и никаких выделений памяти: буфер
// подготовлен заранее, запись идёт через write().

#include <android/log.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <signal.h>
#include <string.h>
#include <unistd.h>
#include <unwind.h>

#include <cstdint>

#define LOG_TAG "takt-native"

namespace {

constexpr int kMaxFrames = 32;
constexpr int kPathMax = 512;

char gPath[kPathMax] = { 0 };
bool gInstalled = false;

// Прежние обработчики: после записи отдаём управление им,
// чтобы система собрала свой отчёт как обычно.
struct sigaction gOld[8];
const int kSignals[] = { SIGSEGV, SIGABRT, SIGBUS, SIGILL, SIGFPE, SIGTRAP };
constexpr int kSignalCount = sizeof(kSignals) / sizeof(kSignals[0]);

struct BacktraceState {
    void** current;
    void** end;
};

_Unwind_Reason_Code unwindCallback(struct _Unwind_Context* context, void* arg) {
    auto* state = static_cast<BacktraceState*>(arg);
    uintptr_t pc = _Unwind_GetIP(context);
    if (pc) {
        if (state->current == state->end) return _URC_END_OF_STACK;
        *state->current++ = reinterpret_cast<void*>(pc);
    }
    return _URC_NO_REASON;
}

// Безопасные примитивы вывода: без snprintf и без malloc.
void writeStr(int fd, const char* s) {
    if (!s) return;
    size_t n = strlen(s);
    ssize_t ignored = write(fd, s, n);
    (void)ignored;
}

void writeHex(int fd, uintptr_t v) {
    char buf[2 + sizeof(uintptr_t) * 2 + 1];
    const char* digits = "0123456789abcdef";
    int i = 0;
    buf[i++] = '0';
    buf[i++] = 'x';
    bool started = false;
    for (int shift = (int)(sizeof(uintptr_t) * 8) - 4; shift >= 0; shift -= 4) {
        int nibble = (int)((v >> shift) & 0xF);
        if (nibble || started || shift == 0) { started = true; buf[i++] = digits[nibble]; }
    }
    buf[i] = 0;
    writeStr(fd, buf);
}

void writeInt(int fd, int v) {
    char buf[16];
    int i = sizeof(buf) - 1;
    buf[i--] = 0;
    if (v == 0) buf[i--] = '0';
    bool neg = v < 0;
    unsigned u = neg ? (unsigned)(-v) : (unsigned)v;
    while (u) { buf[i--] = (char)('0' + (u % 10)); u /= 10; }
    if (neg) buf[i--] = '-';
    writeStr(fd, &buf[i + 1]);
}

const char* signalName(int sig) {
    switch (sig) {
        case SIGSEGV: return "SIGSEGV (обращение по недопустимому адресу)";
        case SIGABRT: return "SIGABRT (аварийное завершение)";
        case SIGBUS:  return "SIGBUS (ошибка выравнивания или доступа)";
        case SIGILL:  return "SIGILL (недопустимая инструкция)";
        case SIGFPE:  return "SIGFPE (ошибка вычисления)";
        case SIGTRAP: return "SIGTRAP";
        default:      return "неизвестный сигнал";
    }
}

void handler(int sig, siginfo_t* info, void* context) {
    int fd = open(gPath, O_WRONLY | O_CREAT | O_TRUNC, 0600);
    if (fd >= 0) {
        writeStr(fd, "НАТИВНОЕ ПАДЕНИЕ\n");
        writeStr(fd, "сигнал: ");
        writeStr(fd, signalName(sig));
        writeStr(fd, " (");
        writeInt(fd, sig);
        writeStr(fd, ")\n");

        if (info) {
            writeStr(fd, "адрес: ");
            writeHex(fd, reinterpret_cast<uintptr_t>(info->si_addr));
            writeStr(fd, "\n");
        }

        writeStr(fd, "\nтрассировка:\n");
        void* frames[kMaxFrames];
        BacktraceState state = { frames, frames + kMaxFrames };
        _Unwind_Backtrace(unwindCallback, &state);
        int count = (int)(state.current - frames);

        for (int i = 0; i < count; ++i) {
            writeStr(fd, "  #");
            writeInt(fd, i);
            writeStr(fd, "  ");
            writeHex(fd, reinterpret_cast<uintptr_t>(frames[i]));

            // dladdr формально не async-signal-safe, но для посмертного
            // отчёта имена символов важнее строгой чистоты: без них
            // трассировка — просто столбик адресов.
            Dl_info dl;
            if (dladdr(frames[i], &dl)) {
                if (dl.dli_fname) { writeStr(fd, "  "); writeStr(fd, dl.dli_fname); }
                if (dl.dli_sname) { writeStr(fd, "  "); writeStr(fd, dl.dli_sname); }
            }
            writeStr(fd, "\n");
        }
        writeStr(fd, "\n");
        close(fd);
    }

    __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "нативное падение, сигнал %d", sig);

    // Возвращаем прежний обработчик и повторяем сигнал: система соберёт
    // свой отчёт, а процесс завершится штатным для неё образом.
    for (int i = 0; i < kSignalCount; ++i) {
        if (kSignals[i] == sig) {
            sigaction(sig, &gOld[i], nullptr);
            break;
        }
    }
    raise(sig);
    (void)context;
}

} // namespace

namespace rg {

bool installCrashHandler(const char* path) {
    if (gInstalled || !path) return gInstalled;
    size_t n = strlen(path);
    if (n == 0 || n >= kPathMax) return false;
    memcpy(gPath, path, n + 1);

    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sigemptyset(&sa.sa_mask);
    sa.sa_sigaction = handler;
    sa.sa_flags = SA_SIGINFO | SA_ONSTACK;

    for (int i = 0; i < kSignalCount; ++i) {
        sigaction(kSignals[i], &sa, &gOld[i]);
    }
    gInstalled = true;
    return true;
}

} // namespace rg
