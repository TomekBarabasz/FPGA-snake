# mimas_rv: monitor dla microrv32 (rv32im). Narzedzia: riscv64-unknown-elf-gcc
# (Ubuntu: apt install gcc-riscv64-unknown-elf; Windows: xPack RISC-V GCC) + python3.
# Wynik: monitor.hex (16 KB = 4096 slow) - wpisywany do BRAM przy generacji Verilogu.
CROSS  ?= riscv64-unknown-elf-
CC      = $(CROSS)gcc
OBJCOPY = $(CROSS)objcopy
WORDS   = 4096
CFLAGS  = -march=rv32im_zicsr -mabi=ilp32 -Os -ffreestanding -nostdlib -nostartfiles \
          -Wall -Wextra -T link.ld -Wl,--gc-sections -Wl,--print-memory-usage

all: monitor.hex

monitor.elf: crt0.S monitor.c mimas_rv.h link.ld
	$(CC) $(CFLAGS) crt0.S monitor.c -o $@ -lgcc

monitor.bin: monitor.elf
	$(OBJCOPY) -O binary $< $@

monitor.hex: monitor.bin bin2hex.py
	python3 bin2hex.py $< $@ $(WORDS)

dump: monitor.elf
	$(CROSS)objdump -d $< | less

clean:
	rm -f monitor.elf monitor.bin

.PHONY: all dump clean
