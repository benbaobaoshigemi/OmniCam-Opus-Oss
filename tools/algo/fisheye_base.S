// Patch cave for the stock libmsnativefilter.so of ROM 17.0.1.200 (linked at VA 0x10c0280)
// External symbols supplied via --defsym:
//   g_state            = 0x10a8800 (.bss: +0x00 lock, +0x08 slots[16]*16, +0x110 path[256], +0x210 path_ready)
//   fn_upload_layer    = 0x79a390
//   fn_obj_release     = 0x9c02d8
//   fn_desc_write      = 0x831dec
//   fn_get_dummy_tex   = 0x781fd4
//   ret_setstr_hit     = 0x76263c
//   ret_setstr_miss    = 0x762604
//   ret_render_desc    = 0x780adc
//   ret_dtor_body      = 0x79765c
//   ret_relres_body    = 0x7979e0
//   fn_set_uniform     = 0xa579a0 (CNvOpenGLShaderProgram::setUniformValue PLT)
//   fn_gl_active       = 0xa59180, fn_gl_bind = 0xa57cf0, fn_gl_param = 0xa59190 (PLT)
//   ret_yuv_uniform    = 0x78cb70, ret_yuv_bind = 0x789e48, ret_yuv_prep = 0x788d40, ret_fish_flag = 0x78237c

.text
.balign 16

.equ OFF_LOCK,       0x000
.equ OFF_SLOTS,      0x008
.equ SLOTS_COUNT,    16
.equ SLOT_SIZE,      16
.equ OFF_PATH,       0x110
.equ OFF_PATH_READY, 0x210

acquire_lock:
    adrp    x9, g_state
    add     x9, x9, :lo12:g_state
1:
    ldaxr   w10, [x9]
    cbnz    w10, 2f
    mov     w10, #1
    stlxr   w11, w10, [x9]
    cbnz    w11, 1b
    ret
2:
    yield
    b       1b

release_lock:
    adrp    x9, g_state
    add     x9, x9, :lo12:g_state
    stlr    wzr, [x9]
    ret

.global hook_set_string
hook_set_string:
    cbz     x21, .Lsetstr_miss
    adrp    x9, str_key_layer_path
    add     x9, x9, :lo12:str_key_layer_path
    mov     x10, x21
1:
    ldrb    w11, [x10], #1
    ldrb    w12, [x9], #1
    cmp     w11, w12
    b.ne    .Lsetstr_miss
    cbnz    w11, 1b

    cbz     x20, .Lsetstr_done
    ldrb    w11, [x20]
    cbz     w11, .Lsetstr_done

    bl      acquire_lock
    add     x10, x9, #OFF_PATH
    mov     x11, x20
    mov     w12, #255
2:
    ldrb    w13, [x11], #1
    strb    w13, [x10], #1
    cbz     w13, 3f
    subs    w12, w12, #1
    b.ne    2b
    strb    wzr, [x10]
3:
    mov     w12, #1
    str     w12, [x9, #OFF_PATH_READY]
    bl      release_lock

.Lsetstr_done:
    b       ret_setstr_hit

.Lsetstr_miss:
    strb    wzr, [sp, #0x20]
    b       ret_setstr_miss

.equ BIND_FRAME, 0x140

.global hook_bind_base
hook_bind_base:
    stp     x29, x30, [sp, #-BIND_FRAME]!
    mov     x29, sp

    ldr     x3, [x23, #0xc0]
    mov     w0, #0xb
    mov     w1, #0x2
    bl      fn_desc_write

    mov     x2, xzr
    ldrb    w9, [x20, #0x218]
    cbz     w9, .Lneed_dummy
    mov     x0, x24
    bl      fy_get_tex
    mov     x2, x0
    cbnz    x2, .Lwrite_slot12

.Lneed_dummy:
    mov     x2, x25
    cbnz    x2, .Lwrite_slot12
    ldr     x2, [x23, #0x118]
    cbnz    x2, .Lwrite_slot12
    mov     x0, x23
    add     x1, sp, #(BIND_FRAME + 0x98)
    bl      fn_get_dummy_tex
    ldr     x25, [sp, #(BIND_FRAME + 0x98)]
    mov     x2, x25

.Lwrite_slot12:
    add     x8, sp, #(BIND_FRAME + 0x290)
    ldr     x3, [x23, #0xc0]
    mov     w0, #0xc
    mov     w1, #0x2
    bl      fn_desc_write

    ldp     x29, x30, [sp], #BIND_FRAME
    b       ret_render_desc

// Base layer texture of one CNvFilterResourceManager: cached per manager, uploaded on first use.
// in: x0 = CNvFilterResourceManager*; out: x0 = CMraTexture* (0 when the upload failed)
.equ GT_FRAME, 0x150
.equ GT_MGR,   0x138
fy_get_tex:
    stp     x29, x30, [sp, #-GT_FRAME]!
    mov     x29, sp
    str     x0, [sp, #GT_MGR]

    bl      acquire_lock
    ldr     x8, [sp, #GT_MGR]
    add     x10, x9, #OFF_SLOTS
    mov     w11, #SLOTS_COUNT
.Lgt_lookup:
    ldr     x12, [x10]
    cmp     x12, x8
    b.eq    .Lgt_found
    add     x10, x10, #SLOT_SIZE
    subs    w11, w11, #1
    b.ne    .Lgt_lookup

    ldr     w11, [x9, #OFF_PATH_READY]
    cbz     w11, .Lgt_defpath
    ldrb    w11, [x9, #OFF_PATH]
    cbz     w11, .Lgt_defpath
    add     x12, x9, #OFF_PATH
    b       .Lgt_copy
.Lgt_defpath:
    adrp    x12, str_default_path
    add     x12, x12, :lo12:str_default_path
.Lgt_copy:
    add     x13, sp, #0x30
    mov     x14, xzr
    mov     w15, #255
.Lgt_cpy:
    ldrb    w16, [x12], #1
    strb    w16, [x13, x14]
    cbz     w16, .Lgt_cpydone
    add     x14, x14, #1
    subs    w15, w15, #1
    b.ne    .Lgt_cpy
    strb    wzr, [x13, x14]
.Lgt_cpydone:
    bl      release_lock

    mov     x10, #0x101
    str     x10, [sp, #0x10]
    str     x14, [sp, #0x18]
    str     x13, [sp, #0x20]
    str     xzr, [sp, #0x28]
    ldr     x0, [sp, #GT_MGR]
    add     x1, sp, #0x10
    mov     w2, #864
    mov     w3, #648
    add     x4, sp, #0x28
    bl      fn_upload_layer
    ldr     x2, [sp, #0x28]
    cbz     x2, .Lgt_fail

    bl      acquire_lock
    ldr     x2, [sp, #0x28]
    ldr     x8, [sp, #GT_MGR]
    add     x10, x9, #OFF_SLOTS
    mov     w11, #SLOTS_COUNT
    mov     x13, xzr
.Lgt_ins_scan:
    ldr     x12, [x10]
    cmp     x12, x8
    b.eq    .Lgt_ins_dup
    cbnz    x12, 1f
    cbnz    x13, 1f
    mov     x13, x10
1:
    add     x10, x10, #SLOT_SIZE
    subs    w11, w11, #1
    b.ne    .Lgt_ins_scan

    cbnz    x13, .Lgt_ins_free
    add     x13, x9, #OFF_SLOTS
    ldr     x14, [x13, #8]
    stp     x8, x2, [x13]
    str     x14, [sp, #0x10]
    bl      release_lock
    ldr     x0, [sp, #0x10]
    cbz     x0, .Lgt_ret_new
    bl      fn_obj_release
.Lgt_ret_new:
    ldr     x0, [sp, #0x28]
    b       .Lgt_ret

.Lgt_ins_free:
    stp     x8, x2, [x13]
    bl      release_lock
    ldr     x0, [sp, #0x28]
    b       .Lgt_ret

.Lgt_ins_dup:
    ldr     x14, [x10, #8]
    str     x14, [sp, #0x10]
    bl      release_lock
    ldr     x0, [sp, #0x28]
    cbz     x0, 1f
    bl      fn_obj_release
1:
    ldr     x0, [sp, #0x10]
    b       .Lgt_ret

.Lgt_found:
    ldr     x0, [x10, #8]
    str     x0, [sp, #0x10]
    bl      release_lock
    ldr     x0, [sp, #0x10]
    b       .Lgt_ret

.Lgt_fail:
    mov     x0, xzr
.Lgt_ret:
    ldp     x29, x30, [sp], #GT_FRAME
    ret

// CNvCompoundLut::PrepareCompoundLutYuvGpsProgram: after the fishEyeEdgeSampler unit (12) assignment, assign
// unit 13 to fishEyeBlackSampler. x0, x1, w2 are the arguments of the displaced setUniformValue call.
.global hook_yuv_uniform
hook_yuv_uniform:
    stp     x29, x30, [sp, #-0x20]!
    mov     x29, sp
    str     x0, [sp, #0x10]
    bl      fn_set_uniform
    ldr     x0, [sp, #0x10]
    adrp    x1, str_black_sampler
    add     x1, x1, :lo12:str_black_sampler
    mov     w2, #13
    bl      fn_set_uniform
    ldp     x29, x30, [sp], #0x20
    b       ret_yuv_uniform

// Per-thread handoff between the two RenderEffectYuvGps hooks (the manager is no longer in a register at the bind
// point): table of (tpidr_el0, texture) at g_state + OFF_TLS.
.equ OFF_TLS,   0x300
.equ TLS_COUNT, 8

// in: x1 = key, x0 = texture
tls_put:
    stp     x29, x30, [sp, #-0x20]!
    mov     x29, sp
    stp     x0, x1, [sp, #0x10]
    bl      acquire_lock
    ldp     x0, x1, [sp, #0x10]
    add     x10, x9, #OFF_TLS
    mov     w11, #TLS_COUNT
    mov     x13, xzr
1:
    ldr     x12, [x10]
    cmp     x12, x1
    b.eq    3f
    cbnz    x12, 2f
    cbnz    x13, 2f
    mov     x13, x10
2:
    add     x10, x10, #16
    subs    w11, w11, #1
    b.ne    1b
    mov     x10, x13
    cbnz    x10, 3f
    add     x10, x9, #OFF_TLS
3:
    stp     x1, x0, [x10]
    bl      release_lock
    ldp     x29, x30, [sp], #0x20
    ret

// in: x1 = key; out: x0 = texture or 0
tls_get:
    stp     x29, x30, [sp, #-0x20]!
    mov     x29, sp
    str     x1, [sp, #0x10]
    bl      acquire_lock
    ldr     x1, [sp, #0x10]
    add     x10, x9, #OFF_TLS
    mov     w11, #TLS_COUNT
    mov     x0, xzr
1:
    ldr     x12, [x10]
    cmp     x12, x1
    b.ne    2f
    ldr     x0, [x10, #8]
    b       3f
2:
    add     x10, x10, #16
    subs    w11, w11, #1
    b.ne    1b
3:
    str     x0, [sp, #0x10]
    bl      release_lock
    ldr     x0, [sp, #0x10]
    ldp     x29, x30, [sp], #0x20
    ret

// CNvCompoundLut::RenderEffectYuvGps, right after SetParamDefaultValue: x21 = ParamSet, x24 = the
// CNvFilterResourceManager (still intact here). With the fisheye switch on, fetch the base layer texture of that
// manager and remember it for this thread. Replaces `ldrb w8, [x21, #0x218]`; `cbz w8, <skip>` follows.
.global hook_yuv_prep
hook_yuv_prep:
    ldrb    w8, [x21, #0x218]
    cbz     w8, 9f
    stp     x29, x30, [sp, #-0x20]!
    mov     x29, sp
    mov     x0, x24
    bl      fy_get_tex
    mrs     x1, tpidr_el0
    bl      tls_put
    ldp     x29, x30, [sp], #0x20
    ldrb    w8, [x21, #0x218]
9:
    b       ret_yuv_prep

// CNvCompoundLut::RenderEffectYuvGps: after the fishEyeEdgeSampler texture (unit 12), bind this thread's base
// layer to unit 13 when the fisheye switch is on. Replaces `mov w0, #0x84cb` (glActiveTexture argument of the
// next unit), which is re-executed at the end.
.global hook_yuv_bind
hook_yuv_bind:
    stp     x29, x30, [sp, #-0x20]!
    mov     x29, sp
    ldrb    w9, [x21, #0x218]
    cbz     w9, 9f
    mrs     x1, tpidr_el0
    bl      tls_get
    cbz     x0, 9f
    str     x0, [sp, #0x10]
    mov     w0, #0x84cd
    bl      fn_gl_active
    ldr     x0, [sp, #0x10]
    ldr     x8, [x0]
    ldr     x8, [x8, #0x28]
    blr     x8
    mov     x1, x0
    mov     w0, #0xde1
    bl      fn_gl_bind
    mov     w0, #0xde1
    mov     w1, #0x2801
    mov     w2, #0x2601
    bl      fn_gl_param
    mov     w0, #0xde1
    mov     w1, #0x2800
    mov     w2, #0x2601
    bl      fn_gl_param
    mov     w0, #0xde1
    mov     w1, #0x2802
    mov     w2, #0x812f
    bl      fn_gl_param
    mov     w0, #0xde1
    mov     w1, #0x2803
    mov     w2, #0x812f
    bl      fn_gl_param
9:
    ldp     x29, x30, [sp], #0x20
    mov     w0, #0x84cb
    b       ret_yuv_bind


// Frame scale of a CompoundLut::ParamSet (CNvString "a:b" at +0x2e8, UTF-16 behind the QArrayData header).
// out w0 = 1 when it is 4:3 (b * 4 == a * 3) or cannot be read, else 0 -- the same test RenderEffectYuvGps makes.
fy_is43:
    ldr     x9, [x0, #0x2e8]
    cbz     x9, 5f
    ldr     x10, [x9, #0x10]
    add     x10, x9, x10
    ldr     w11, [x9, #4]
    mov     w12, wzr
    mov     w13, wzr
    mov     w14, wzr
    mov     w15, wzr
    mov     w17, #10
1:
    cmp     w15, w11
    b.hs    4f
    ldrh    w16, [x10, w15, uxtw #1]
    add     w15, w15, #1
    cmp     w16, #0x3a
    b.ne    2f
    mov     w14, #1
    b       1b
2:
    sub     w16, w16, #0x30
    cmp     w16, #9
    b.hi    1b
    cbnz    w14, 3f
    madd    w12, w12, w17, w16
    b       1b
3:
    madd    w13, w13, w17, w16
    b       1b
4:
    cbz     w12, 5f
    cbz     w14, 5f
    lsl     w9, w13, #2
    add     w10, w12, w12, lsl #1
    cmp     w9, w10
    cset    w0, eq
    ret
5:
    mov     w0, #1
    ret

// CompoundLut::GetCompundLutGraphicsPilelineUbo writes the fisheye switch (ParamSet +0x218) into the uniform block;
// the QSB shaders pad the frame (fyPad) only when it is 1. A frame scale other than 4:3 writes 2: those captures
// are cropped by the route of RenderEffectYuvGps already (square ones need no crop at all).
// Replaces `ldrb w1, [x20, #0x218]`; only x0-x17 are touched.
.global hook_fish_flag
hook_fish_flag:
    ldrb    w1, [x20, #0x218]
    cbz     w1, 9f
    stp     x29, x30, [sp, #-16]!
    mov     x0, x20
    bl      fy_is43
    ldp     x29, x30, [sp], #16
    mov     w1, #1
    cbnz    w0, 9f
    mov     w1, #2
9:
    b       ret_fish_flag

cleanup_mgr_tex:
    stp     x29, x30, [sp, #-0x30]!
    mov     x29, sp
    str     x0, [sp, #0x10]
.Lcln_retry:
    bl      acquire_lock
    ldr     x0, [sp, #0x10]
    add     x10, x9, #OFF_SLOTS
    mov     w11, #SLOTS_COUNT
.Lcln_loop:
    ldr     x12, [x10]
    cmp     x12, x0
    b.eq    .Lcln_hit
    add     x10, x10, #SLOT_SIZE
    subs    w11, w11, #1
    b.ne    .Lcln_loop
    bl      release_lock
    ldr     x0, [sp, #0x10]
    ldp     x29, x30, [sp], #0x30
    ret
.Lcln_hit:
    ldr     x14, [x10, #8]
    stp     xzr, xzr, [x10]
    str     x14, [sp, #0x18]
    bl      release_lock
    ldr     x0, [sp, #0x18]
    cbz     x0, .Lcln_retry
    bl      fn_obj_release
    b       .Lcln_retry

.global hook_mgr_dtor
hook_mgr_dtor:
    stp     x29, x30, [sp, #-0x20]!
    mov     x29, sp
    bl      cleanup_mgr_tex
    ldp     x29, x30, [sp], #0x20
    stp     x29, x30, [sp, #-0x20]!
    b       ret_dtor_body

.global hook_mgr_relres
hook_mgr_relres:
    stp     x29, x30, [sp, #-0x20]!
    mov     x29, sp
    bl      cleanup_mgr_tex
    ldp     x29, x30, [sp], #0x20
    stp     x29, x30, [sp, #-0x20]!
    b       ret_relres_body

.balign 4
str_key_layer_path:
    .asciz "Fish Eye Layer File Path"
str_black_sampler:
    .asciz "fishEyeBlackSampler"
str_default_path:
    .asciz "/odm/etc/camera/meishe_lut/fisheyelayer.rgba.bin"
