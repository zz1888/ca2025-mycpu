/**
 * PicoSynth - A lightweight software synthesizer.
 *
 * Modular design: voices contain interconnected nodes (oscillators,
 * envelopes, filters). Nodes wire together via 'q15_t *' pointers.
 *
 * Example:
 *   picosynth_t *s = picosynth_create(2, 8);
 *   picosynth_voice_t *v = picosynth_get_voice(s, 0);
 *   picosynth_node_t *env = picosynth_voice_get_node(v, 0);
 *   picosynth_node_t *osc = picosynth_voice_get_node(v, 1);
 *   picosynth_node_t *flt = picosynth_voice_get_node(v, 2);
 *
 *   picosynth_init_env_ms(env, NULL,
 *       &(picosynth_env_ms_params_t){.atk_ms=10, .dec_ms=100,
 *                                    .sus_pct=80, .rel_ms=50,
 *                                   });
 *   picosynth_init_osc(osc, &env->out, picosynth_voice_freq_ptr(v),
 *                      picosynth_wave_sine);
 *   picosynth_init_lp(flt, NULL, &osc->out, 5000);
 *   picosynth_voice_set_out(v, 2);
 *
 *   picosynth_note_on(s, 0, 60);
 *   q15_t sample = picosynth_process(s);
 *   picosynth_destroy(s);
 */

#ifndef PICOSYNTH_H_
#define PICOSYNTH_H_

#include <stdbool.h>
#include <stdint.h>

#ifndef SAMPLE_RATE
#define SAMPLE_RATE 11025
#endif

/* Block size for envelope processing optimization.
 * Rate computed once per block, transitions checked per-sample.
 * Maximum 255 (uint8_t counter). Typical values: 16, 32, 64.
 */
#ifndef PICOSYNTH_BLOCK_SIZE
#define PICOSYNTH_BLOCK_SIZE 32
#endif

#if PICOSYNTH_BLOCK_SIZE > 255
#error "PICOSYNTH_BLOCK_SIZE must be <= 255 (uint8_t block_counter)"
#endif

/* Maximum nodes per voice. Fixed-size scratch array avoids VLAs
 * (banned from Linux kernel due to stack overflow risk).
 * Default 32 is sufficient for complex patches; increase if needed.
 * picosynth_create() returns NULL if nodes exceeds this limit.
 */
#ifndef PICOSYNTH_MAX_NODES
#define PICOSYNTH_MAX_NODES 32
#endif

#if PICOSYNTH_MAX_NODES > 255
#error "PICOSYNTH_MAX_NODES must be <= 255 (uint8_t n_nodes)"
#endif

/**
 * Q15 fixed-point: signed 16-bit, 15 fractional bits.
 * Range: [-1.0, +1.0) as [-32768, +32767].
 * Multiply: (q15_t)(((int32_t)a * b) >> 15)
 */
typedef int16_t q15_t;
typedef int8_t q7_t;

#define Q15_MAX 0x7FFF /* +0.99997 */
#define Q15_MIN 0x8000 /* -1.0 */

/* Saturating cast from int32_t to q15_t */
static inline q15_t q15_sat(int32_t x)
{
    if (x > Q15_MAX)
        return Q15_MAX;
    if (x < -32768)
        return Q15_MIN;
    return (q15_t) x;
}

/*============================================================================
 * DSP Hardware Acceleration Functions
 *
 * Custom RISC-V instructions using opcode 0x0B (custom-0).
 * Encoding: .insn r 0x0B, funct3, funct7, rd, rs1, rs2
 *
 * funct3 | Instruction | Description
 * -------|-------------|------------------------------------------
 *   0    | QMUL16      | Q15 16x16 multiply: (a * b) >> 15
 *   1    | SADD16      | 16-bit saturating add
 *   2    | SSUB16      | 16-bit saturating subtract
 *   3    | SADD32      | 32-bit saturating add
 *   4    | SSUB32      | 32-bit saturating subtract
 *   5    | QMUL16R     | Q15 16x16 multiply with rounding
 *   6    | SSHL16      | 16-bit saturating shift left
 *   7    | QMUL32x16   | Q15 32x16 multiply: (a * b[15:0]) >> 15
 *============================================================================*/

/* Q15 16x16 multiply: (a * b) >> 15
 * Input: two Q15 values [-1.0, +1.0)
 * Output: Q15 product
 */
static inline q15_t q15_mul(q15_t a, q15_t b)
{
    uint32_t result;
    asm volatile(".insn r 0x0B, 0x0, 0x00, %0, %1, %2"
                 : "=r"(result)
                 : "r"(a), "r"(b));
    return (q15_t) (result & 0xFFFF);
}

/* 16-bit saturating add: clamps to [-32768, 32767] */
static inline q15_t q15_add_sat(q15_t a, q15_t b)
{
    uint32_t result;
    asm volatile(".insn r 0x0B, 0x1, 0x00, %0, %1, %2"
                 : "=r"(result)
                 : "r"(a), "r"(b));
    return (q15_t) (result & 0xFFFF);
}

/* 16-bit saturating subtract: clamps to [-32768, 32767] */
static inline q15_t q15_sub_sat(q15_t a, q15_t b)
{
    uint32_t result;
    asm volatile(".insn r 0x0B, 0x2, 0x00, %0, %1, %2"
                 : "=r"(result)
                 : "r"(a), "r"(b));
    return (q15_t) (result & 0xFFFF);
}

/* 32-bit saturating add: clamps to [INT32_MIN, INT32_MAX] */
static inline int32_t i32_add_sat(int32_t a, int32_t b)
{
    uint32_t result;
    asm volatile(".insn r 0x0B, 0x3, 0x00, %0, %1, %2"
                 : "=r"(result)
                 : "r"(a), "r"(b));
    return (int32_t) result;
}

/* 32-bit saturating subtract: clamps to [INT32_MIN, INT32_MAX] */
static inline int32_t i32_sub_sat(int32_t a, int32_t b)
{
    uint32_t result;
    asm volatile(".insn r 0x0B, 0x4, 0x00, %0, %1, %2"
                 : "=r"(result)
                 : "r"(a), "r"(b));
    return (int32_t) result;
}

/* Q15 16x16 multiply with rounding: (a * b + 0x4000) >> 15
 * Provides better precision for cascaded multiplications.
 */
static inline q15_t q15_mul_r(q15_t a, q15_t b)
{
    uint32_t result;
    asm volatile(".insn r 0x0B, 0x5, 0x00, %0, %1, %2"
                 : "=r"(result)
                 : "r"(a), "r"(b));
    return (q15_t) (result & 0xFFFF);
}

/* 16-bit saturating shift left: (a << shamt) clamped to [-32768, 32767]
 * shamt is taken from lower 5 bits of b.
 */
static inline q15_t q15_shl_sat(q15_t a, uint8_t shamt)
{
    uint32_t result;
    asm volatile(".insn r 0x0B, 0x6, 0x00, %0, %1, %2"
                 : "=r"(result)
                 : "r"(a), "r"(shamt));
    return (q15_t) (result & 0xFFFF);
}

/* Q15 32x16 multiply: (a * b) >> 15
 * Input: 32-bit accumulator, 16-bit Q15 coefficient
 * Output: 32-bit result
 * Used for filter/envelope: ((int32_t) state * (int16_t) coeff) >> 15
 */
static inline int32_t qmul32x16(int32_t a, q15_t b)
{
    uint32_t result;
    asm volatile(".insn r 0x0B, 0x7, 0x00, %0, %1, %2"
                 : "=r"(result)
                 : "r"(a), "r"(b));
    return (int32_t) result;
}

/*============================================================================
 * RV32M Integer Multiply/Divide Hardware Acceleration
 *
 * Standard RISC-V M extension instructions for integer arithmetic.
 * These use the hardware multiplier/divider instead of software emulation.
 *============================================================================*/

/* 32-bit signed multiplication (lower 32 bits of 64-bit result)
 * Uses RV32M MUL instruction
 */
static inline int32_t i32_mul(int32_t a, int32_t b)
{
    int32_t result;
    asm volatile("mul %0, %1, %2" : "=r"(result) : "r"(a), "r"(b));
    return result;
}

/* 32-bit unsigned multiplication (lower 32 bits of 64-bit result)
 * Uses RV32M MUL instruction
 */
static inline uint32_t u32_mul(uint32_t a, uint32_t b)
{
    uint32_t result;
    asm volatile("mul %0, %1, %2" : "=r"(result) : "r"(a), "r"(b));
    return result;
}

/* 32-bit signed multiplication, high 32 bits of 64-bit result
 * Uses RV32M MULH instruction
 */
static inline int32_t i32_mulh(int32_t a, int32_t b)
{
    int32_t result;
    asm volatile("mulh %0, %1, %2" : "=r"(result) : "r"(a), "r"(b));
    return result;
}

/* 32-bit unsigned multiplication, high 32 bits of 64-bit result
 * Uses RV32M MULHU instruction
 */
static inline uint32_t u32_mulhu(uint32_t a, uint32_t b)
{
    uint32_t result;
    asm volatile("mulhu %0, %1, %2" : "=r"(result) : "r"(a), "r"(b));
    return result;
}

/* 32-bit signed*unsigned multiplication, high 32 bits of 64-bit result
 * Uses RV32M MULHSU instruction
 * Computes: (int64_t)a * (uint64_t)(uint32_t)b >> 32
 */
static inline int32_t i32_mulhsu(int32_t a, uint32_t b)
{
    int32_t result;
    asm volatile("mulhsu %0, %1, %2" : "=r"(result) : "r"(a), "r"(b));
    return result;
}

/* 32-bit signed division
 * Uses RV32M DIV instruction
 * Note: Division by zero returns -1, overflow (INT32_MIN/-1) returns INT32_MIN
 */
static inline int32_t i32_div(int32_t a, int32_t b)
{
    int32_t result;
    asm volatile("div %0, %1, %2" : "=r"(result) : "r"(a), "r"(b));
    return result;
}

/* 32-bit unsigned division
 * Uses RV32M DIVU instruction
 * Note: Division by zero returns 0xFFFFFFFF
 */
static inline uint32_t u32_div(uint32_t a, uint32_t b)
{
    uint32_t result;
    asm volatile("divu %0, %1, %2" : "=r"(result) : "r"(a), "r"(b));
    return result;
}

/* 32-bit signed remainder
 * Uses RV32M REM instruction
 * Note: Remainder by zero returns dividend, overflow returns 0
 */
static inline int32_t i32_rem(int32_t a, int32_t b)
{
    int32_t result;
    asm volatile("rem %0, %1, %2" : "=r"(result) : "r"(a), "r"(b));
    return result;
}

/* 32-bit unsigned remainder
 * Uses RV32M REMU instruction
 * Note: Remainder by zero returns dividend
 */
static inline uint32_t u32_rem(uint32_t a, uint32_t b)
{
    uint32_t result;
    asm volatile("remu %0, %1, %2" : "=r"(result) : "r"(a), "r"(b));
    return result;
}

/* 64-bit unsigned division using 32-bit hardware divider
 * Computes (a_hi:a_lo) / b where b is 32-bit
 * Returns 64-bit quotient
 * Used for envelope rate calculations: ((int64_t)Q15_MAX << 4) / samples
 */
static inline uint64_t u64_div_u32(uint64_t a, uint32_t b)
{
    if (b == 0)
        return 0xFFFFFFFFFFFFFFFFULL; /* Division by zero */

    uint32_t a_hi = (uint32_t) (a >> 32);
    uint32_t a_lo = (uint32_t) a;

    /* Two-step division for 64-bit / 32-bit:
     * 1. Divide high part: q_hi = a_hi / b, r_hi = a_hi % b
     * 2. Combine remainder with low part and divide using shift-subtract
     */
    uint32_t q_hi, r_hi, q_lo;

    asm volatile("divu %0, %1, %2" : "=r"(q_hi) : "r"(a_hi), "r"(b));
    asm volatile("remu %0, %1, %2" : "=r"(r_hi) : "r"(a_hi), "r"(b));

    /* Binary long division for (r_hi:a_lo) / b */
    uint64_t remainder = ((uint64_t) r_hi << 32) | a_lo;
    q_lo = 0;

    for (int i = 31; i >= 0; i--) {
        if (remainder >= ((uint64_t) b << i)) {
            remainder -= ((uint64_t) b << i);
            q_lo |= (1u << i);
        }
    }

    return ((uint64_t) q_hi << 32) | q_lo;
}

/* 64-bit signed division using 32-bit hardware divider
 * Handles sign conversion and calls u64_div_u32
 */
static inline int64_t i64_div_i32(int64_t a, int32_t b)
{
    if (b == 0)
        return -1; /* Division by zero */

    int neg = 0;
    uint64_t ua;
    uint32_t ub;

    if (a < 0) {
        neg = 1;
        ua = (uint64_t) (-a);
    } else {
        ua = (uint64_t) a;
    }

    if (b < 0) {
        neg ^= 1;
        ub = (uint32_t) (-b);
    } else {
        ub = (uint32_t) b;
    }

    uint64_t result = u64_div_u32(ua, ub);
    return neg ? -(int64_t) result : (int64_t) result;
}

/* Waveform generator function pointer */
typedef q15_t (*picosynth_wave_func_t)(q15_t phase);

/* AHDSR envelope parameters for initialization.
 * Using a struct avoids long parameter lists and enables named initialization.
 */
typedef struct {
    int32_t attack;  /* Attack rate (higher = faster attack) */
    int32_t hold;    /* Hold duration in samples (0 = no hold) */
    int32_t decay;   /* Decay rate (higher = faster decay) */
    q15_t sustain;   /* Sustain level (negative inverts output) */
    int32_t release; /* Release rate (higher = faster release) */
} picosynth_env_params_t;

/* Oscillator state */
typedef struct {
    const q15_t *freq;          /* Phase increment (frequency control) */
    const q15_t *detune;        /* Optional FM/detune offset */
    picosynth_wave_func_t wave; /* Waveform generator (phase -> sample) */
} picosynth_osc_t;

/* AHDSR envelope state (Attack-Hold-Decay-Sustain-Release).
 * Rates are step values scaled <<4 internally. Use synth_init_env_ms().
 */
typedef struct {
    int32_t attack;      /* Ramp-up rate */
    int32_t hold;        /* Hold duration in samples (at peak before decay) */
    int32_t decay;       /* Ramp-down rate to sustain */
    q15_t sustain;       /* Hold level (negative inverts output) */
    int32_t release;     /* Ramp-down rate after note-off */
    q15_t decay_coeff;   /* Exponential multiplier for decay */
    q15_t release_coeff; /* Exponential multiplier for release */
    /* Block processing state (computed at block boundaries) */
    int32_t block_rate;    /* Current per-sample rate */
    uint8_t block_counter; /* Samples until next rate computation */
    int32_t hold_counter;  /* Remaining hold samples (runtime state) */
} picosynth_env_t;

/* Single-pole filter state */
typedef struct {
    const q15_t *in;    /* Input signal pointer */
    int32_t accum;      /* Internal accumulator (Q31) */
    q15_t coeff;        /* Smoothed cutoff: 0=DC, Q15_MAX=bypass */
    q15_t coeff_target; /* Target cutoff for smoothing */
} picosynth_filter_t;

/* Two-pole State Variable Filter (SVF) state.
 * Provides -12dB/octave rolloff with resonance control.
 * Topology: hp = in - lp - q*bp; lp += f*bp; bp += f*hp
 */
typedef struct {
    const q15_t *in; /* Input signal pointer */
    int32_t lp;      /* Low-pass state (Q15 scaled <<8 for precision) */
    int32_t bp;      /* Band-pass state (Q15 scaled <<8) */
    q15_t f;         /* Frequency coefficient: 2*sin(pi*fc/fs) in Q15 */
    q15_t f_target;  /* Target frequency for smoothing */
    q15_t q; /* Damping factor. Higher means more damping (less resonance).
              * Q15_MAX = maximum damping (no resonance), 0 = self-oscillation.
              * Note: This is NOT 1/Q since 1/Q can exceed Q15 range.
              */
} picosynth_svf_t;

/* 3-input mixer state */
typedef struct {
    const q15_t *in[3]; /* Input signal pointers (NULL = unused) */
} picosynth_mixer_t;

/* Node types */
typedef enum {
    PICOSYNTH_NODE_NONE = 0,
    PICOSYNTH_NODE_OSC,
    PICOSYNTH_NODE_ENV,
    PICOSYNTH_NODE_LP,
    PICOSYNTH_NODE_HP,
    PICOSYNTH_NODE_MIX,
    PICOSYNTH_NODE_SVF_LP, /* 2-pole SVF low-pass (-12dB/oct) */
    PICOSYNTH_NODE_SVF_HP, /* 2-pole SVF high-pass */
    PICOSYNTH_NODE_SVF_BP, /* 2-pole SVF band-pass */
} picosynth_node_type_t;

/* Audio processing node */
typedef struct {
    int32_t state;     /* Internal state (phase, level, etc.) */
    const q15_t *gain; /* Amplitude modulation input */
    q15_t out;         /* Output signal */
    picosynth_node_type_t type;
    union {
        picosynth_osc_t osc;
        picosynth_env_t env;
        picosynth_filter_t flt;
        picosynth_svf_t svf;
        picosynth_mixer_t mix;
    };
} picosynth_node_t;

/* Opaque types (definitions in picosynth.c) */
typedef struct picosynth_voice picosynth_voice_t;
typedef struct picosynth picosynth_t;

/* Create synthesizer. Returns NULL on failure. Caller must synth_destroy(). */
picosynth_t *picosynth_create(uint8_t voices, uint8_t nodes);

/* Free synthesizer and all resources */
void picosynth_destroy(picosynth_t *s);

/* Get voice by index (NULL if out of bounds) */
picosynth_voice_t *picosynth_get_voice(picosynth_t *s, uint8_t idx);

/* Get node by index within voice (NULL if out of bounds) */
picosynth_node_t *picosynth_voice_get_node(picosynth_voice_t *v, uint8_t idx);

/* Set which node provides voice output (also recomputes usage mask) */
void picosynth_voice_set_out(picosynth_voice_t *v, uint8_t idx);

/* Get pointer to voice's frequency (for wiring to oscillator) */
const q15_t *picosynth_voice_freq_ptr(picosynth_voice_t *v);

/* Trigger note (sets frequency, resets envelopes) */
void picosynth_note_on(picosynth_t *s, uint8_t voice, uint8_t note);

/* Release note (starts envelope release phase) */
void picosynth_note_off(picosynth_t *s, uint8_t voice);

/* Convert MIDI note (0-127) to phase increment */
q15_t picosynth_midi_to_freq(uint8_t note);

/* Debug helper: return raw octave8_freq entry (index 0-11). */
q15_t picosynth_debug_octave8_freq(uint8_t idx);
const q15_t *picosynth_debug_octave8_ptr(void);

/* Initialize oscillator node. Set n->osc.detune after init if needed. */
void picosynth_init_osc(picosynth_node_t *n,
                        const q15_t *gain,
                        const q15_t *freq,
                        picosynth_wave_func_t wave);

/* Initialize AHDSR envelope node with parameter struct.
 * @params: Pointer to envelope parameters (attack, hold, decay, sustain,
 * release). Rates are increments per sample, scaled <<4 internally.
 */
void picosynth_init_env(picosynth_node_t *n,
                        const q15_t *gain,
                        const picosynth_env_params_t *params);

/* Millisecond-based envelope parameters for picosynth_init_env_ms(). */
typedef struct {
    uint16_t atk_ms;  /* Attack time in milliseconds */
    uint16_t hold_ms; /* Hold time in milliseconds (0 = no hold) */
    uint16_t dec_ms;  /* Decay time in milliseconds */
    uint8_t sus_pct;  /* Sustain level as percentage (0-100) */
    uint16_t rel_ms;  /* Release time in milliseconds */
} picosynth_env_ms_params_t;

/* Initialize envelope with millisecond timings and percentage sustain.
 * Converts timing parameters to internal rate values.
 */
void picosynth_init_env_ms(picosynth_node_t *n,
                           const q15_t *gain,
                           const picosynth_env_ms_params_t *params);

/* Initialize low-pass filter node */
void picosynth_init_lp(picosynth_node_t *n,
                       const q15_t *gain,
                       const q15_t *in,
                       q15_t coeff);

/* Initialize high-pass filter node */
void picosynth_init_hp(picosynth_node_t *n,
                       const q15_t *gain,
                       const q15_t *in,
                       q15_t coeff);

/* Set filter cutoff with smoothing */
void picosynth_filter_set_coeff(picosynth_node_t *n, q15_t coeff);

/* Initialize 2-pole SVF low-pass filter (-12dB/octave).
 * @f_coeff : frequency coefficient (use picosynth_svf_freq() to calculate)
 * @q       : damping factor in Q15. Q15_MAX/2 (16384) = Butterworth (Q=0.707)
 *            Higher values = more resonance, lower values = overdamped
 */
void picosynth_init_svf_lp(picosynth_node_t *n,
                           const q15_t *gain,
                           const q15_t *in,
                           q15_t f_coeff,
                           q15_t q);

/* Initialize 2-pole SVF high-pass filter */
void picosynth_init_svf_hp(picosynth_node_t *n,
                           const q15_t *gain,
                           const q15_t *in,
                           q15_t f_coeff,
                           q15_t q);

/* Initialize 2-pole SVF band-pass filter */
void picosynth_init_svf_bp(picosynth_node_t *n,
                           const q15_t *gain,
                           const q15_t *in,
                           q15_t f_coeff,
                           q15_t q);

/* Set SVF frequency coefficient with smoothing */
void picosynth_svf_set_freq(picosynth_node_t *n, q15_t f_coeff);

/* Calculate SVF frequency coefficient from cutoff frequency (Hz).
 * Returns f = 2*sin(pi*fc/fs) in Q15 format.
 * Valid range: 1 Hz to SAMPLE_RATE/4 (higher values clamped for stability).
 * Note: Very low frequencies (<20 Hz) may have reduced precision.
 */
q15_t picosynth_svf_freq(uint16_t fc_hz);

/* Initialize 3-input mixer node */
void picosynth_init_mix(picosynth_node_t *n,
                        const q15_t *gain,
                        const q15_t *in1,
                        const q15_t *in2,
                        const q15_t *in3);

/* Process one sample (mix all voices, apply soft clipping) */
q15_t picosynth_process(picosynth_t *s);

/* Waveform generators. Input: phase [0, Q15_MAX]. Output: sample [-Q15_MAX,
 * Q15_MAX].
 */
q15_t picosynth_wave_saw(q15_t phase);      /* Rising sawtooth */
q15_t picosynth_wave_square(q15_t phase);   /* Square wave */
q15_t picosynth_wave_triangle(q15_t phase); /* Triangle wave */
q15_t picosynth_wave_falling(q15_t phase);  /* Falling ramp */
q15_t picosynth_wave_exp(q15_t phase);      /* Exponential decay [0, Q15_MAX] */
q15_t picosynth_wave_noise(q15_t phase);    /* White noise (phase ignored) */
q15_t picosynth_wave_sine(q15_t phase);     /* Sine (LUT-based or sinf) */

/* Convert milliseconds to sample count */
#define PICOSYNTH_MS(ms) ((uint32_t) ((long) (ms) * SAMPLE_RATE / 1000))

#endif /* PICOSYNTH_H_ */
