// SPDX-License-Identifier: MIT
// Minimal HWSynth test - no delay, just read samples quickly
#include "hwsynth.h"
#include "mmio.h"
#include <stdint.h>

static void uart_putc(char c) { while (!(*UART_STATUS & 0x1)); *UART_SEND = c; }
static void print_str(const char *s) { while (*s) uart_putc(*s++); }

// Print unsigned integer without hardware division (uses repeated subtraction)
static void print_uint(uint32_t val) {
    static const uint32_t divisors[] = {
        1000000000, 100000000, 10000000, 1000000, 100000,
        10000, 1000, 100, 10, 1
    };
    int started = 0;
    if (val == 0) { uart_putc('0'); return; }
    for (int i = 0; i < 10; i++) {
        uint32_t d = divisors[i];
        int digit = 0;
        while (val >= d) { val -= d; digit++; }
        if (digit > 0 || started) { uart_putc('0' + digit); started = 1; }
    }
}

// Print signed integer
static void print_dec(int32_t val) {
    if (val < 0) {
        uart_putc('-');
        if (val == (int32_t)0x80000000) {
            print_str("2147483648");
            return;
        }
        val = -val;
    }
    print_uint((uint32_t)val);
}

// Print 8-digit hex
static void print_hex(uint32_t val) {
    static const char hex[] = "0123456789ABCDEF";
    for (int i = 7; i >= 0; i--) uart_putc(hex[(val >> (i * 4)) & 0xF]);
}

// Print 4-digit hex
static void print_hex4(uint16_t val) {
    static const char hex[] = "0123456789ABCDEF";
    for (int i = 3; i >= 0; i--) uart_putc(hex[(val >> (i * 4)) & 0xF]);
}

int main(void) {
    *UART_ENABLE = 1;
    print_str("\n=== HWSynth Minimal Test ===\n\n");

    // Check ID
    print_str("ID: 0x");
    print_hex(HWSYNTH_ID);
    print_str(" (expected 0x53594E54)\n");

    if (hwsynth_init() != 0) {
        print_str("FAIL: Init failed\n");
        return 1;
    }

    // Configure voice 0 for saw wave
    print_str("Configuring voice 0...\n");
    hwsynth_set_freq(0, 2000);
    hwsynth_set_envelope(0, 0xFF, 0, 0, 0);  // Instant attack
    hwsynth_set_sustain(0, 32000);
    hwsynth_set_filter(0, 32767, 0, 0);
    hwsynth_set_wave(0, WAVE_SAW);

    print_str("Enabling synth...\n");
    hwsynth_enable(0x01);  // Enable voice 0

    print_str("Gate on...\n");
    hwsynth_gate_on(0);

    // Read first 20 samples and print them
    print_str("\nFirst 20 samples (raw values):\n");
    for (int i = 0; i < 20; i++) {
        int16_t s = hwsynth_get_sample();
        print_str("  [");
        if (i < 10) uart_putc(' ');
        print_dec(i);
        print_str("] = ");
        print_dec((int32_t)s);
        print_str(" (0x");
        print_hex4((uint16_t)s);
        print_str(")\n");
    }

    // Read more samples and track min/max
    print_str("\nReading 1000 more samples...\n");
    int16_t mn = 32767, mx = -32768;
    for (int i = 0; i < 1000; i++) {
        int16_t s = hwsynth_get_sample();
        if (s < mn) mn = s;
        if (s > mx) mx = s;
    }

    print_str("Results:\n");
    print_str("  Min: ");
    print_dec((int32_t)mn);
    print_str("\n  Max: ");
    print_dec((int32_t)mx);
    print_str("\n  Range: ");
    print_dec((int32_t)mx - (int32_t)mn);
    print_str("\n");

    hwsynth_gate_off(0);
    hwsynth_disable();

    if (mx > 1000 && mn < -1000 && mx - mn > 10000) {
        print_str("\nPASS\n");
        return 0;
    } else {
        print_str("\nFAIL\n");
        return 1;
    }
}
