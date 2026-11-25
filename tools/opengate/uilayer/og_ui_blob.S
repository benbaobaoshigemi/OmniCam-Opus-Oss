.text
.global og_entry_capture
.global og_entry_scope
/* Replaces "bl native_parse_streams" at 1aa6c; x0=this, x1=config, x19=this. */
og_entry_capture:
    b og_capture_hook
/* Replaces "bl native_standardize" at 209f8; x19=this, x1=&mask. */
og_entry_scope:
    stp x29, x30, [sp, #-16]!
    mov x29, sp
    mov x0, x19
    bl og_scope_hook
    ldp x29, x30, [sp], #16
    ret
