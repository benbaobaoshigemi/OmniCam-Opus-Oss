/* Freestanding blob placed in the slack after the executable segment of
 * camera.oemlayer.uilayer.so. Two stock call sites are redirected here:
 *   ParseSessionParams -> og_capture_hook: remember per camera whether the session configures an
 *     OpenGate video stream (4080x3064 / 4096x3072, opmode 0x800B or 0x8021 family);
 *   the caps standardization in SensorModeMatching -> og_scope_hook: for such a session, replace
 *     the caps key with the private 0x800000 key (0x2000 Dolby, 0x0200 60fps kept), which
 *     CameraHWConfiguration.config maps to the full-frame mode of each lens.
 * Front camera (1) and 120fps-and-up keys keep the stock path.
 */
typedef unsigned int u32;
typedef unsigned long u64;
struct Stream { u32 type,width,height,format,usage,maxBuffers; void *privateData; u32 dataSpace; };
struct Configuration { u32 count; struct Stream **streams; u32 operation; };
struct CapsBitset { u64 low, high; };

extern long native_parse_streams(const void *instance, const struct Configuration *configuration);
extern void native_standardize(const void *instance, struct CapsBitset *mask);

/* Zero-filled writable page tail after .bss; one flag per camera slot. */
extern volatile u32 og_state[8];

static int camera_of(const void *instance)
{
    const u32 *info=*(const u32 *const *)instance;
    return info ? (int)info[1] : -1;
}

static void capture(int camera, const struct Configuration *c)
{
    if ((u32)camera>=8) return;
    u32 match=0;
    if (c && ((c->operation & 0xf0ffu)==0x800bu || (c->operation & 0xf0ffu)==0x8021u) && c->streams) {
        for (u32 i=0;i<c->count;++i) {
            const struct Stream *s=c->streams[i];
            if (s && (s->usage & 0x80010080u) && s->dataSpace!=0x1004u &&
                ((s->width==4080 && s->height==3064)||(s->width==4096 && s->height==3072))) match=1;
        }
    }
    og_state[camera]=match;
}

long og_capture_hook(const void *instance, const struct Configuration *c)
{
    capture(camera_of(instance), c);
    return native_parse_streams(instance, c);
}

/* Called with this in x19 at the stock call site; x0 is unrelated there. */
void og_scope_hook(const void *instance, struct CapsBitset *mask)
{
    native_standardize(instance, mask);
    int camera=camera_of(instance);
    /* camera 1 = front; ultra tele is camera 5 (measured: OG 10x session opens ID 5). */
    if ((u32)camera>=8 || camera==1 || !og_state[camera]) return;
    u32 original=(u32)mask->low;
    /* 120fps and higher keys keep the stock path. */
    if (mask->high || (original & 0x1c00u)) return;
    mask->low=0x800000u | (original & 0x2000u) | ((original & 0x0200u) ? 0x0200u : 0x0040u);
}
