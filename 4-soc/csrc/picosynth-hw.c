// SPDX-License-Identifier: MIT
// Hardware Synthesizer Music Demo - Uses HWSynth peripheral instead of software synthesis
// This demonstrates the HWSynth hardware offloading: CPU only handles MIDI parsing
// and voice allocation, all DSP is done in hardware.

#include "hwsynth.h"
#include "mmio.h"
#include <stdint.h>

// ============================================================================
// UART Debug Output
// ============================================================================
static void uart_putc(char c) {
    while (!(*UART_STATUS & 0x1));
    *UART_SEND = c;
}

static void print_str(const char *s) {
    while (*s) uart_putc(*s++);
}

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

static void print_hex(uint32_t val) {
    static const char hex[] = "0123456789ABCDEF";
    print_str("0x");
    for (int i = 7; i >= 0; i--) uart_putc(hex[(val >> (i * 4)) & 0xF]);
}

// ============================================================================
// Audio Output - Write samples to AudioPeripheral FIFO
// ============================================================================
static inline void audio_write_sample(int16_t sample) {
    // Non-blocking write (drop if FIFO full)
    AUDIO_DATA = (uint32_t)(uint16_t)sample;
}

static inline int audio_fifo_full(void) {
    return (AUDIO_STATUS & 0x2) != 0;
}

// ============================================================================
// Simple Voice Allocator for 4-voice polyphony
// ============================================================================
#define MAX_VOICES 4

typedef struct {
    uint8_t note;       // MIDI note (0 = free)
    uint8_t active;     // Voice is playing
} voice_slot_t;

static voice_slot_t voice_slots[MAX_VOICES];

// Find a free voice or steal the oldest one
static int allocate_voice(uint8_t note) {
    // First, check if this note is already playing
    for (int i = 0; i < MAX_VOICES; i++) {
        if (voice_slots[i].note == note && voice_slots[i].active) {
            return i;  // Retrigger same voice
        }
    }

    // Find a free voice
    for (int i = 0; i < MAX_VOICES; i++) {
        if (!voice_slots[i].active) {
            return i;
        }
    }

    // All voices busy - steal voice 0 (simple strategy)
    return 0;
}

static int find_voice_by_note(uint8_t note) {
    for (int i = 0; i < MAX_VOICES; i++) {
        if (voice_slots[i].note == note && voice_slots[i].active) {
            return i;
        }
    }
    return -1;
}

// ============================================================================
// HWSynth Voice Setup - Configure voice for piano-like sound
// ============================================================================
static void setup_voice(int voice, uint8_t wave) {
    // AHDSR envelope: moderate attack, short hold, medium decay, sustain at 60%
    hwsynth_set_envelope(voice, 0x40, 0x10, 0x20, 0x30);  // A, H, D, R rates
    hwsynth_set_sustain(voice, 19660);  // ~60% of 32767

    // SVF filter: LP mode, moderate cutoff, slight resonance
    hwsynth_set_filter(voice, 20000, 30, FILTER_LP);

    // Envelope modulation to filter (subtle)
    hwsynth_set_env_mod(voice, 5000, 1);

    // Waveform
    hwsynth_set_wave(voice, wave);
}

// ============================================================================
// Note On/Off Handlers
// ============================================================================
static void note_on(uint8_t note, uint8_t velocity) {
    int voice = allocate_voice(note);

    voice_slots[voice].note = note;
    voice_slots[voice].active = 1;

    // Set frequency from MIDI note
    hwsynth_set_note(voice, note);

    // Adjust sustain based on velocity (louder = higher sustain)
    int16_t sustain = (int16_t)((int32_t)velocity * 256);  // Scale 0-127 to 0-32512
    hwsynth_set_sustain(voice, sustain);

    // Trigger note
    hwsynth_gate_on(voice);
}

static void note_off(uint8_t note) {
    int voice = find_voice_by_note(note);
    if (voice >= 0) {
        hwsynth_gate_off(voice);
        // Note: don't clear active flag yet - let release phase complete
        // The hardware will handle the envelope release
        voice_slots[voice].active = 0;  // Allow voice reuse
    }
}

// ============================================================================
// Melody Data - Twinkle Twinkle Little Star
// Format: {note, duration_in_samples} pairs, note=0 means rest
// ============================================================================
typedef struct {
    uint8_t note;
    uint16_t duration;  // in samples (at 11025 Hz)
} melody_note_t;

// Duration constants at 11025 Hz sample rate
#define QUARTER_NOTE  2756   // 250ms
#define HALF_NOTE     5512   // 500ms
#define WHOLE_NOTE    11025  // 1000ms
#define EIGHTH_NOTE   1378   // 125ms

static const melody_note_t melody[] = {
    // Twinkle Twinkle Little Star
    {60, QUARTER_NOTE}, {60, QUARTER_NOTE}, {67, QUARTER_NOTE}, {67, QUARTER_NOTE},
    {69, QUARTER_NOTE}, {69, QUARTER_NOTE}, {67, HALF_NOTE},
    {65, QUARTER_NOTE}, {65, QUARTER_NOTE}, {64, QUARTER_NOTE}, {64, QUARTER_NOTE},
    {62, QUARTER_NOTE}, {62, QUARTER_NOTE}, {60, HALF_NOTE},
    // Second verse
    {67, QUARTER_NOTE}, {67, QUARTER_NOTE}, {65, QUARTER_NOTE}, {65, QUARTER_NOTE},
    {64, QUARTER_NOTE}, {64, QUARTER_NOTE}, {62, HALF_NOTE},
    {67, QUARTER_NOTE}, {67, QUARTER_NOTE}, {65, QUARTER_NOTE}, {65, QUARTER_NOTE},
    {64, QUARTER_NOTE}, {64, QUARTER_NOTE}, {62, HALF_NOTE},
    // Repeat first verse
    {60, QUARTER_NOTE}, {60, QUARTER_NOTE}, {67, QUARTER_NOTE}, {67, QUARTER_NOTE},
    {69, QUARTER_NOTE}, {69, QUARTER_NOTE}, {67, HALF_NOTE},
    {65, QUARTER_NOTE}, {65, QUARTER_NOTE}, {64, QUARTER_NOTE}, {64, QUARTER_NOTE},
    {62, QUARTER_NOTE}, {62, QUARTER_NOTE}, {60, WHOLE_NOTE},
    {0, 0}  // End marker
};

// For fast simulation, limit melody length
#define FAST_SIM 1
#if FAST_SIM
#define MAX_NOTES 8
#define SAMPLE_LIMIT 500  // Samples per note
#else
#define MAX_NOTES 100
#define SAMPLE_LIMIT 0    // Use full duration
#endif

// ============================================================================
// Main Program
// ============================================================================
int main(void) {
    *UART_ENABLE = 1;

    print_str("\n");
    print_str("===========================================\n");
    print_str("  HWSynth Music Demo (Hardware Synthesis)  \n");
    print_str("===========================================\n\n");

    // Check HWSynth peripheral
    print_str("HWSynth ID: ");
    print_hex(HWSYNTH_ID);
    print_str(" (expected 0x53594E54)\n");

    if (hwsynth_init() != 0) {
        print_str("ERROR: HWSynth not found!\n");
        return 1;
    }
    print_str("HWSynth initialized.\n");

    // Check Audio peripheral
    print_str("Audio ID: ");
    print_hex(AUDIO_ID);
    print_str(" (expected 0x41554449)\n");

    if (AUDIO_ID != 0x41554449) {
        print_str("ERROR: Audio device not found!\n");
        return 2;
    }
    print_str("Audio device ready.\n\n");

    // Initialize voice slots
    for (int i = 0; i < MAX_VOICES; i++) {
        voice_slots[i].note = 0;
        voice_slots[i].active = 0;
        setup_voice(i, WAVE_SAW);
    }

    // Enable all 4 voices
    hwsynth_enable(0x0F);
    print_str("All 4 voices enabled.\n\n");

    // Play melody
    print_str("Playing Twinkle Twinkle Little Star...\n");
    print_str("(Hardware synthesis - CPU only handles MIDI events)\n\n");

    int note_idx = 0;
    uint8_t current_note = 0;

    while (melody[note_idx].duration != 0 && note_idx < MAX_NOTES) {
        melody_note_t m = melody[note_idx];

        // Note change
        if (current_note != 0 && current_note != m.note) {
            note_off(current_note);
        }

        if (m.note != 0) {
            if (current_note != m.note) {
                note_on(m.note, 100);  // velocity = 100
                print_str("Note ON: ");
                print_uint(m.note);
                print_str(" (");
                // Print note name
                static const char *note_names[] = {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};
                print_str(note_names[m.note % 12]);
                print_uint(m.note / 12 - 1);
                print_str(")\n");
            }
            current_note = m.note;
        } else {
            current_note = 0;
            print_str("Rest\n");
        }

        // Generate samples for this note's duration
        uint16_t duration = (SAMPLE_LIMIT > 0 && m.duration > SAMPLE_LIMIT) ? SAMPLE_LIMIT : m.duration;

        for (uint16_t s = 0; s < duration; s++) {
            // Read mixed sample from HWSynth hardware
            int16_t sample = hwsynth_get_sample();

            // Write to audio output FIFO
            audio_write_sample(sample);
        }

        note_idx++;
    }

    // Release final note and let it decay
    if (current_note != 0) {
        note_off(current_note);
    }

    // Generate some silence for release tail
    print_str("\nRelease tail...\n");
    for (int i = 0; i < QUARTER_NOTE; i++) {
        int16_t sample = hwsynth_get_sample();
        audio_write_sample(sample);
    }

    // Disable synth
    hwsynth_disable();

    print_str("\n=== Done! ===\n");
    print_str("Hardware synth demo complete.\n");
    print_str("Check output.wav for audio.\n");

    return 0;
}
