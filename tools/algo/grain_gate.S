/* Master grain for filters without stock grain. Inputs: GRAIN_AMOUNT_SLOT (stack offset of the
 * filter_grain_amount float in setMesheFilterParam), GRAIN_FRAME_BASE (stack offset of the filter
 * parameter struct held in x22), SKIP_STRING_SLOT (stack offset of the scratch std::string in
 * doFilterProcessV2); branch targets (--defsym): grain_join_next, grain_block, skip_after, skip_process,
 * skip_read_float; the key string (--defsym): key_grain_amount.
 *
 * grain_join replaces the instruction after all grain blocks of setMesheFilterParam. A negative
 * filter_grain_amount (the app's marker) is made positive and enters the grain block; a stock capture
 * never sends one and runs the displaced instruction.
 *
 * skip_gate replaces the empty-filter-type check of doFilterProcessV2 (cbnz w11, process): it reads
 * filter_grain_amount the way the function reads retro_filter_intensity and processes the node without
 * a LUT when it is negative.
 */
    .text
    .balign 4
    .global grain_join
    .global skip_gate
grain_join:
    ldr   s0, [sp, #GRAIN_AMOUNT_SLOT]
    fcmp  s0, #0.0
    b.mi  grain_force
    ldrb  w9, [x23]
    b     grain_join_next
grain_force:
    fneg  s0, s0
    str   s0, [sp, #GRAIN_AMOUNT_SLOT]
    add   x22, sp, #GRAIN_FRAME_BASE
    b     grain_block
skip_gate:
    cbnz  w11, skip_to_process
    cbnz  x8, skip_to_process
    adrp  x9, key_grain_amount
    add   x9, x9, :lo12:key_grain_amount
    ldr   x0, [x25, #0x110]
    ldr   q0, [x9]
    ldr   w10, [x9, #0x10]
    mov   w8, #0x26
    add   x1, sp, #SKIP_STRING_SLOT
    strb  w8, [sp, #SKIP_STRING_SLOT]
    stur  q0, [x21, #0x51]
    movi  d0, #0
    stur  w10, [x21, #0x61]
    bl    skip_read_float
    fcmp  s0, #0.0
    b.mi  skip_to_process
    b     skip_after
skip_to_process:
    b     skip_process
