/* Минимальный config.h для сборки libmp3lame 3.100 под Android (без autotools). */
#define STDC_HEADERS 1
#define HAVE_STDINT_H 1
#define HAVE_INTTYPES_H 1
#define HAVE_LIMITS_H 1
#define HAVE_STRING_H 1
#define HAVE_STDLIB_H 1
#define HAVE_ERRNO_H 1
#define HAVE_FCNTL_H 1
#define HAVE_UNISTD_H 1
#define HAVE_STRCHR 1
#define HAVE_MEMCPY 1
#define PROTOTYPES 1
#define USE_FAST_LOG 1
#define TAKEHIRO_IEEE754_HACK 1
#define LAME_LIBRARY_BUILD 1
#include <stdint.h>
typedef float ieee754_float32_t;
typedef double ieee754_float64_t;
