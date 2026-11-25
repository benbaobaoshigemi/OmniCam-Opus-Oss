// libBasicTonePhoto.so for /odm/lib64: routes the stock BasicTone API to two backends.
//
// The X9 Ultra's own engine is bound at /odm/lib64/libBasicTonePhotoX9.so (its stock libBasicTonePhoto.so),
// the Find X10's at /odm/lib64/libBasicTonePhotoX10.so. Each handle owns one instance of each; a process call
// goes to X10 when the request carries a palette and comes from the photo (6) or portrait (8) mode, and to X9
// otherwise. Build: tools/basictone_router/build.py.
#include <android/log.h>
#include <dlfcn.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>

#define TAG "X10ToneRoute"
#define FAILED 0xffff

// The engine's API table, as getAlgoAPI fills it.
typedef struct {
    const char *(*version)(void);
    void (*log_level)(int level);
    const char *(*status)(int code);
    int (*init)(void **handle, const void *config);
    int (*set_parameters)(void *handle, const void *parameters);
    int (*get_parameters)(void *handle, void *parameters);
    int (*process)(void *handle, void *input, void *output, const void *request, void *extra);
    int (*release)(void *handle, void *arg);
} AlgoAPI;

enum { X9, X10, BACKENDS };
static const char *const kPaths[BACKENDS] = {
    "/odm/lib64/libBasicTonePhotoX9.so",
    "/odm/lib64/libBasicTonePhotoX10.so",
};

static pthread_once_t once = PTHREAD_ONCE_INIT;
static bool ready;
static AlgoAPI api[BACKENDS];

typedef struct {
    void *backend[BACKENDS];
} Handle;

static bool load_backend(int which) {
    void *lib = dlopen(kPaths[which], RTLD_NOW);
    if (!lib) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "dlopen %s: %s", kPaths[which], dlerror());
        return false;
    }
    int (*get)(AlgoAPI *) = (int (*)(AlgoAPI *))dlsym(lib, "getAlgoAPI");
    if (!get || get(&api[which]) != 0 || !api[which].init || !api[which].process || !api[which].release) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "invalid API: %s", kPaths[which]);
        return false;
    }
    return true;
}

static void load(void) {
    if (load_backend(X9) && load_backend(X10)) ready = true;
}

static const char *version(void) { return "X9/X10 photo-portrait palette router 1.0.13"; }

static void log_level(int level) { (void)level; }

static const char *status(int code) {
    pthread_once(&once, load);
    if (!ready) return "BasicTone backend load failed";
    return api[X9].status(code);
}

static int init(void **out, const void *config) {
    if (!out || !config) return FAILED;
    *out = NULL;
    pthread_once(&once, load);
    if (!ready) return FAILED;
    Handle *handle = calloc(1, sizeof(Handle));
    if (!handle) return FAILED;
    bool second = false;
    int rc = api[X9].init(&handle->backend[X9], config);
    if (rc == 0 && !handle->backend[X9]) {
        rc = FAILED;
    } else if (rc == 0) {
        second = true;
        rc = api[X10].init(&handle->backend[X10], config);
        if (rc == 0) {
            if (handle->backend[X10]) {
                *out = handle;
                return 0;
            }
            rc = FAILED;
        }
    }
    if (handle->backend[X9]) api[X9].release(handle->backend[X9], NULL);
    if (second && handle->backend[X10]) api[X10].release(handle->backend[X10], NULL);
    free(handle);
    return rc;
}

static int parameters(void *handle, void *parameters) {
    (void)handle;
    (void)parameters;
    return 0;
}

static int process(void *opaque, void *input, void *output, const void *request, void *extra) {
    if (!opaque || !request) return FAILED;
    Handle *handle = opaque;
    int mode = *(const int32_t *)((const uint8_t *)request + 0x10);
    unsigned palette = *((const uint8_t *)request + 0x68);
    int which = palette != 0 && (mode == 6 || mode == 8) ? X10 : X9;
    __android_log_print(ANDROID_LOG_INFO, TAG, "mode=%d palette=%u backend=X%d", mode, palette,
                        which == X10 ? 10 : 9);
    return api[which].process(handle->backend[which], input, output, request, extra);
}

static int release(void *opaque, void *arg) {
    if (!opaque) return 0;
    Handle *handle = opaque;
    int first = api[X9].release(handle->backend[X9], arg);
    int second = api[X10].release(handle->backend[X10], arg);
    free(handle);
    return first != 0 ? first : second;
}

__attribute__((visibility("default"))) int getAlgoAPI(AlgoAPI *out) {
    if (!out) return 0;
    out->version = version;
    out->log_level = log_level;
    out->status = status;
    out->init = init;
    out->set_parameters = (int (*)(void *, const void *))parameters;
    out->get_parameters = parameters;
    out->process = process;
    out->release = release;
    return 0;
}
