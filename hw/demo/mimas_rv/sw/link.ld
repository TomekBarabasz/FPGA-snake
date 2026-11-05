/* mimas_rv: program w BRAM od 0x80000000 (rozmiar = MimasRvConfig.ramBytes), stos na koncu. */
OUTPUT_ARCH("riscv")
ENTRY(_start)
MEMORY { RAM (rwx) : ORIGIN = 0x80000000, LENGTH = 16K }
SECTIONS {
  .text   : { *(.text.init) *(.text*) } > RAM
  .rodata : { *(.rodata*) *(.srodata*) } > RAM
  .data   : { *(.data*) *(.sdata*) } > RAM
  .bss    : { _bss_start = .; *(.bss*) *(.sbss*) *(COMMON) _bss_end = .; } > RAM
  _stack_top = ORIGIN(RAM) + LENGTH(RAM);
}
