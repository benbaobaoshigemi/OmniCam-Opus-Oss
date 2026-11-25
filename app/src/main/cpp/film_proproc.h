// Film grain in the master capture's ProProc pass (film_proproc.cpp).
#pragma once

#include <cstddef>
#include <string>

#include "capture_dump.h"

// The stock kernel's name.
bool filmProProcOwns(const std::string &name);
// Runs the film's variant of the stock ProProc kernel for this submission (a film look with grain on, replacement mode) instead of the
// stock one, with the same arguments and range. False: nothing was submitted, the stock kernel runs.
bool filmProProcRun(void *engine, const std::string &name, const CaptureKernelArgs &args, unsigned dim, const size_t *offset, const size_t *global,
                    const size_t *local, bool sync);

// Log line to logcat and to files/jiege/film_proproc.log (the camera's log buffer rolls over long before a deferred capture is processed).
void filmTrace(const char *format, ...) __attribute__((format(printf, 1, 2)));
