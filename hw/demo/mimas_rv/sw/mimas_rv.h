// mimas_rv: mapa pamieci SoC (MimasRvSoc w hw/spinal/main/MimasRv.scala)
#ifndef MIMAS_RV_H
#define MIMAS_RV_H
#include <stdint.h>

#define REG32(a)      (*(volatile uint32_t *)(a))

#define RAM_BASE      0x80000000u            // BRAM z programem (16 KB)
#define LED           REG32(0x81000000u)     // D1..D8, bit 0 = D1
#define UART_TXDATA   REG32(0x82000000u)
#define UART_TXCTRL   REG32(0x82000004u)     // zapis 1 = wyslij; odczyt 1 = gotowy
#define UART_RXDATA   REG32(0x82000008u)     // odczyt zdejmuje bajt z FIFO
#define UART_RXEMPTY  REG32(0x82000014u)
#define MTIME_LO      REG32(0x0200bff8u)     // licznik taktow zegara CPU
#define MTIME_HI      REG32(0x0200bffcu)
#define BOARD_DIP     REG32(0x84000000u)     // DP1..DP8, 1 = ON
#define BOARD_BTN     REG32(0x84000004u)     // SW1..SW6, 1 = wcisniety
#define BOARD_SEG     REG32(0x84000008u)     // 7-seg: 3 cyfry (11..8 lewa) + kropki (14..12)
#define BOARD_STATUS  REG32(0x8400000Cu)     // bit 0 = LPDDR skalibrowana, bit 1 = blad portu MCB
#define DDR_BASE      0x90000000u            // LPDDR 64 MB
#define DDR_BYTES     (64u << 20)

#endif
