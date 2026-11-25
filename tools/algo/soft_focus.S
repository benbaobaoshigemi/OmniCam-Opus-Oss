// Soft focus (POP "去锐化") for CNvComputeRenderer::DoProcessEffectsWithHarewareBuffer of ROM 17.0.1.200.
// The stock library lists soft focus as its own effect (id 9) next to the filter, which sends captures through the
// copy / RGBA effect chain / copy route. ROM 17.0.1.202 instead blurs once before the filter inside the single-effect route
// (CNvComputeRenderer::ProcessSoftFocus; CCD and Neutral CCD blur the frame they already copied). These hooks do the same.
//
// External symbols (--defsym), stock addresses:
//   plt_alloc      0xa584e0  CNvFilterResourceManager::AllocateGpuFrame(pos, TNvSmartPtr<CNvGpuFrame>*, format, bool)
//   plt_copy_init  0xa58690  CNvCopyEffect::InitRenderer
//   plt_ascii      0xa57d40  CNvString::fromAscii_helper
//   plt_copy       0xa588d0  CNvCopyEffect::RenderEffectYUVGPS
//   plt_conv_blur  0xa58560  CNvComputeRenderer::ConvertSurfaceBlurParams
//   plt_blur       0xa58570  CNvSurfaceBlur::RenderEffect
//   plt_blur_dtor  0xa586d0  CNvSurfaceBlur::ParamSet::~ParamSet
//   plt_copy_dtor  0xa586b0  CNvCopyEffect::ParamSet::~ParamSet
//   plt_sp_dtor    0xa58510  TNvSmartPtr<CNvGpuFrame>::~TNvSmartPtr
//   plt_compound   0xa58940  CNvCompoundLut::RenderEffectYuvGps
//   plt_set_origin 0xa58ae0  CNvGpuFrame::SetTextureOriginLocation
//   fn_release     0x9c02d8  frame reference release
//   c_copy_d 0x114e10, c_blur_d 0x114d90 (doubles in .rodata), str_copy 0xf6f60 (10 bytes, copy effect name)
//   ret_* : return points inside DoProcessEffectsWithHarewareBuffer, lbl_* : branch targets there
// Stock RenderContext (x21): +0xc position (16 bytes), +0x1c/+0x24 doubles, +0x2c/+0x2d/+0x2e flags, +0x30 effect id vector.
// Stock SNvfEffectParams ([x21]): +0x9c fisheye, +0xa4 soft focus enable, +0xac thresh, +0x198 Polaroid.

.text
.balign 16

.equ P_FISH,    0x9c
.equ P_SOFT,    0xa4
.equ P_THRESH,  0xac
.equ P_POLAR,   0x198

// w0 = soft focus is on (enable and thresh above 1e-5); x0 = params. Clobbers x0-x2.
soft_enabled:
    ldrb    w1, [x0, #P_SOFT]
    cbz     w1, 1f
    ldr     w1, [x0, #P_THRESH]
    and     w1, w1, #0x7fffffff
    mov     w2, #0xc5ac
    movk    w2, #0x3727, lsl #16
    cmp     w1, w2
    cset    w0, hi
    ret
1:
    mov     w0, #0
    ret

// x0 this, x1 input frame, x2 output frame, x3 RenderContext: copy the input into an RGBA frame and blur it into x2.
soft_process:
    sub     sp, sp, #0xd0
    stp     x29, x30, [sp, #0x90]
    add     x29, sp, #0x90
    stp     x24, x23, [sp, #0xa0]
    stp     x22, x21, [sp, #0xb0]
    stp     x20, x19, [sp, #0xc0]
    mov     x21, x3
    mov     x19, x2
    mov     x22, x1
    mov     x20, x0
    ldr     x8, [x1, #0x10]
    str     xzr, [sp, #0x38]
    ldur    q0, [x3, #0xc]
    ldr     x0, [x0, #0x20]
    ldr     w3, [x8, #0x18]
    str     q0, [sp, #0x40]
    add     x1, sp, #0x40
    add     x2, sp, #0x38
    mov     w4, wzr
    bl      plt_alloc
    ldr     x0, [x20, #0x108]
    bl      plt_copy_init
    adrp    x8, c_copy_d
    str     wzr, [sp, #0x14]
    stur    xzr, [sp, #0xc]
    strh    wzr, [sp, #0x24]
    ldr     d0, [x8, :lo12:c_copy_d]
    str     d0, [sp, #0x28]
    adrp    x0, str_copy
    mov     w1, #0xa
    add     x0, x0, :lo12:str_copy
    bl      plt_ascii
    ldur    d0, [x21, #0x24]
    str     x0, [sp, #0x30]
    ldrb    w8, [x21, #0x2c]
    mov     w24, #1
    ldrb    w9, [x21, #0x2e]
    strb    wzr, [sp, #0x15]
    str     d0, [sp]
    ldur    d0, [x21, #0x1c]
    ldr     x0, [x20, #0x108]
    strb    w8, [sp, #8]
    ldr     x2, [sp, #0x38]
    strb    w9, [sp, #0x16]
    ldr     x5, [x20, #0x20]
    stur    d0, [sp, #0x1c]
    strb    w24, [sp, #0x18]
    mov     x4, sp
    mov     x1, x22
    mov     w3, #1
    mov     w6, wzr
    bl      plt_copy
    adrp    x8, c_blur_d
    mov     w9, #0x3f800000
    sturb   wzr, [x29, #-0x30]
    stp     xzr, xzr, [x29, #-0x20]
    ldr     d0, [x8, :lo12:c_blur_d]
    stur    xzr, [x29, #-0x28]
    stur    w24, [x29, #-0x10]
    stur    w9, [x29, #-0x2c]
    stur    d0, [x29, #-0x38]
    sturb   wzr, [x29, #-0xc]
    mov     x0, x20
    sub     x3, x29, #0x38
    mov     w1, wzr
    mov     w2, wzr
    mov     x4, x21
    bl      plt_conv_blur
    // Polaroid keeps the flag clear (with fisheye the blurred frame came out flipped otherwise)
    ldr     x8, [x21]
    ldrb    w9, [x8, #P_POLAR]
    cbnz    w9, 3f
    ldrb    w8, [x21, #0x2e]
    sturb   w8, [x29, #-0x30]
3:
    ldr     x0, [x20, #0x138]
    ldr     x1, [sp, #0x38]
    ldr     x4, [x20, #0x20]
    sub     x3, x29, #0x38
    mov     x2, x19
    bl      plt_blur
    sub     x0, x29, #0x38
    bl      plt_blur_dtor
    mov     x0, sp
    bl      plt_copy_dtor
    add     x0, sp, #0x38
    bl      plt_sp_dtor
    ldp     x20, x19, [sp, #0xc0]
    ldp     x22, x21, [sp, #0xb0]
    ldp     x24, x23, [sp, #0xa0]
    ldp     x29, x30, [sp, #0x90]
    add     sp, sp, #0xd0
    ret

// x0 this, x1 RenderContext, x2 frame whose format the result takes, x3 position, x4 TNvSmartPtr<CNvGpuFrame>* (frame to blur,
// replaced by the blurred frame when soft focus is on).
soft_blur_slot:
    sub     sp, sp, #0xb0
    stp     x29, x30, [sp, #0x60]
    add     x29, sp, #0x60
    stp     x19, x20, [sp, #0x70]
    stp     x21, x22, [sp, #0x80]
    str     x23, [sp, #0x90]
    mov     x19, x0
    mov     x20, x1
    mov     x21, x2
    mov     x22, x3
    mov     x23, x4
    ldr     x0, [x20]
    bl      soft_enabled
    cbz     w0, 9f
    adrp    x8, c_blur_d
    mov     w9, #0x3f800000
    mov     w10, #1
    ldr     d0, [x8, :lo12:c_blur_d]
    strb    wzr, [sp, #0x28]
    stp     xzr, xzr, [sp, #0x38]
    str     xzr, [sp, #0x30]
    str     w9, [sp, #0x2c]
    str     w10, [sp, #0x48]
    str     d0, [sp, #0x20]
    strb    wzr, [sp, #0x4c]
    mov     x0, x19
    add     x3, sp, #0x20
    mov     w1, wzr
    mov     w2, wzr
    mov     x4, x20
    bl      plt_conv_blur
    ldr     x8, [x21, #0x10]
    ldr     x0, [x19, #0x20]
    ldr     w3, [x8, #0x18]
    mov     x1, x22
    str     xzr, [sp, #0x10]
    add     x2, sp, #0x10
    mov     w4, wzr
    bl      plt_alloc
    ldr     x0, [x19, #0x138]
    ldr     x1, [x23]
    ldr     x2, [sp, #0x10]
    add     x3, sp, #0x20
    ldr     x4, [x19, #0x20]
    bl      plt_blur
    ldr     x0, [x23]
    cbz     x0, 1f
    bl      fn_release
1:
    ldr     x8, [sp, #0x10]
    str     x8, [x23]
    add     x0, sp, #0x20
    bl      plt_blur_dtor
9:
    ldp     x21, x22, [sp, #0x80]
    ldr     x23, [sp, #0x90]
    ldp     x19, x20, [sp, #0x70]
    ldp     x29, x30, [sp, #0x60]
    add     sp, sp, #0xb0
    ret

// CNvCompoundLut::RenderEffectYuvGps(x0 lut, x1 in, w2 isYuv, x3 out, x4 params, x5 resource manager) with soft focus applied
// to the input first (x19 this, x21 RenderContext of the hooked function).
soft_compound:
    stp     x29, x30, [sp, #-0x70]!
    mov     x29, sp
    stp     x0, x1, [sp, #0x10]
    stp     x2, x3, [sp, #0x20]
    stp     x4, x5, [sp, #0x30]
    ldr     x0, [x21]
    bl      soft_enabled
    cbnz    w0, 1f
    ldp     x0, x1, [sp, #0x10]
    ldp     x2, x3, [sp, #0x20]
    ldp     x4, x5, [sp, #0x30]
    ldp     x29, x30, [sp], #0x70
    b       plt_compound
1:
    ldr     x1, [sp, #0x18]
    ldr     x8, [x1, #0x10]
    ldr     x0, [x19, #0x20]
    ldr     w3, [x8, #0x18]
    ldur    q0, [x21, #0xc]
    str     q0, [sp, #0x50]
    add     x1, sp, #0x50
    str     xzr, [sp, #0x40]
    add     x2, sp, #0x40
    mov     w4, wzr
    bl      plt_alloc
    mov     x0, x19
    ldr     x1, [sp, #0x18]
    ldr     x2, [sp, #0x40]
    mov     x3, x21
    bl      soft_process
    ldr     x0, [sp, #0x10]
    ldr     x1, [sp, #0x40]
    mov     w2, #0
    ldr     x3, [sp, #0x28]
    ldp     x4, x5, [sp, #0x30]
    bl      plt_compound
    ldr     x0, [sp, #0x40]
    cbz     x0, 2f
    bl      fn_release
2:
    ldp     x29, x30, [sp], #0x70
    ret

// 0x76ccb0 ldp x8, x9, [x21, #0x30]: a vector of one filter id and soft focus becomes the single filter id.
.global hook_sf_entry
hook_sf_entry:
    ldp     x8, x9, [x21, #0x30]
    sub     x10, x9, x8
    cmp     x10, #8
    b.ne    9f
    ldp     w10, w11, [x8]
    cmp     w10, #9
    b.eq    1f
    cmp     w11, #9
    b.ne    9f
    mov     w12, w10
    b       2f
1:
    mov     w12, w11
2:
    cmp     w12, #8
    b.hi    9f
    mov     w13, #0x1cd
    mov     w14, #1
    lsl     w14, w14, w12
    tst     w14, w13
    b.eq    9f
    str     w12, [x8]
    sub     x9, x9, #4
    str     x9, [x21, #0x38]
9:
    b       ret_sf_entry

// after the CCD / Neutral CCD copy into the 0.7 frame ([sp+0x18], position [sp+0x20]): blur it when soft focus is on
.global hook_sf_ccd
hook_sf_ccd:
    mov     x0, x19
    mov     x1, x21
    mov     x2, x22
    add     x3, sp, #0x20
    add     x4, sp, #0x18
    bl      soft_blur_slot
    mov     w8, #0x4000
    b       ret_sf_ccd

.global hook_sf_neutral
hook_sf_neutral:
    mov     x0, x19
    mov     x1, x21
    mov     x2, x22
    add     x3, sp, #0x20
    add     x4, sp, #0x18
    bl      soft_blur_slot
    mov     w8, #0x4000
    b       ret_sf_neutral

// Cinestill 800T, Portra 400 and Polaroid draw through the compound pass when soft focus is on (as in the newer ROM)
.macro ROUTE name, ret, a, b
.global \name
\name:
    orr     \a, \a, \b
    stp     x0, x1, [sp, #-0x30]!
    stp     x2, x30, [sp, #0x10]
    ldr     x0, [x21]
    bl      soft_enabled
    cbz     w0, 1f
    ldp     x2, x30, [sp, #0x10]
    ldp     x0, x1, [sp], #0x30
    mov     \a, #1
    b       \ret
1:
    ldp     x2, x30, [sp, #0x10]
    ldp     x0, x1, [sp], #0x30
    b       \ret
.endm
ROUTE hook_sf_route_800t, ret_sf_route_800t, w8, w9
ROUTE hook_sf_route_pt400, ret_sf_route_pt400, w8, w9
ROUTE hook_sf_route_polaroid, ret_sf_route_polaroid, w9, w10

// 0x76d5e8 ldrb w8, [x8, #0xa4]: a lone soft focus effect blurs only, a filter with soft focus goes through the compound pass
.global hook_sf_default
hook_sf_default:
    ldr     x0, [x21]
    bl      soft_enabled
    cbnz    w0, 2f
    b       lbl_sf_plain
2:
    ldp     x9, x10, [x21, #0x30]
    sub     x10, x10, x9
    cmp     x10, #4
    b.ne    1f
    ldr     w9, [x9]
    cmp     w9, #9
    b.ne    1f
    b       lbl_sf_only
1:
    ldr     x0, [x19, #0xf8]
    ldr     x5, [x19, #0x20]
    add     x4, sp, #0x4b8
    mov     x1, x22
    mov     w2, #1
    mov     x3, x20
    bl      soft_compound
    b       lbl_sf_done

// CNvSurfaceBlur::RenderEffect(x0 this, x1 in, x2 out, x3 ParamSet, x4 resMgr)
// Unlike 17.0.1.202, 17.0.1.200 does not reset outFrame's OriginLocation to 0 after FBO rendering when ParamSet+0x8 != 0,
// leaving it at 1 (TopLeft). Portra 400's LowQualityBlur(isYuv=0) then skips a flip in CopyGpuFrame while GaussianBlur(+0xb=1)
// still flips once, producing a vertically mirrored auxSampler. Reset OriginLocation(0) on return to match 17.0.1.202.
surface_blur_stock:
    sub     sp, sp, #0x160
    b       ret_surface_blur

.global hook_surface_blur
hook_surface_blur:
    stp     x29, x30, [sp, #-0x30]!
    mov     x29, sp
    stp     x19, x20, [sp, #0x10]
    mov     x19, x2
    mov     x20, x3
    bl      surface_blur_stock
    cbz     w0, 1f
    ldrb    w8, [x20, #8]
    cbz     w8, 1f
    str     w0, [sp, #0x20]
    mov     x0, x19
    mov     w1, wzr
    bl      plt_set_origin
    ldr     w0, [sp, #0x20]
1:
    ldp     x19, x20, [sp, #0x10]
    ldp     x29, x30, [sp], #0x30
    ret
