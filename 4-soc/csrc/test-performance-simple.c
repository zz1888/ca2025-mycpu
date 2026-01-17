// SPDX-License-Identifier: MIT
// Simplified performance test - measure core operations only
#include <stdint.h>
#include <stdbool.h>
#include <stddef.h>
#include "picosynth.h"
#include "mmio.h"

/* Minimal allocator for bare-metal tests (avoids libc dependency). */
#define PERF_HEAP_SIZE (32 * 1024u)
static uint8_t g_perf_heap[PERF_HEAP_SIZE];
static size_t g_perf_heap_off;

void *malloc(size_t n) {
    n = (n + 7u) & ~7u;
    if (g_perf_heap_off + n > PERF_HEAP_SIZE)
        return (void *)0;
    void *p = &g_perf_heap[g_perf_heap_off];
    g_perf_heap_off += n;
    return p;
}

void free(void *p) { (void)p; }

static void *perf_memset(void *dest, int c, unsigned int n) {
    unsigned char *d = (unsigned char *)dest;
    while (n--)
        *d++ = (unsigned char)c;
    return dest;
}

void *calloc(size_t nmemb, size_t size) {
    size_t n = nmemb * size;
    void *p = malloc(n);
    if (p)
        perf_memset(p, 0, n);
    return p;
}

/* UART functions */
void uart_putc(char c) {
    while (!(*UART_STATUS & 0x1));
    *UART_SEND = c;
}

void print_str(const char* s) {
    while (*s) {
        uart_putc(*s++);
    }
}

void print_hex(uint32_t val) {
    const char hex[] = "0123456789ABCDEF";
    print_str("0x");
    for (int i = 7; i >= 0; i--) {
        uart_putc(hex[(val >> (i * 4)) & 0xF]);
    }
}

void print_uint(uint32_t val) {
    static const uint32_t divisors[] = {
        1000000000, 100000000, 10000000, 1000000, 100000,
        10000,      1000,      100,      10,      1};
    int started = 0;

    if (val == 0) {
        uart_putc('0');
        return;
    }

    for (int i = 0; i < 10; i++) {
        uint32_t d = divisors[i];
        int digit = 0;

        while (val >= d) {
            val -= d;
            digit++;
        }

        if (digit > 0 || started) {
            uart_putc('0' + digit);
            started = 1;
        }
    }
}

/* Read CPU cycle counter (32-bit only for simplicity) */
static inline uint32_t read_mcycle(void) {
    uint32_t c;
    __asm__ volatile("csrr %0, mcycle" : "=r"(c));
    return c;
}

static inline void audio_write_sample(int16_t sample) {
    while (AUDIO_STATUS & 0x2) {
        /* Wait while FIFO full */
    }
    AUDIO_DATA = (uint32_t)sample;
}

static uint32_t cycle_diff(uint32_t start, uint32_t end) {
    if (end >= start) {
        return end - start;
    }
    return (0xFFFFFFFF - start) + end + 1;
}

/* Local Q15 helpers for profiling (picosynth.c versions are static). */
static inline q15_t q15_mul(q15_t a, q15_t b) {
    uint32_t r;
    asm volatile(".insn r 0x0B, 0x0, 0x00, %0, %1, %2"
                 : "=r"(r)
                 : "r"(a), "r"(b));
    return (q15_t)(r & 0xFFFF);
}

static q15_t pow_q15(q15_t base, uint32_t exp) {
    int32_t result = Q15_MAX; /* 1.0 */
    int32_t b = base;
    while (exp) {
        if (exp & 1u)
            result = (int32_t)q15_mul((q15_t)result, (q15_t)b);
        exp >>= 1;
        if (exp)
            b = (int32_t)q15_mul((q15_t)b, (q15_t)b);
    }
    return (q15_t)result;
}

/* Fast envelope init for profiling: avoids expensive exp coefficient search. */
static void perf_init_env_fast(picosynth_node_t *n) {
    /* Nodes are zeroed by calloc in picosynth_create(); avoid extra memset. */
    n->gain = NULL;
    n->type = PICOSYNTH_NODE_ENV;
    n->env.attack = 0x2000;
    n->env.hold = 0;
    n->env.decay = 0x2000;
    n->env.sustain = (q15_t)(Q15_MAX * 80 / 100);
    n->env.release = 0x2000;
    n->env.decay_coeff = 0x6000;   /* ~0.75 */
    n->env.release_coeff = 0x6000; /* ~0.75 */
    n->env.block_counter = 0;
    n->env.block_rate = 0;
    n->env.hold_counter = 0;
}

static void perf_init_osc_fast(picosynth_node_t *n, const q15_t *gain,
                               const q15_t *freq, picosynth_wave_func_t wave) {
    n->gain = gain;
    n->type = PICOSYNTH_NODE_OSC;
    n->osc.freq = freq;
    n->osc.detune = NULL;
    n->osc.wave = wave;
}

static void perf_init_lp_fast(picosynth_node_t *n, const q15_t *gain,
                              const q15_t *in, q15_t coeff) {
    n->gain = gain;
    n->type = PICOSYNTH_NODE_LP;
    n->flt.in = in;
    n->flt.accum = 0;
    n->flt.coeff = coeff;
    n->flt.coeff_target = coeff;
}

int main() {
    *UART_ENABLE = 1;
    print_str("\n=== Simple Performance Test ===\n");
    print_str("Testing core DSP operations\n\n");

    // Test 1: q15_mul performance
    print_str("Test 1: q15_mul() performance\n");
    {
        q15_t a = 0x4000;  // 0.5
        q15_t b = 0x4000;  // 0.5
        
        // Read cycle counter multiple times to get stable reading
        uint32_t start1 = read_mcycle();
        uint32_t start2 = read_mcycle();
        uint32_t start = (start2 > start1) ? start2 : start1;
        
        // Perform operations
        volatile q15_t dummy_result = 0;
        for (int i = 0; i < 1000; i++) {
            dummy_result = q15_mul(a, b);
        }
        
        // Read cycle counter again
        uint32_t end1 = read_mcycle();
        uint32_t end2 = read_mcycle();
        uint32_t end = (end2 > end1) ? end2 : end1;
        
        // Handle wraparound (if end < start, counter wrapped)
        uint32_t cycles;
        if (end >= start) {
            cycles = end - start;
        } else {
            // Counter wrapped around
            cycles = (0xFFFFFFFF - start) + end + 1;
        }
        
        uint32_t cycles_per_op = cycles / 1000;
        
        print_str("  1000 operations: ");
        print_uint(cycles);
        print_str(" cycles\n");
        print_str("  Cycles per q15_mul: ");
        print_uint(cycles_per_op);
        print_str("\n");
        print_str("  (Expected: ~1-3 cycles with QMUL16)\n");
    }

    // Test 2: pow_q15 performance
    print_str("\nTest 2: pow_q15() performance\n");
    {
        q15_t base = 0x4000;  // 0.5
        
        uint32_t start1 = read_mcycle();
        uint32_t start2 = read_mcycle();
        uint32_t start = (start2 > start1) ? start2 : start1;
        
        volatile q15_t dummy_result = 0;
        for (int i = 0; i < 100; i++) {
            dummy_result = pow_q15(base, 100);
        }
        
        uint32_t end1 = read_mcycle();
        uint32_t end2 = read_mcycle();
        uint32_t end = (end2 > end1) ? end2 : end1;
        
        uint32_t cycles;
        if (end >= start) {
            cycles = end - start;
        } else {
            cycles = (0xFFFFFFFF - start) + end + 1;
        }
        
        uint32_t cycles_per_op = cycles / 100;
        
        print_str("  100 operations: ");
        print_uint(cycles);
        print_str(" cycles\n");
        print_str("  Cycles per pow_q15: ");
        print_uint(cycles_per_op);
        print_str("\n");
    }

    // Test 3: Simple synth creation and one sample
    print_str("\nTest 3: Single sample processing\n");
    {
        print_str("  Creating synth (1 voice, 3 nodes)...\n");
        picosynth_t *s = picosynth_create(1, 3);
        if (!s) {
            print_str("  ERROR: Failed to create synth\n");
            return 1;
        }
        print_str("  Synth created\n");

        // Setup voice
        print_str("  Getting voice...\n");
        picosynth_voice_t *v = picosynth_get_voice(s, 0);
        if (!v) {
            print_str("  ERROR: Failed to get voice\n");
            picosynth_destroy(s);
            return 1;
        }
        print_str("  Voice OK\n");
        
        print_str("  Getting nodes...\n");
        picosynth_node_t *env = picosynth_voice_get_node(v, 0);
        picosynth_node_t *osc = picosynth_voice_get_node(v, 1);
        picosynth_node_t *lp = picosynth_voice_get_node(v, 2);
        
        if (!env || !osc || !lp) {
            print_str("  ERROR: Failed to get nodes\n");
            picosynth_destroy(s);
            return 1;
        }
        print_str("  Nodes OK\n");
        
        print_str("  Initializing envelope...\n");
        uint32_t env_start = read_mcycle();
        perf_init_env_fast(env);
        uint32_t env_end = read_mcycle();
        print_str("  Envelope OK (cycles: ");
        print_uint(cycle_diff(env_start, env_end));
        print_str(")\n");
        
        print_str("  Initializing oscillator...\n");
        uint32_t osc_start = read_mcycle();
        perf_init_osc_fast(osc, &env->out, picosynth_voice_freq_ptr(v), picosynth_wave_sine);
        uint32_t osc_end = read_mcycle();
        print_str("  Oscillator OK (cycles: ");
        print_uint(cycle_diff(osc_start, osc_end));
        print_str(")\n");
        
        print_str("  Initializing filter...\n");
        uint32_t lp_start = read_mcycle();
        perf_init_lp_fast(lp, NULL, &osc->out, 0x4000);
        uint32_t lp_end = read_mcycle();
        print_str("  Filter OK (cycles: ");
        print_uint(cycle_diff(lp_start, lp_end));
        print_str(")\n");
        
        print_str("  Setting output...\n");
        uint32_t out_start = read_mcycle();
        picosynth_voice_set_out(v, 2);
        uint32_t out_end = read_mcycle();
        print_str("  Output OK (cycles: ");
        print_uint(cycle_diff(out_start, out_end));
        print_str(")\n");
        
        print_str("  Triggering note...\n");
        uint32_t note_start = read_mcycle();
        picosynth_note_on(s, 0, 60);
        uint32_t note_end = read_mcycle();
        print_str("  Note OK (cycles: ");
        print_uint(cycle_diff(note_start, note_end));
        print_str(")\n");
        
        // Debug: check voice_enable_mask after note_on
        uint16_t *p_mask = (uint16_t *)(((uint8_t *)s) + 6);
        uint16_t mask = *p_mask;
        print_str("  voice_enable_mask after note_on: 0x");
        print_hex(mask);
        print_str("\n");
        
    print_str("  Processing 1 sample (test)...\n");
    uint32_t start1 = read_mcycle();
    uint32_t start2 = read_mcycle();
    uint32_t start = (start2 > start1) ? start2 : start1;
    
    print_str("  About to call picosynth_process...\n");
    q15_t sample = picosynth_process(s);
    (void)sample;
    print_str("  Process returned\n");
        
        print_str("  Node outputs after 1 sample:\n");
        print_str("    env->out = ");
        print_hex((uint32_t)(uint16_t)env->out);
        print_str("\n");
        print_str("    osc->out = ");
        print_hex((uint32_t)(uint16_t)osc->out);
        print_str("\n");
        print_str("    lp->out  = ");
        print_hex((uint32_t)(uint16_t)lp->out);
        print_str("\n");
        
    uint32_t end1 = read_mcycle();
    uint32_t end2 = read_mcycle();
    uint32_t end = (end2 > end1) ? end2 : end1;
    
    uint32_t cycles;
    if (end >= start) {
        cycles = end - start;
    } else {
        cycles = (0xFFFFFFFF - start) + end + 1;
    }
    
    print_str("  Total: ");
    print_uint(cycles);
    print_str(" cycles for 1 sample\n");
        
        print_str("\n  Writing 1024 samples to audio FIFO...\n");
        for (int i = 0; i < 1024; i++) {
            q15_t sample = picosynth_process(s);
            audio_write_sample(sample);
        }
        print_str("  Audio samples written (output.wav on exit)\n");
        
        picosynth_destroy(s);
    }

    print_str("\n=== Test Complete ===\n");
    return 0;
}
