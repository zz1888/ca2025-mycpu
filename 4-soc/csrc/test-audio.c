// SPDX-License-Identifier: MIT
// Test audio output via MMIO

#include <stdint.h>
#include "mmio.h"

// UART functions
void uart_putc(char c) {
    while (!(*UART_STATUS & 0x1));
    *UART_SEND = c;
}

void print_str(const char* s) {
    while (*s) uart_putc(*s++);
}

void print_hex(uint32_t val) {
    const char hex[] = "0123456789ABCDEF";
    print_str("0x");
    for (int i = 7; i >= 0; i--) {
        uart_putc(hex[(val >> (i * 4)) & 0xF]);
    }
}

// Simple sine wave approximation (Q1.15)
int16_t sine_wave(uint32_t phase) {
    // phase: 0-65535 maps to 0-2π
    // Simple 4-segment piecewise linear approximation
    uint16_t p = (uint16_t)phase;
    
    if (p < 16384) {
        // 0 to π/2: linear up from 0 to 32767
        return (int16_t)((p * 2));
    } else if (p < 32768) {
        // π/2 to π: linear down from 32767 to 0
        return (int16_t)((32768 - p) * 2);
    } else if (p < 49152) {
        // π to 3π/2: linear down from 0 to -32767
        return -(int16_t)((p - 32768) * 2);
    } else {
        // 3π/2 to 2π: linear up from -32767 to 0
        return -(int16_t)((65536 - p) * 2);
    }
}

int main(void) {
    print_str("\n=== Audio Output Test ===\n\n");
    
    // Check audio device ID
    print_str("Audio ID: ");
    uint32_t id = AUDIO_ID;
    print_hex(id);
    print_str("\n");
    
    if (id != 0x41554449) {  // 'AUDI'
        print_str("ERROR: Audio device not found!\n");
        return 1;
    }
    
    print_str("Audio device detected!\n");
    print_str("Playing 440 Hz tone for ~1 second...\n\n");
    
    // Generate 440 Hz tone at 11025 Hz sample rate
    // phase increment = (440 * 65536) / 11025 ≈ 2615
    const uint32_t PHASE_INC = 2615;
    const uint32_t NUM_SAMPLES = 11025;  // 1 second
    
    uint32_t phase = 0;
    uint32_t samples_written = 0;
    
    for (uint32_t i = 0; i < NUM_SAMPLES; i++) {
        // Wait if FIFO is full
        while ((AUDIO_STATUS & 0x2) != 0) {
            // FIFO full, wait
        }
        
        // Generate sample
        int16_t sample = sine_wave(phase);
        phase += PHASE_INC;
        
        // Write to audio DATA register
        AUDIO_DATA = (uint32_t)sample;
        samples_written++;
        
        // Progress indicator every 1000 samples
        if ((i % 1000) == 0) {
            print_str(".");
        }
    }
    
    print_str("\n\nDone! Wrote ");
    print_hex(samples_written);
    print_str(" samples\n");
    print_str("(Audio in FIFO, will be saved to output.wav on exit)\n");
    
    print_str("\n=== Test Complete ===\n");
    print_str("(Program will now enter _exit loop, simulator will auto-exit after 1M cycles)\n");
    
    // Return normally, which will call _exit and enter infinite loop
    // The simulator's stuck PC detection will trigger after 1M cycles
    return 0;
}
