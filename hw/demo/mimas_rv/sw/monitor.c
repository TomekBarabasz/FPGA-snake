// mimas_rv monitor: prosta konsola przez UART (115200 8N1).
//   help                 lista polecen
//   r ADDR [N]           odczyt N slow (hex)
//   w ADDR VAL           zapis slowa          wb ADDR VAL   zapis bajtu
//   led VAL / seg VAL    LED-y / wyswietlacz (seg 123 -> "123")
//   sw                   DIP-y i przyciski
//   st                   stan LPDDR (kalibracja, bledy)
//   mt [KB]              test LPDDR (domyslnie 64 KB), czas z mtime
// Liczby szesnastkowo, z "0x" lub bez.
#include "mimas_rv.h"

#define CPU_HZ 50000000u

static void putc_(char c) {
    while (!UART_TXCTRL) ;
    UART_TXDATA = (uint8_t)c;
    UART_TXCTRL = 1;
}
static void puts_(const char *s) { while (*s) { if (*s == '\n') putc_('\r'); putc_(*s++); } }
static int getc_(void) { while (UART_RXEMPTY) ; return (int)(UART_RXDATA & 0xff); }

static void puthex(uint32_t v, int digits) {
    for (int i = digits - 1; i >= 0; i--) putc_("0123456789abcdef"[(v >> (4 * i)) & 0xf]);
}
static void putdec(uint32_t v) {
    char b[11]; int n = 0;
    do { b[n++] = '0' + v % 10; v /= 10; } while (v);
    while (n) putc_(b[--n]);
}

static char line[80];
static void readline(void) {
    int n = 0;
    for (;;) {
        int c = getc_();
        if (c == '\r' || c == '\n') { puts_("\n"); break; }
        if ((c == 8 || c == 127) && n > 0) { n--; puts_("\b \b"); continue; }
        if (c >= ' ' && n < (int)sizeof line - 1) { line[n++] = (char)c; putc_((char)c); }
    }
    line[n] = 0;
}

// tokeny z linii
static char *argv_[4]; static int argc_;
static void split(void) {
    argc_ = 0;
    for (char *p = line; *p && argc_ < 4; ) {
        while (*p == ' ') *p++ = 0;
        if (!*p) break;
        argv_[argc_++] = p;
        while (*p && *p != ' ') p++;
    }
}
static int streq(const char *a, const char *b) { while (*a && *a == *b) a++, b++; return *a == *b; }
static int hexarg(int i, uint32_t *v) {
    if (i >= argc_) return 0;
    const char *s = argv_[i];
    if (s[0] == '0' && (s[1] == 'x' || s[1] == 'X')) s += 2;
    uint32_t x = 0; int n = 0;
    for (; *s; s++, n++) {
        int d = (*s >= '0' && *s <= '9') ? *s - '0' : (*s >= 'a' && *s <= 'f') ? *s - 'a' + 10
              : (*s >= 'A' && *s <= 'F') ? *s - 'A' + 10 : -1;
        if (d < 0) return 0;
        x = (x << 4) | (uint32_t)d;
    }
    *v = x;
    return n > 0;
}

static int ddr_ready(void) {
    if (BOARD_STATUS & 1) return 1;
    puts_("LPDDR nie jest skalibrowana (st)\n");
    return 0;
}
static int is_ddr(uint32_t a) { return a >= DDR_BASE && a < DDR_BASE + DDR_BYTES; }

static void memtest(uint32_t kb) {
    if (!ddr_ready()) return;
    if (kb == 0 || kb > (DDR_BYTES >> 10)) kb = DDR_BYTES >> 10;
    uint32_t words = kb * 256, err = 0;
    volatile uint32_t *m = (volatile uint32_t *)DDR_BASE;
    uint32_t t0 = MTIME_LO;
    for (uint32_t i = 0; i < words; i++) m[i] = i * 0x9E3779B1u ^ (DDR_BASE + 4 * i);
    uint32_t t1 = MTIME_LO;
    for (uint32_t i = 0; i < words; i++) {
        uint32_t want = i * 0x9E3779B1u ^ (DDR_BASE + 4 * i), got = m[i];
        if (got != want && err++ < 8) {
            puts_("  blad @"); puthex(DDR_BASE + 4 * i, 8);
            puts_(": jest "); puthex(got, 8); puts_(", ma byc "); puthex(want, 8); puts_("\n");
        }
    }
    uint32_t t2 = MTIME_LO;
    // bajty: zapis bajtowy w srodku slowa, odczyt slowa
    m[0] = 0x11223344u;
    ((volatile uint8_t *)DDR_BASE)[2] = 0xAB;
    if (m[0] != 0x11AB3344u) { err++; puts_("  blad zapisu bajtu: "); puthex(m[0], 8); puts_("\n"); }
    putdec(kb); puts_(" KB: ");
    if (err) { putdec(err); puts_(" BLEDOW"); } else puts_("OK");
    puts_(", zapis "); putdec((t1 - t0) / (CPU_HZ / 1000000u)); puts_(" us");
    puts_(", odczyt "); putdec((t2 - t1) / (CPU_HZ / 1000000u)); puts_(" us\n");
}

static void help(void) {
    puts_("r ADDR [N] | w ADDR VAL | wb ADDR VAL | led VAL | seg VAL | sw | st | mt [KB]\n"
          "RAM 80000000 (16 KB), LED 81000000, UART 82000000, BOARD 84000000, LPDDR 90000000 (64 MB)\n");
}

int main(void) {
    BOARD_SEG = 0x0AAA;
    LED = 1;
    puts_("\nmicrorv32 @ Mimas V2 - monitor (help)\n");
    for (;;) {
        puts_("rv> ");
        readline();
        split();
        if (!argc_) continue;
        const char *c = argv_[0];
        uint32_t a, v, n;
        if (streq(c, "help") || streq(c, "?")) help();
        else if (streq(c, "r") && hexarg(1, &a)) {
            if (!hexarg(2, &n) || n == 0) n = 1;
            if (is_ddr(a) && !ddr_ready()) continue;
            a &= ~3u;
            for (uint32_t i = 0; i < n; i++) {
                if (i % 4 == 0) { if (i) puts_("\n"); puthex(a + 4 * i, 8); puts_(":"); }
                putc_(' '); puthex(REG32(a + 4 * i), 8);
            }
            puts_("\n");
        }
        else if (streq(c, "w") && hexarg(1, &a) && hexarg(2, &v)) {
            if (is_ddr(a) && !ddr_ready()) continue;
            REG32(a & ~3u) = v;
        }
        else if (streq(c, "wb") && hexarg(1, &a) && hexarg(2, &v)) {
            if (is_ddr(a) && !ddr_ready()) continue;
            *(volatile uint8_t *)a = (uint8_t)v;
        }
        else if (streq(c, "led") && hexarg(1, &v)) LED = v;
        else if (streq(c, "seg") && hexarg(1, &v)) BOARD_SEG = v;
        else if (streq(c, "sw")) {
            puts_("DIP "); puthex(BOARD_DIP, 2); puts_("  BTN "); puthex(BOARD_BTN, 2); puts_("\n");
        }
        else if (streq(c, "st")) {
            uint32_t s = BOARD_STATUS;
            puts_("LPDDR: "); puts_(s & 1 ? "skalibrowana" : "brak kalibracji");
            puts_(s & 2 ? ", BLAD portu MCB\n" : "\n");
        }
        else if (streq(c, "mt")) { if (!hexarg(1, &n)) n = 0x40; memtest(n); }
        else puts_("? (help)\n");
    }
}
