// LightFlow on a portrait frame (POP 流光胶片, Ultra HDR route).
// The stock Meishe shader (CNvCompoundLut::RenderEffectYuvGps, uniforms lightFlowInputVertical / flipLighX / flipLighY) places the
// three light leaks in the landscape sensor frame: it mirrors the coordinate by flipLighX / flipLighY first and rotates it by
// 90 degrees afterwards when lightFlowInputVertical is set. The flip flags are defined in the landscape frame, so for a portrait
// input the mirror axes swap: the hooks below set the vertical flag when the frame is portrait (height > width) and swap flipLighX
// with flipLighY, which makes the leaks land where the landscape render rotated later by the camera puts them.
// Why the frame is portrait: with Ultra HDR the reference-frame processor runs the filter after hdr_transform and after the
// rotation of the quick image (libAlgoProcess, APSRefFrameProcessor), while the final image is filtered in the landscape frame.
// x20 = effect params (+0x2f0 flipLighX, +0x2f1 flipLighY, +0x326 lightFlowInputVertical), x22 = frame size {int width, int height}.
// Each hook executes the replaced stock `ldrb w2, [x20, #off]` and returns to the instruction after it.

.text
.balign 16

.global hook_lf_vertical            // 0x78d898: ldrb w2, [x20, #0x326]
hook_lf_vertical:
    ldrb    w2, [x20, #0x326]
    cbnz    w2, 1f
    cbz     x22, 1f
    ldr     w8, [x22]
    ldr     w9, [x22, #4]
    cmp     w9, w8
    cset    w2, hi
1:  b       ret_lf_vertical

.global hook_lf_flip_y              // 0x78d830: ldrb w2, [x20, #0x2f1]
hook_lf_flip_y:
    ldrb    w2, [x20, #0x2f1]
    cbz     x22, 1f
    ldr     w8, [x22]
    ldr     w9, [x22, #4]
    cmp     w9, w8
    b.ls    1f
    ldrb    w2, [x20, #0x2f0]
1:  b       ret_lf_flip_y

.global hook_lf_flip_x              // 0x78daa4: ldrb w2, [x20, #0x2f0]
hook_lf_flip_x:
    ldrb    w2, [x20, #0x2f0]
    cbz     x22, 1f
    ldr     w8, [x22]
    ldr     w9, [x22, #4]
    cmp     w9, w8
    b.ls    1f
    ldrb    w2, [x20, #0x2f1]
1:  b       ret_lf_flip_x
