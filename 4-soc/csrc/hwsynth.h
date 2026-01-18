// SPDX-License-Identifier: MIT
// Hardware Synthesizer Driver Header - Complete picosynth implementation
//
// Features:
//   - 4-voice polyphony
//   - 5 waveforms: Saw, Square, Triangle, Sine, Noise
//   - AHDSR Envelope (Attack-Hold-Decay-Sustain-Release)
//   - SVF Filter with LP/HP/BP modes and resonance
//   - Envelope → Filter cutoff modulation
//   - DC Blocker on final output

#ifndef HWSYNTH_H
#define HWSYNTH_H

#include <stdint.h>

// ============================================================================
// Base address and register map
// ============================================================================
#define HWSYNTH_BASE    0x80000000

// Global registers
#define HWSYNTH_ID      (*(volatile uint32_t*)(HWSYNTH_BASE + 0x00))
#define HWSYNTH_CTRL    (*(volatile uint32_t*)(HWSYNTH_BASE + 0x04))
#define HWSYNTH_STATUS  (*(volatile uint32_t*)(HWSYNTH_BASE + 0x08))
#define HWSYNTH_SAMPLE  (*(volatile int16_t*)(HWSYNTH_BASE + 0x0C))

// Voice base addresses (32 bytes per voice)
#define HWSYNTH_VOICE0  (HWSYNTH_BASE + 0x10)
#define HWSYNTH_VOICE1  (HWSYNTH_BASE + 0x30)
#define HWSYNTH_VOICE2  (HWSYNTH_BASE + 0x50)
#define HWSYNTH_VOICE3  (HWSYNTH_BASE + 0x70)

// Voice register offsets
#define VOICE_FREQ      0x00    // [15:0] Phase increment
#define VOICE_WAVE      0x04    // [2:0] Waveform type
#define VOICE_ENV_ADSR  0x08    // [7:0]A [15:8]H [23:16]D [31:24]R
#define VOICE_ENV_SUS   0x0C    // [15:0] Sustain level
#define VOICE_FILTER    0x10    // [15:0]cutoff [23:16]Q [25:24]mode
#define VOICE_MOD       0x14    // [15:0]amount [16]enable
#define VOICE_GATE      0x18    // [0]gate [1]trigger

// Voice register accessors
#define HWSYNTH_VOICE_REG(n, off)  (*(volatile uint32_t*)(HWSYNTH_VOICE0 + (n)*0x20 + (off)))

// ============================================================================
// Waveform types
// ============================================================================
#define WAVE_SAW      0
#define WAVE_SQUARE   1
#define WAVE_TRIANGLE 2
#define WAVE_SINE     3
#define WAVE_NOISE    4

// ============================================================================
// Filter modes
// ============================================================================
#define FILTER_LP     0   // Low-pass
#define FILTER_HP     1   // High-pass
#define FILTER_BP     2   // Band-pass

// ============================================================================
// Expected peripheral ID
// ============================================================================
#define HWSYNTH_ID_EXPECTED 0x53594E54  // 'SYNT'

// ============================================================================
// MIDI note to frequency table (phase increment at 11025 Hz)
// ============================================================================
static const uint16_t midi_to_freq[] = {
    // C4 to B4 (notes 60-71)
    1554, 1647, 1745, 1849, 1959, 2075, 2199, 2330, 2469, 2616, 2771, 2936,
    // C5 to B5 (notes 72-83)
    3109, 3294, 3490, 3698, 3918, 4151, 4398, 4660, 4938, 5232, 5542, 5872,
    // C6 to B6 (notes 84-95)
    6218, 6588, 6980, 7396, 7836, 8302, 8796, 9320, 9876, 10464, 11084, 11744,
};

// ============================================================================
// Initialization
// ============================================================================
static inline int hwsynth_init(void) {
    if (HWSYNTH_ID != HWSYNTH_ID_EXPECTED) {
        return -1;
    }
    HWSYNTH_CTRL = 0x00;  // Disable
    return 0;
}

// ============================================================================
// Global control
// ============================================================================
static inline void hwsynth_enable(uint8_t voice_mask) {
    HWSYNTH_CTRL = 0x01 | ((voice_mask & 0xF) << 4);
}

static inline void hwsynth_disable(void) {
    HWSYNTH_CTRL = 0x00;
}

// ============================================================================
// Voice configuration - Complete
// ============================================================================

// Set voice frequency directly
static inline void hwsynth_set_freq(uint8_t voice, uint16_t freq) {
    if (voice >= 4) return;
    HWSYNTH_VOICE_REG(voice, VOICE_FREQ) = freq;
}

// Set voice frequency from MIDI note
static inline void hwsynth_set_note(uint8_t voice, uint8_t note) {
    if (voice >= 4) return;
    uint16_t freq = (note >= 60 && note <= 95) ? midi_to_freq[note - 60] : 1554;
    HWSYNTH_VOICE_REG(voice, VOICE_FREQ) = freq;
}

// Set waveform type
static inline void hwsynth_set_wave(uint8_t voice, uint8_t wave) {
    if (voice >= 4) return;
    HWSYNTH_VOICE_REG(voice, VOICE_WAVE) = wave & 0x7;
}

// Set AHDSR envelope parameters
static inline void hwsynth_set_envelope(uint8_t voice,
                                        uint8_t attack, uint8_t hold,
                                        uint8_t decay, uint8_t release) {
    if (voice >= 4) return;
    HWSYNTH_VOICE_REG(voice, VOICE_ENV_ADSR) =
        attack | ((uint32_t)hold << 8) | ((uint32_t)decay << 16) | ((uint32_t)release << 24);
}

// Set sustain level (0-32767)
static inline void hwsynth_set_sustain(uint8_t voice, uint16_t level) {
    if (voice >= 4) return;
    HWSYNTH_VOICE_REG(voice, VOICE_ENV_SUS) = level;
}

// Set filter parameters
// cutoff: 0-32767 (0=closed, 32767=fully open)
// resonance: 0-255 (0=none, 255=max resonance)
// mode: FILTER_LP, FILTER_HP, or FILTER_BP
static inline void hwsynth_set_filter(uint8_t voice,
                                      uint16_t cutoff, uint8_t resonance, uint8_t mode) {
    if (voice >= 4) return;
    HWSYNTH_VOICE_REG(voice, VOICE_FILTER) =
        cutoff | ((uint32_t)resonance << 16) | ((uint32_t)(mode & 0x3) << 24);
}

// Set envelope → filter modulation
// amount: signed, how much envelope affects cutoff (-32768 to 32767)
// enable: 1 to enable modulation, 0 to disable
static inline void hwsynth_set_env_mod(uint8_t voice, int16_t amount, uint8_t enable) {
    if (voice >= 4) return;
    HWSYNTH_VOICE_REG(voice, VOICE_MOD) =
        ((uint32_t)(uint16_t)amount) | ((uint32_t)(enable & 1) << 16);
}

// ============================================================================
// Gate control (note on/off)
// ============================================================================
static inline void hwsynth_gate_on(uint8_t voice) {
    if (voice >= 4) return;
    HWSYNTH_VOICE_REG(voice, VOICE_GATE) = 0x03;  // gate=1, trigger=1
}

static inline void hwsynth_gate_off(uint8_t voice) {
    if (voice >= 4) return;
    HWSYNTH_VOICE_REG(voice, VOICE_GATE) = 0x00;  // gate=0
}

// ============================================================================
// Status queries
// ============================================================================
static inline int16_t hwsynth_get_sample(void) {
    // Note: sample_ready is only high for 1 cycle, so we cannot reliably wait for it
    
    return HWSYNTH_SAMPLE;
}

static inline int hwsynth_sample_ready(void) {
    return (HWSYNTH_STATUS & 0x01) != 0;
}

static inline uint8_t hwsynth_active_voices(void) {
    return (HWSYNTH_STATUS >> 4) & 0xF;
}

// ============================================================================
// Convenience: Full voice setup
// ============================================================================
static inline void hwsynth_voice_setup(uint8_t voice, uint8_t note, uint8_t wave,
                                       uint8_t attack, uint8_t hold,
                                       uint8_t decay, uint16_t sustain, uint8_t release,
                                       uint16_t cutoff, uint8_t resonance, uint8_t filter_mode) {
    hwsynth_set_note(voice, note);
    hwsynth_set_wave(voice, wave);
    hwsynth_set_envelope(voice, attack, hold, decay, release);
    hwsynth_set_sustain(voice, sustain);
    hwsynth_set_filter(voice, cutoff, resonance, filter_mode);
    hwsynth_set_env_mod(voice, 0, 0);  // No modulation by default
}

// Classic synth presets
static inline void hwsynth_preset_bass(uint8_t voice, uint8_t note) {
    hwsynth_set_note(voice, note);
    hwsynth_set_wave(voice, WAVE_SAW);
    hwsynth_set_envelope(voice, 0x40, 0, 0x20, 0x30);  // Fast attack, medium decay/release
    hwsynth_set_sustain(voice, 20000);
    hwsynth_set_filter(voice, 8000, 100, FILTER_LP);   // Low-pass with some resonance
    hwsynth_set_env_mod(voice, 16000, 1);              // Envelope opens filter
}

static inline void hwsynth_preset_lead(uint8_t voice, uint8_t note) {
    hwsynth_set_note(voice, note);
    hwsynth_set_wave(voice, WAVE_SQUARE);
    hwsynth_set_envelope(voice, 0x60, 0, 0x10, 0x20);
    hwsynth_set_sustain(voice, 24000);
    hwsynth_set_filter(voice, 20000, 80, FILTER_LP);
    hwsynth_set_env_mod(voice, 8000, 1);
}

static inline void hwsynth_preset_pad(uint8_t voice, uint8_t note) {
    hwsynth_set_note(voice, note);
    hwsynth_set_wave(voice, WAVE_TRIANGLE);
    hwsynth_set_envelope(voice, 0x10, 0x20, 0x08, 0x10);  // Slow attack, hold, slow decay
    hwsynth_set_sustain(voice, 28000);
    hwsynth_set_filter(voice, 16000, 40, FILTER_LP);
    hwsynth_set_env_mod(voice, 4000, 1);
}

static inline void hwsynth_preset_strings(uint8_t voice, uint8_t note) {
    hwsynth_set_note(voice, note);
    hwsynth_set_wave(voice, WAVE_SAW);
    hwsynth_set_envelope(voice, 0x08, 0, 0x04, 0x08);  // Very slow attack/release
    hwsynth_set_sustain(voice, 30000);
    hwsynth_set_filter(voice, 24000, 20, FILTER_LP);
    hwsynth_set_env_mod(voice, 2000, 1);
}

#endif // HWSYNTH_H
