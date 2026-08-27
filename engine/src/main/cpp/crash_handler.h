#pragma once

namespace rg {
/** Ставит обработчики сигналов, пишущие посмертный отчёт в файл path. */
bool installCrashHandler(const char* path);
}
