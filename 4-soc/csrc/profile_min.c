// SPDX-License-Identifier: MIT
#include "mmio.h"
#include <stdint.h>

/* ===== Minimal UART ===== */
static inline void uart_putc(unsigned char c)
{
    while (!(*UART_STATUS & 0x01));
    *UART_SEND = c;
}

static void uart_puts(const char *s)
{
    while (*s) uart_putc(*s++);
}

static void uart_put_hex(uint32_t v)
{
    for (int i = 28; i >= 0; i -= 4) {
        int d = (v >> i) & 0xF;
        uart_putc(d < 10 ? '0' + d : 'A' + d - 10);
    }
}

/* ===== rdcycle (RV32) ===== */
static inline uint32_t rdcycle(void)
{
    uint32_t c;
    asm volatile("rdcycle %0" : "=r"(c));
    return c;
}

int main(void)
{
    /* Enable UART */
    *UART_BAUDRATE = 115200;
    *UART_ENABLE  = 1;

    volatile uint32_t dummy = 0;

    uint32_t start = rdcycle();
    for (uint32_t i = 0; i < 100000; i++) {
        dummy += i;
    }
    uint32_t end = rdcycle();

    uart_puts("Cycle count = 0x");
    uart_put_hex(end - start);
    uart_puts("\n");

    /* 🔴 告訴 simulator：我結束了 */
    *TEST_DONE_FLAG = 0xCAFEF00D;

    while (1)
        asm volatile("wfi");
}
