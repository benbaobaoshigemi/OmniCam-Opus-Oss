/* Colour palette nodes for the addCommonPipelines of ApsCapDecisionRearNormal25021, RearBokeh25021,
 * FrontBokeh25021 and FrontNormal25021. Each pipeline's first instruction after its algorithm gates is
 * replaced by a branch to one entry below. The entry calls the shared routine with
 *   x0 = x22, x1 = x20 (AlgoProcessData array), x2 = x21 (AlgoProcessData), x3 = x19 (node counter),
 *   x4 = load bias (entry address minus its link address), x5 = node table of the pipeline.
 * The routine returns 0 when the palette algorithm is off, appends paletteInit/Proc/Uninit when Ultra HDR
 * is on, and falls back to the library's own single node 0x458 otherwise. Link-time inputs (-D): ENTRY0..3,
 * GOT_IS_ALGO_ENABLED, FN_META_BOOL, FN_ADD_DEFINITE; branch targets (--defsym): rear_skip, rear_cont,
 * rearbokeh_cont, frontbokeh_cont, frontnormal_cont.
 */
    .text
    .balign 4
    .global cave_start
cave_start:
entry_rearnormal:
    .inst 0xd503245f /* bti c */
    .inst 0xa9bf7bfd /* stp x29, x30, [sp, #-0x10]! */
    .inst 0xaa1603e0 /* mov x0, x22 */
    .inst 0xaa1403e1 /* mov x1, x20 */
    .inst 0xaa1503e2 /* mov x2, x21 */
    .inst 0xaa1303e3 /* mov x3, x19 */
    adr x4, entry_rearnormal
    movz x9, #(ENTRY0 & 0xffff)
    movk x9, #((ENTRY0 >> 16) & 0xffff), lsl #16
    .inst 0xcb090084 /* sub x4, x4, x9 */
    bl L40
    .inst 0xa8c17bfd /* ldp x29, x30, [sp], #0x10 */
    cbz w0, L38
    b rear_skip
L38:
    .inst 0xf94002c8 /* ldr x8, [x22] */
    b rear_cont
L40:
    .inst 0xd101c3ff /* sub sp, sp, #0x70 */
    movz w8, #(GOT_IS_ALGO_ENABLED & 0xffff)
    .inst 0xa90367fa /* stp x26, x25, [sp, #0x30] */
    movk w8, #((GOT_IS_ALGO_ENABLED >> 16) & 0xffff), lsl #16
    .inst 0xa9045ff8 /* stp x24, x23, [sp, #0x40] */
    .inst 0xaa0003f7 /* mov x23, x0 */
    .inst 0xf868689a /* ldr x26, [x4, x8] */
    .inst 0xa9064ff4 /* stp x20, x19, [sp, #0x60] */
    .inst 0xaa0103f4 /* mov x20, x1 */
    .inst 0xaa0203e0 /* mov x0, x2 */
    .inst 0x52800d81 /* mov w1, #0x6c */
    .inst 0xa9027bfd /* stp x29, x30, [sp, #0x20] */
    .inst 0xa90557f6 /* stp x22, x21, [sp, #0x50] */
    .inst 0x910083fd /* add x29, sp, #0x20 */
    .inst 0xaa0403f6 /* mov x22, x4 */
    .inst 0xaa0303f3 /* mov x19, x3 */
    .inst 0xaa0203f5 /* mov x21, x2 */
    .inst 0xd63f0340 /* blr x26 */
    cbz w0, L1d8
    .inst 0xd503201f /* nop  */
    adr x8, str_ultra_hdr_a
    .inst 0x5290220b /* mov w11, #0x8110 */
    .inst 0xa9402109 /* ldp x9, x8, [x8] */
    .inst 0xf86b6aa0 /* ldr x0, [x21, x11] */
    .inst 0x52800caa /* mov w10, #0x65 */
    .inst 0x910023e1 /* add x1, sp, #8 */
    .inst 0x2a1f03e2 /* mov w2, wzr */
    .inst 0xf9000fea /* str x10, [sp, #0x18] */
    .inst 0xa900a3e9 /* stp x9, x8, [sp, #8] */
    movz w9, #(FN_META_BOOL & 0xffff)
    movk w9, #((FN_META_BOOL >> 16) & 0xffff), lsl #16
    .inst 0x8b0902c8 /* add x8, x22, x9 */
    .inst 0xd63f0100 /* blr x8 */
    .inst 0x2a0003f9 /* mov w25, w0 */
    .inst 0xaa1503e0 /* mov x0, x21 */
    .inst 0x52800221 /* mov w1, #0x11 */
    .inst 0xd63f0340 /* blr x26 */
    .inst 0x2a0003f8 /* mov w24, w0 */
    cbz w25, L190
    .inst 0xaa1503e0 /* mov x0, x21 */
    .inst 0x52800aa1 /* mov w1, #0x55 */
    .inst 0xd63f0340 /* blr x26 */
    .inst 0xb9800269 /* ldrsw x9, [x19] */
    .inst 0x7100031f /* cmp w24, #0 */
    .inst 0x52800108 /* mov w8, #8 */
    .inst 0x528000ab /* mov w11, #5 */
    .inst 0x5284158a /* mov w10, #0x20ac */
    .inst 0x1a880168 /* csel w8, w11, w8, eq */
    .inst 0x9b2a5129 /* smaddl x9, w9, w10, x20 */
    .inst 0x1b0a7d0a /* mul w10, w8, w10 */
    .inst 0xaa0903ec /* mov x12, x9 */
    .inst 0xaa0a03eb /* mov x11, x10 */
    cbz x11, L120
L114:
    .inst 0x3800159f /* strb wzr, [x12], #1 */
    .inst 0xd100056b /* sub x11, x11, #1 */
    cbnz x11, L114
L120:
    adrp x11, table_rear
    add x11, x11, :lo12:table_rear
    .inst 0x5280092c /* mov w12, #0x49 */
    cbz x12, L150
L130:
    .inst 0x785fc16d /* ldurh w13, [x11, #-4] */
    .inst 0xeb0d095f /* cmp x10, x13, lsl #2 */
    b.ls L144
    .inst 0xb940016e /* ldr w14, [x11] */
    .inst 0xb82d792e /* str w14, [x9, x13, lsl #2] */
L144:
    .inst 0x9100216b /* add x11, x11, #8 */
    .inst 0xd100058c /* sub x12, x12, #1 */
    cbnz x12, L130
L150:
    .inst 0x9284156c /* mov x12, #-0x20ac */
    .inst 0xaa1f03ea /* mov x10, xzr */
    .inst 0x911a212b /* add x11, x9, #0x688 */
    .inst 0x8b0c012c /* add x12, x9, x12 */
L160:
    .inst 0xf101015f /* cmp x10, #0x40 */
    b.eq L178
    .inst 0x386a698d /* ldrb w13, [x12, x10] */
    .inst 0x382a696d /* strb w13, [x11, x10] */
    .inst 0x9100054a /* add x10, x10, #1 */
    b L160
L178:
    cbz w0, L1c8
    .inst 0x528d218a /* mov w10, #0x690c */
    .inst 0x528d228b /* mov w11, #0x6914 */
    .inst 0xf82a693f /* str xzr, [x9, x10] */
    .inst 0xb82b693f /* str wzr, [x9, x11] */
    b L1c8
L190:
    cbz w24, L19c
    .inst 0x2a1f03e0 /* mov w0, wzr */
    b L1d8
L19c:
    movz w8, #(FN_ADD_DEFINITE & 0xffff)
    .inst 0xaa1703e0 /* mov x0, x23 */
    .inst 0xaa1403e1 /* mov x1, x20 */
    movk w8, #((FN_ADD_DEFINITE >> 16) & 0xffff), lsl #16
    .inst 0xaa1503e2 /* mov x2, x21 */
    .inst 0xaa1303e3 /* mov x3, x19 */
    .inst 0x8b0802c8 /* add x8, x22, x8 */
    .inst 0x52808b04 /* mov w4, #0x458 */
    .inst 0x2a1f03e5 /* mov w5, wzr */
    .inst 0xd63f0100 /* blr x8 */
    .inst 0x52800028 /* mov w8, #1 */
L1c8:
    .inst 0xb9400269 /* ldr w9, [x19] */
    .inst 0x52800020 /* mov w0, #1 */
    .inst 0x0b080128 /* add w8, w9, w8 */
    .inst 0xb9000268 /* str w8, [x19] */
L1d8:
    .inst 0xa9464ff4 /* ldp x20, x19, [sp, #0x60] */
    .inst 0xa94557f6 /* ldp x22, x21, [sp, #0x50] */
    .inst 0xa9445ff8 /* ldp x24, x23, [sp, #0x40] */
    .inst 0xa94367fa /* ldp x26, x25, [sp, #0x30] */
    .inst 0xa9427bfd /* ldp x29, x30, [sp, #0x20] */
    .inst 0x9101c3ff /* add sp, sp, #0x70 */
    .inst 0xd65f03c0 /* ret  */
    .balign 4
str_ultra_hdr_a:
    .word 0x746c7520, 0x685f6172, 0x655f7264, 0x6c62616e, 0x00000065, 0x00000000, 0x00000000
table_rear:
    .word 0x79706f63, 0x00000001, 0x72646855, 0x00000002, 0x30313050, 0x00000012, 0x00000442, 0x00000013, 0x00000003, 0x0000082b, 0x6765706a, 0x0000082c, 0x63725372, 0x0000082d, 0x65646f43, 0x0000082e, 0x00000063, 0x0000083d, 0x00000440, 0x0000083e, 0x00000003, 0x0000083f, 0x0000004c, 0x00000841, 0x00000443, 0x00000842, 0x00000003, 0x000009cd, 0x79706f63, 0x000009ce, 0x72646855, 0x000009cf, 0x30313050, 0x000009dd, 0x54726468, 0x000009de, 0x736e6172, 0x000009df, 0x6d726f66, 0x000009e0, 0x74696e49, 0x00001056, 0x656c6170, 0x00001057, 0x49657474, 0x00001058, 0x0074696e, 0x00001068, 0x00000458, 0x00001881, 0x656c6170, 0x00001882, 0x50657474, 0x00001883, 0x00636f72, 0x00001893, 0x00000458, 0x00001894, 0x00000003, 0x00001895, 0x00000040, 0x00001a23, 0x656c6170, 0x00001a24, 0x49657474, 0x00001a25, 0x0074696e, 0x00001a33, 0x79706f63, 0x00001a34, 0x72646855, 0x00001a35, 0x30313050, 0x00001a43, 0x50707372, 0x00001a44, 0x65636f72, 0x00001a45, 0x00007373, 0x000020ac, 0x656c6170, 0x000020ad, 0x55657474, 0x000020ae, 0x696e696e, 0x000020af, 0x00000074, 0x000020be, 0x00000458, 0x000020bf, 0x00000001, 0x0000224e, 0x656c6170, 0x0000224f, 0x50657474, 0x00002250, 0x00636f72, 0x000028d7, 0x746c6966, 0x000028d8, 0x6e497265, 0x000028d9, 0x00007469, 0x000028e7, 0x00000001, 0x000028e9, 0x000003fd, 0x00003102, 0x746c6966, 0x00003103, 0x72507265, 0x00003104, 0x0000636f, 0x00003114, 0x000003fd, 0x00003115, 0x00000003, 0x000032a4, 0x746c6966, 0x000032a5, 0x6e497265, 0x000032a6, 0x00007469, 0x000032b4, 0x656c6170, 0x000032b5, 0x50657474, 0x000032b6, 0x00636f72, 0x0000392d, 0x746c6966, 0x0000392e, 0x6e557265, 0x0000392f, 0x74696e69, 0x0000393f, 0x000003fd, 0x00003940, 0x00000001, 0x00003acf, 0x746c6966, 0x00003ad0, 0x72507265, 0x00003ad1, 0x0000636f, 0x00000000, 0x00000000, 0x00000000
entry_rearbokeh:
    .inst 0xa9bf7bfd /* stp x29, x30, [sp, #-0x10]! */
    .inst 0xaa1603e0 /* mov x0, x22 */
    .inst 0xaa1403e1 /* mov x1, x20 */
    .inst 0xaa1503e2 /* mov x2, x21 */
    .inst 0xaa1303e3 /* mov x3, x19 */
    adr x4, entry_rearbokeh
    adr x5, L7e0
    movz x9, #(ENTRY1 & 0xffff)
    movk x9, #((ENTRY1 >> 16) & 0xffff), lsl #16
    .inst 0xcb090084 /* sub x4, x4, x9 */
    bl L508
    .inst 0xa8c17bfd /* ldp x29, x30, [sp], #0x10 */
    .inst 0xf94002c8 /* ldr x8, [x22] */
    b rearbokeh_cont
entry_frontbokeh:
    .inst 0xa9bf7bfd /* stp x29, x30, [sp, #-0x10]! */
    .inst 0xaa1603e0 /* mov x0, x22 */
    .inst 0xaa1403e1 /* mov x1, x20 */
    .inst 0xaa1503e2 /* mov x2, x21 */
    .inst 0xaa1303e3 /* mov x3, x19 */
    adr x4, entry_frontbokeh
    adr x5, L8ac
    movz x9, #(ENTRY2 & 0xffff)
    movk x9, #((ENTRY2 >> 16) & 0xffff), lsl #16
    .inst 0xcb090084 /* sub x4, x4, x9 */
    bl L508
    .inst 0xa8c17bfd /* ldp x29, x30, [sp], #0x10 */
    .inst 0xf94002c8 /* ldr x8, [x22] */
    b frontbokeh_cont
entry_frontnormal:
    .inst 0xa9bf7bfd /* stp x29, x30, [sp, #-0x10]! */
    .inst 0xaa1603e0 /* mov x0, x22 */
    .inst 0xaa1403e1 /* mov x1, x20 */
    .inst 0xaa1503e2 /* mov x2, x21 */
    .inst 0xaa1303e3 /* mov x3, x19 */
    adr x4, entry_frontnormal
    adr x5, L9a0
    movz x9, #(ENTRY3 & 0xffff)
    movk x9, #((ENTRY3 >> 16) & 0xffff), lsl #16
    .inst 0xcb090084 /* sub x4, x4, x9 */
    bl L508
    .inst 0xa8c17bfd /* ldp x29, x30, [sp], #0x10 */
    .inst 0xf94002c8 /* ldr x8, [x22] */
    b frontnormal_cont
L508:
    .inst 0xd101c3ff /* sub sp, sp, #0x70 */
    movz w8, #(GOT_IS_ALGO_ENABLED & 0xffff)
    movk w8, #((GOT_IS_ALGO_ENABLED >> 16) & 0xffff), lsl #16
    .inst 0xa90367fa /* stp x26, x25, [sp, #0x30] */
    .inst 0xa9045ff8 /* stp x24, x23, [sp, #0x40] */
    .inst 0xaa0503f8 /* mov x24, x5 */
    .inst 0xaa0003f7 /* mov x23, x0 */
    .inst 0xf868689a /* ldr x26, [x4, x8] */
    .inst 0xa9064ff4 /* stp x20, x19, [sp, #0x60] */
    .inst 0xaa0103f4 /* mov x20, x1 */
    .inst 0xaa0203e0 /* mov x0, x2 */
    .inst 0x52800d81 /* mov w1, #0x6c */
    .inst 0xa9027bfd /* stp x29, x30, [sp, #0x20] */
    .inst 0xa90557f6 /* stp x22, x21, [sp, #0x50] */
    .inst 0x910083fd /* add x29, sp, #0x20 */
    .inst 0xaa0403f6 /* mov x22, x4 */
    .inst 0xaa0303f3 /* mov x19, x3 */
    .inst 0xaa0203f5 /* mov x21, x2 */
    .inst 0xd63f0340 /* blr x26 */
    cbz w0, L7b0
    adr x8, L7d0
    .inst 0x5290220b /* mov w11, #0x8110 */
    .inst 0xa9402109 /* ldp x9, x8, [x8] */
    .inst 0xf86b6aa0 /* ldr x0, [x21, x11] */
    .inst 0x52800caa /* mov w10, #0x65 */
    .inst 0x910023e1 /* add x1, sp, #8 */
    .inst 0x2a1f03e2 /* mov w2, wzr */
    .inst 0xf9000fea /* str x10, [sp, #0x18] */
    .inst 0xa900a3e9 /* stp x9, x8, [sp, #8] */
    movz w9, #(FN_META_BOOL & 0xffff)
    movk w9, #((FN_META_BOOL >> 16) & 0xffff), lsl #16
    .inst 0x8b0902c8 /* add x8, x22, x9 */
    .inst 0xd63f0100 /* blr x8 */
    .inst 0x2a0003f9 /* mov w25, w0 */
    cbz w25, L75c
    .inst 0xb9800269 /* ldrsw x9, [x19] */
    .inst 0x5284158a /* mov w10, #0x20ac */
    .inst 0x9b2a5129 /* smaddl x9, w9, w10, x20 */
    .inst 0x528c408a /* mov w10, #0x6204 */
    .inst 0xaa0903ec /* mov x12, x9 */
    .inst 0xaa0a03eb /* mov x11, x10 */
L5ac:
    cbz x11, L5bc
    .inst 0x3800159f /* strb wzr, [x12], #1 */
    .inst 0xd100056b /* sub x11, x11, #1 */
    cbnz x11, L5ac
L5bc:
    .inst 0xb940030c /* ldr w12, [x24] */
    .inst 0x9100430b /* add x11, x24, #0x10 */
L5c4:
    cbz x12, L5e0
    .inst 0xb85fc16d /* ldur w13, [x11, #-4] */
    .inst 0xb940016e /* ldr w14, [x11] */
    .inst 0xb82d792e /* str w14, [x9, x13, lsl #2] */
    .inst 0x9100216b /* add x11, x11, #8 */
    .inst 0xd100058c /* sub x12, x12, #1 */
    b L5c4
L5e0:
    .inst 0xb9400b0f /* ldr w15, [x24, #8] */
    cbz w15, L6b4
    .inst 0xaa1503e0 /* mov x0, x21 */
    .inst 0x2a0f03e1 /* mov w1, w15 */
    .inst 0xd63f0340 /* blr x26 */
    cbnz w0, L618
    .inst 0xb9800269 /* ldrsw x9, [x19] */
    .inst 0x5284158a /* mov w10, #0x20ac */
    .inst 0x9b2a5129 /* smaddl x9, w9, w10, x20 */
    .inst 0x5284158f /* mov w15, #0x20ac */
    .inst 0x8b0f012b /* add x11, x9, x15 */
    .inst 0x911c216b /* add x11, x11, #0x708 */
    .inst 0xa9007d7f /* stp xzr, xzr, [x11] */
    b L6b4
L618:
    .inst 0xb9800270 /* ldrsw x16, [x19] */
    .inst 0xaa1403ed /* mov x13, x20 */
L620:
    cbz x16, L6b4
    .inst 0xb94001ae /* ldr w14, [x13] */
    .inst 0x528e6e4f /* mov w15, #0x7372 */
    .inst 0x72aaae0f /* movk w15, #0x5570, lsl #16 */
    .inst 0x6b0f01df /* cmp w14, w15 */
    b.ne L6a4
    .inst 0xb94005ae /* ldr w14, [x13, #4] */
    .inst 0x528d2dcf /* mov w15, #0x696e */
    .inst 0x72ad2dcf /* movk w15, #0x696e, lsl #16 */
    .inst 0x6b0f01df /* cmp w14, w15 */
    b.ne L6a4
    .inst 0xb94009ae /* ldr w14, [x13, #8] */
    .inst 0x7101d1df /* cmp w14, #0x74 */
    b.ne L6a4
    .inst 0x911a21ac /* add x12, x13, #0x688 */
    .inst 0xd2800211 /* mov x17, #0x10 */
L660:
    cbz x17, L6b4
    .inst 0xb940018e /* ldr w14, [x12] */
    cbnz w14, L698
    .inst 0x528c2e0f /* mov w15, #0x6170 */
    .inst 0x72acad8f /* movk w15, #0x656c, lsl #16 */
    .inst 0xb900018f /* str w15, [x12] */
    .inst 0x528e8e8f /* mov w15, #0x7474 */
    .inst 0x72aa0caf /* movk w15, #0x5065, lsl #16 */
    .inst 0xb900058f /* str w15, [x12, #4] */
    .inst 0x528dee4f /* mov w15, #0x6f72 */
    .inst 0x72a00c6f /* movk w15, #0x63, lsl #16 */
    .inst 0xb900098f /* str w15, [x12, #8] */
    .inst 0xb9000d9f /* str wzr, [x12, #0xc] */
    b L6b4
L698:
    .inst 0x9101018c /* add x12, x12, #0x40 */
    .inst 0xd1000631 /* sub x17, x17, #1 */
    b L660
L6a4:
    .inst 0x5284158f /* mov w15, #0x20ac */
    .inst 0x8b0f01ad /* add x13, x13, x15 */
    .inst 0xd1000610 /* sub x16, x16, #1 */
    b L620
L6b4:
    .inst 0xb9800269 /* ldrsw x9, [x19] */
    .inst 0x5284158a /* mov w10, #0x20ac */
    .inst 0x9b2a5129 /* smaddl x9, w9, w10, x20 */
    .inst 0xb940070f /* ldr w15, [x24, #4] */
    cbz w15, L748
    .inst 0xb9800270 /* ldrsw x16, [x19] */
    .inst 0xaa1403ed /* mov x13, x20 */
L6d0:
    cbz x16, L748
    .inst 0xb94001ae /* ldr w14, [x13] */
    .inst 0x528dec6f /* mov w15, #0x6f63 */
    .inst 0x72af2e0f /* movk w15, #0x7970, lsl #16 */
    .inst 0x6b0f01df /* cmp w14, w15 */
    b.ne L738
    .inst 0xb94005ae /* ldr w14, [x13, #4] */
    .inst 0x528d0aaf /* mov w15, #0x6855 */
    .inst 0x72ae4c8f /* movk w15, #0x7264, lsl #16 */
    .inst 0x6b0f01df /* cmp w14, w15 */
    b.ne L738
    .inst 0xb94009ae /* ldr w14, [x13, #8] */
    .inst 0x52860a0f /* mov w15, #0x3050 */
    .inst 0x72a6062f /* movk w15, #0x3031, lsl #16 */
    .inst 0x6b0f01df /* cmp w14, w15 */
    b.ne L738
    .inst 0x911a21ac /* add x12, x13, #0x688 */
    .inst 0x5284158f /* mov w15, #0x20ac */
    .inst 0x8b0f012b /* add x11, x9, x15 */
    .inst 0x911b216b /* add x11, x11, #0x6c8 */
    .inst 0xd280078a /* mov x10, #0x3c */
L724:
    .inst 0xa8c13d8e /* ldp x14, x15, [x12], #0x10 */
    .inst 0xa8813d6e /* stp x14, x15, [x11], #0x10 */
    .inst 0xd100054a /* sub x10, x10, #1 */
    cbnz x10, L724
    b L748
L738:
    .inst 0x5284158f /* mov w15, #0x20ac */
    .inst 0x8b0f01ad /* add x13, x13, x15 */
    .inst 0xd1000610 /* sub x16, x16, #1 */
    b L6d0
L748:
    .inst 0xb9400268 /* ldr w8, [x19] */
    .inst 0x11000d08 /* add w8, w8, #3 */
    .inst 0xb9000268 /* str w8, [x19] */
    .inst 0x52800020 /* mov w0, #1 */
    b L7b4
L75c:
    .inst 0xaa1503e0 /* mov x0, x21 */
    .inst 0x52800221 /* mov w1, #0x11 */
    .inst 0xd63f0340 /* blr x26 */
    cbz w0, L774
    .inst 0x2a1f03e0 /* mov w0, wzr */
    b L7b4
L774:
    movz w8, #(FN_ADD_DEFINITE & 0xffff)
    movk w8, #((FN_ADD_DEFINITE >> 16) & 0xffff), lsl #16
    .inst 0xaa1703e0 /* mov x0, x23 */
    .inst 0xaa1403e1 /* mov x1, x20 */
    .inst 0xaa1503e2 /* mov x2, x21 */
    .inst 0xaa1303e3 /* mov x3, x19 */
    .inst 0x8b0802c8 /* add x8, x22, x8 */
    .inst 0x52808b04 /* mov w4, #0x458 */
    .inst 0x2a1f03e5 /* mov w5, wzr */
    .inst 0xd63f0100 /* blr x8 */
    .inst 0xb9400269 /* ldr w9, [x19] */
    .inst 0x11000529 /* add w9, w9, #1 */
    .inst 0xb9000269 /* str w9, [x19] */
    .inst 0x52800020 /* mov w0, #1 */
    b L7b4
L7b0:
    .inst 0x2a1f03e0 /* mov w0, wzr */
L7b4:
    .inst 0xa9464ff4 /* ldp x20, x19, [sp, #0x60] */
    .inst 0xa94557f6 /* ldp x22, x21, [sp, #0x50] */
    .inst 0xa9445ff8 /* ldp x24, x23, [sp, #0x40] */
    .inst 0xa94367fa /* ldp x26, x25, [sp, #0x30] */
    .inst 0xa9427bfd /* ldp x29, x30, [sp, #0x20] */
    .inst 0x9101c3ff /* add sp, sp, #0x70 */
    .inst 0xd65f03c0 /* ret  */
    .balign 4
L7d0:
    .word 0x746c7520, 0x685f6172, 0x655f7264, 0x6c62616e
L7e0:
    .word 0x00000018, 0x00000000, 0x00000000, 0x00000000, 0x656c6170, 0x00000001, 0x49657474, 0x00000002, 0x0074696e, 0x00000012, 0x00000458, 0x0000082b, 0x656c6170, 0x0000082c, 0x50657474, 0x0000082d, 0x00636f72, 0x0000083d, 0x00000458, 0x0000083e, 0x00000003, 0x0000083f, 0x00000040, 0x000009cd, 0x656c6170, 0x000009ce, 0x49657474, 0x000009cf, 0x0074696e, 0x000009dd, 0x656b6f62, 0x000009de, 0x00003368, 0x00001056, 0x656c6170, 0x00001057, 0x55657474, 0x00001058, 0x696e696e, 0x00001059, 0x00000074, 0x00001068, 0x00000458, 0x00001069, 0x00000001, 0x000011f8, 0x656c6170, 0x000011f9, 0x50657474, 0x000011fa, 0x00636f72
L8ac:
    .word 0x0000001d, 0x00000000, 0x00000065, 0x00000000, 0x656c6170, 0x00000001, 0x49657474, 0x00000002, 0x0074696e, 0x00000012, 0x00000458, 0x0000082b, 0x656c6170, 0x0000082c, 0x50657474, 0x0000082d, 0x00636f72, 0x0000083d, 0x00000458, 0x0000083e, 0x00000003, 0x0000083f, 0x00000040, 0x000009cd, 0x656c6170, 0x000009ce, 0x49657474, 0x000009cf, 0x0074696e, 0x000009dd, 0x79706f63, 0x000009de, 0x72646855, 0x000009df, 0x30313050, 0x000009e0, 0x00000000, 0x000009ed, 0x50707372, 0x000009ee, 0x65636f72, 0x000009ef, 0x00007373, 0x00001056, 0x656c6170, 0x00001057, 0x55657474, 0x00001058, 0x696e696e, 0x00001059, 0x00000074, 0x00001068, 0x00000458, 0x00001069, 0x00000001, 0x000011f8, 0x656c6170, 0x000011f9, 0x50657474, 0x000011fa, 0x00636f72
L9a0:
    .word 0x0000001e, 0x00000000, 0x00000065, 0x00000000, 0x656c6170, 0x00000001, 0x49657474, 0x00000002, 0x0074696e, 0x00000012, 0x00000458, 0x0000082b, 0x656c6170, 0x0000082c, 0x50657474, 0x0000082d, 0x00636f72, 0x0000083d, 0x00000458, 0x0000083e, 0x00000003, 0x0000083f, 0x00000040, 0x000009cd, 0x656c6170, 0x000009ce, 0x49657474, 0x000009cf, 0x0074696e, 0x000009dd, 0x64486266, 0x000009de, 0x6e6f4372, 0x000009df, 0x74726576, 0x000009e0, 0x6e696e55, 0x000009e1, 0x00007469, 0x000009ed, 0x50707372, 0x000009ee, 0x65636f72, 0x000009ef, 0x00007373, 0x00001056, 0x656c6170, 0x00001057, 0x55657474, 0x00001058, 0x696e696e, 0x00001059, 0x00000074, 0x00001068, 0x00000458, 0x00001069, 0x00000001, 0x000011f8, 0x656c6170, 0x000011f9, 0x50657474, 0x000011fa, 0x00636f72, 0x00000000
