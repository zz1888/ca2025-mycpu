#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include "mmio.h"
#include "melody.h"
#include "picosynth.h"

/* Frequency offsets for true partials (added to base freq via detune) */
static q15_t partial2_offset; /* 2nd partial: base_freq (so total = 2*base) */
static q15_t partial3_offset; /* 3rd partial: 2*base_freq (so total = 3*base) */

/* Filter node pointers for dynamic frequency tracking */
static picosynth_node_t *g_flt_main;
static picosynth_node_t *g_flt_harm;
static picosynth_node_t *g_flt_noise;

/* Inharmonicity coefficient table (Q15 format).
 * B scales with frequency squared: B ≈ 7e-5 * (f/440)^2
 * Real piano: Bass ~0.00005, Middle ~0.0001, Treble ~0.001+
 */
static q15_t get_inharmonicity_coeff(uint8_t note)
{
    /* Pre-calculated B values for key MIDI notes (Q15 scaled by 32768)
     * Using formula: B = 7e-5 * (f/440)^2, clamped 2e-5 to 2e-3
     */
    static const q15_t B_table[12] = {
        /* C    C#   D    D#   E    F    F#   G    G#   A    A#   B  */
        1, 1, 1, 1, 2, 2, 2, 2, 2, 2, 3, 3, /* Base values, scaled by octave */
    };

    int octave = note / 12;
    int semitone = note % 12;

    /* Scale B by octave (doubles each octave due to f^2 relationship) */
    int32_t B = B_table[semitone];
    for (int i = 0; i < octave - 4;
         i++)    /* Octave 4 (MIDI 48-59) is reference */
        B <<= 2; /* B quadruples per octave (f^2) */
    for (int i = octave; i < 4; i++)
        B >>= 2;

    /* Clamp to realistic range */
    if (B < 1)
        B = 1;
    if (B > 65)
        B = 65; /* ~0.002 in Q15 */

    return (q15_t) B;
}

/* Calculate partial frequencies with inharmonicity stretching.
 * Real piano formula: fn = n * f1 * sqrt(1 + B * n^2)
 * Approximation: fn ≈ n * f1 * (1 + B * n^2 / 2) for small B
 *
 * Sets global variables for oscillator detune offsets.
 */
static void calc_partial_frequencies(uint8_t note, q15_t base_freq)
{
    q15_t B = get_inharmonicity_coeff(note);

    /* 2nd partial: freq = 2*f1*(1 + B*4/2) = 2*f1 + 4*B*f1
     * offset = f1 + 4*B*f1 (since detune is added to base_freq)
     */
    int32_t stretch2 = ((int32_t) B * 4 * base_freq) >> 15;
    partial2_offset = q15_sat(base_freq + stretch2);

    /* 3rd partial: freq = 3*f1*(1 + B*9/2) ≈ 3*f1 + 13.5*B*f1
     * offset = 2*f1 + inharmonic stretch
     * Only include if 3*f1 < Nyquist (5512 Hz at 11025 sample rate)
     */
    int32_t stretch3 = ((int32_t) B * 14 * base_freq) >> 15;
    int32_t offset3 = 2 * (int32_t) base_freq + stretch3;
    /* Clamp to Q15 range - high notes may overflow */
    partial3_offset = q15_sat(offset3);
}

/* Frequency-tracked SVF filter coefficient.
 * Conservative cutoffs for warm piano sound.
 * fc = 600 + 20 * (note - 48), clamped 500-1500 Hz
 */
static q15_t calc_svf_freq(uint8_t note)
{
    /* Conservative frequency tracking for warm sound */
    int32_t fc = 600 + 20 * ((int32_t) note - 48);
    if (fc < 500)
        fc = 500; /* Bass: warm */
    if (fc > 1500)
        fc = 1500; /* Treble: still warm, not bright */

    return picosynth_svf_freq((uint16_t) fc);
}

static int write_wav(char *filename, int16_t *buf, uint32_t samples)
{
    FILE *f = fopen(filename, "wb");
    if (!f) {
        printf("Failed to open output file\n");
        return 1;
    }

    uint32_t sample_rate = SAMPLE_RATE;
    uint32_t file_size = samples * 2 + 36;
    uint32_t byte_rate = SAMPLE_RATE * 2;
    uint32_t block_align = 2;
    uint32_t bits_per_sample = 16;
    uint32_t data_size = samples * 2;
    uint32_t fmt_size = 16;
    uint16_t format = 1;
    uint16_t channels = 1;

    fwrite("RIFF", 1, 4, f);
    fwrite(&file_size, 4, 1, f);
    fwrite("WAVE", 1, 4, f);
    fwrite("fmt ", 1, 4, f);
    fwrite(&fmt_size, 4, 1, f);
    fwrite(&format, 2, 1, f);
    fwrite(&channels, 2, 1, f);
    fwrite(&sample_rate, 4, 1, f);
    fwrite(&byte_rate, 4, 1, f);
    fwrite(&block_align, 2, 1, f);
    fwrite(&bits_per_sample, 2, 1, f);
    fwrite("data", 1, 4, f);
    fwrite(&data_size, 4, 1, f);
    fwrite(buf, 2, samples, f);
    fclose(f);
    return 0;
}


int main() {
    while (1) {
        while (AUDIO_STATUS & 0x2);
        AUDIO_DATA = 0x2000;
        while (AUDIO_STATUS & 0x2);
        AUDIO_DATA = 0xE000;
    }
}