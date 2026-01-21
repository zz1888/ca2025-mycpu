/* Bare-metal piano synth example for MyCPU
 * Outputs audio samples to AudioPeripheral FIFO
 * No stdio, no file I/O - 100% hardware accelerated
 */
#include <stdint.h>
#include <stddef.h>

#include "melody.h"
#include "picosynth.h"
#include "mmio.h"

/* Simple heap allocator (bump allocator) */
extern char _end;
static char *heap_ptr = 0;

void *malloc(size_t size)
{
    if (!heap_ptr)
        heap_ptr = &_end;
    size = (size + 3) & ~3;
    char *p = heap_ptr;
    heap_ptr += size;
    return p;
}

void free(void *ptr)
{
    (void)ptr;
}

static void uart_putc(char c)
{
    while (!(*UART_STATUS & 0x1))
        ;
    *UART_SEND = c;
}

static void print_str(const char *s)
{
    while (*s)
        uart_putc(*s++);
}

static void print_hex(uint32_t val)
{
    const char hex[] = "0123456789ABCDEF";
    uart_putc('0');
    uart_putc('x');
    for (int i = 28; i >= 0; i -= 4)
        uart_putc(hex[(val >> i) & 0xF]);
}

static void print_dec(int32_t val)
{
    if (val < 0) {
        uart_putc('-');
        val = -val;
    }
    char buf[12];
    int i = 0;
    do {
        buf[i++] = '0' + u32_rem((uint32_t)val, 10);
        val = u32_div((uint32_t)val, 10);
    } while (val > 0);
    while (i > 0)
        uart_putc(buf[--i]);
}

static void audio_wait_ready(void)
{
    while (AUDIO_STATUS & AUDIO_FIFO_FULL)
        ;
}

static void audio_write_sample(int16_t sample)
{
    audio_wait_ready();
    AUDIO_DATA = (uint32_t)(int32_t)sample;
}

static q15_t partial2_offset;
static q15_t partial3_offset;

static picosynth_node_t *g_flt_main;
static picosynth_node_t *g_flt_harm;
static picosynth_node_t *g_flt_noise;

static q15_t get_inharmonicity_coeff(uint8_t note)
{
    static const q15_t B_table[12] = {1, 1, 1, 1, 2, 2, 2, 2, 2, 2, 3, 3};
    int octave = u32_div((uint32_t)note, 12);
    int semitone = u32_rem((uint32_t)note, 12);
    int32_t B = B_table[semitone];
    for (int i = 0; i < octave - 4; i++) B <<= 2;
    for (int i = octave; i < 4; i++) B >>= 2;
    if (B < 1) B = 1;
    if (B > 65) B = 65;
    return (q15_t) B;
}

static void calc_partial_frequencies(uint8_t note, q15_t base_freq)
{
    q15_t B = get_inharmonicity_coeff(note);
    int32_t stretch2 = ((int32_t) B * 4 * base_freq) >> 15;
    partial2_offset = q15_sat(base_freq + stretch2);
    int32_t stretch3 = ((int32_t) B * 14 * base_freq) >> 15;
    int32_t offset3 = 2 * (int32_t) base_freq + stretch3;
    partial3_offset = q15_sat(offset3);
}

static q15_t calc_svf_freq(uint8_t note)
{
    int32_t fc = 600 + 20 * ((int32_t) note - 48);
    if (fc < 500) fc = 500;
    if (fc > 1500) fc = 1500;
    return picosynth_svf_freq((uint16_t) fc);
}

int main(void)
{
    print_str("Piano Synth Example");
    print_str("Audio ID: ");
    print_hex(AUDIO_ID);
    print_str("");

    picosynth_t *picosynth = picosynth_create(4, 8);
    if (!picosynth) {
        print_str("Failed to create synth");
        return 1;
    }

    q15_t piano_q = Q15_MAX;

    /* Voice 0: FUNDAMENTAL */
    picosynth_voice_t *v = picosynth_get_voice(picosynth, 0);
    picosynth_node_t *v0_flt = picosynth_voice_get_node(v, 0);
    picosynth_node_t *v0_env = picosynth_voice_get_node(v, 1);
    picosynth_node_t *v0_osc = picosynth_voice_get_node(v, 2);

    picosynth_init_env(v0_env, NULL,
        &(picosynth_env_params_t){.attack=10000, .hold=0, .decay=60,
            .sustain=(q15_t)(Q15_MAX*15/100), .release=40});
    picosynth_init_osc(v0_osc, &v0_env->out, picosynth_voice_freq_ptr(v), picosynth_wave_sine);
    picosynth_init_svf_lp(v0_flt, NULL, &v0_osc->out, picosynth_svf_freq(1200), piano_q);
    g_flt_main = v0_flt;
    picosynth_voice_set_out(v, 0);

    /* Voice 1: 2nd-3rd PARTIALS */
    v = picosynth_get_voice(picosynth, 1);
    picosynth_node_t *v1_flt = picosynth_voice_get_node(v, 0);
    picosynth_node_t *v1_env1 = picosynth_voice_get_node(v, 1);
    picosynth_node_t *v1_osc1 = picosynth_voice_get_node(v, 2);
    picosynth_node_t *v1_env2 = picosynth_voice_get_node(v, 3);
    picosynth_node_t *v1_osc2 = picosynth_voice_get_node(v, 4);
    picosynth_node_t *v1_mix = picosynth_voice_get_node(v, 5);

    picosynth_init_env(v1_env1, NULL,
        &(picosynth_env_params_t){.attack=8000, .hold=0, .decay=150,
            .sustain=(q15_t)(Q15_MAX*8/100), .release=50});
    picosynth_init_osc(v1_osc1, &v1_env1->out, picosynth_voice_freq_ptr(v), picosynth_wave_sine);
    v1_osc1->osc.detune = &partial2_offset;

    picosynth_init_env(v1_env2, NULL,
        &(picosynth_env_params_t){.attack=7000, .hold=0, .decay=300,
            .sustain=(q15_t)(Q15_MAX*4/100), .release=40});
    picosynth_init_osc(v1_osc2, &v1_env2->out, picosynth_voice_freq_ptr(v), picosynth_wave_sine);
    v1_osc2->osc.detune = &partial3_offset;

    picosynth_init_mix(v1_mix, NULL, &v1_osc1->out, &v1_osc2->out, NULL);
    picosynth_init_svf_lp(v1_flt, NULL, &v1_mix->out, picosynth_svf_freq(1200), piano_q);
    g_flt_harm = v1_flt;
    picosynth_voice_set_out(v, 0);

    /* Voice 2: UPPER PARTIALS */
    v = picosynth_get_voice(picosynth, 2);
    picosynth_node_t *v2_flt = picosynth_voice_get_node(v, 0);
    picosynth_node_t *v2_env = picosynth_voice_get_node(v, 1);
    picosynth_node_t *v2_osc = picosynth_voice_get_node(v, 2);

    picosynth_init_env(v2_env, NULL,
        &(picosynth_env_params_t){.attack=5000, .hold=0, .decay=800,
            .sustain=(q15_t)(Q15_MAX*1/100), .release=20});
    picosynth_init_osc(v2_osc, &v2_env->out, picosynth_voice_freq_ptr(v), picosynth_wave_sine);
    picosynth_init_svf_lp(v2_flt, NULL, &v2_osc->out, picosynth_svf_freq(1500), piano_q);
    picosynth_voice_set_out(v, 0);

    /* Voice 3: HAMMER NOISE */
    v = picosynth_get_voice(picosynth, 3);
    picosynth_node_t *v3_lp = picosynth_voice_get_node(v, 0);
    picosynth_node_t *v3_env = picosynth_voice_get_node(v, 1);
    picosynth_node_t *v3_noise = picosynth_voice_get_node(v, 2);
    picosynth_node_t *v3_hp = picosynth_voice_get_node(v, 3);

    picosynth_init_env(v3_env, NULL,
        &(picosynth_env_params_t){.attack=8000, .hold=0, .decay=6000,
            .sustain=0, .release=50});
    picosynth_init_osc(v3_noise, &v3_env->out, picosynth_voice_freq_ptr(v), picosynth_wave_noise);
    picosynth_init_svf_hp(v3_hp, NULL, &v3_noise->out, picosynth_svf_freq(200), piano_q);
    picosynth_init_svf_lp(v3_lp, NULL, &v3_hp->out, picosynth_svf_freq(800), piano_q);
    g_flt_noise = v3_lp;
    picosynth_voice_set_out(v, 0);

    print_str("Synth initialized, playing melody...");

    uint32_t note_dur = 0;
    uint32_t note_idx = 0;
    uint32_t sample_count = 0;

    for (;;) {
        if (note_dur == 0) {
            note_dur = PICOSYNTH_MS(u32_div(2000, melody_beats[note_idx]));
            uint8_t note = melody[note_idx];
            if (note) {
                picosynth_note_on(picosynth, 0, note);
                picosynth_note_on(picosynth, 1, note);
                picosynth_note_on(picosynth, 2, note);
                picosynth_note_on(picosynth, 3, note);

                q15_t base_freq = *picosynth_voice_freq_ptr(picosynth_get_voice(picosynth, 0));
                calc_partial_frequencies(note, base_freq);

                q15_t svf_f = calc_svf_freq(note);
                picosynth_svf_set_freq(g_flt_main, svf_f);

                int32_t fc_harm = 700 + 15 * ((int32_t) note - 48);
                if (fc_harm < 500) fc_harm = 500;
                if (fc_harm > 1400) fc_harm = 1400;
                picosynth_svf_set_freq(g_flt_harm, picosynth_svf_freq((uint16_t) fc_harm));

                int32_t fc_noise = 500 + 10 * ((int32_t) note - 48);
                if (fc_noise < 400) fc_noise = 400;
                if (fc_noise > 1000) fc_noise = 1000;
                picosynth_svf_set_freq(g_flt_noise, picosynth_svf_freq((uint16_t) fc_noise));

                print_str("Note ");
                print_dec(note_idx);
                print_str(": MIDI ");
                print_dec(note);
                print_str("");
            }
            note_idx++;
            if (note_idx >= sizeof(melody))
                break;
        } else if (note_dur < 200) {
            picosynth_note_off(picosynth, 0);
            picosynth_note_off(picosynth, 1);
            picosynth_note_off(picosynth, 2);
            picosynth_note_off(picosynth, 3);
        }
        note_dur--;

        int16_t sample = picosynth_process(picosynth);
        audio_write_sample(sample);
        sample_count++;
    }

    uint32_t tail_samples = SAMPLE_RATE;
    for (uint32_t i = 0; i < tail_samples; i++) {
        int16_t sample = picosynth_process(picosynth);
        audio_write_sample(sample);
        sample_count++;
    }

    print_str("Done! Total samples: ");
    print_dec(sample_count);
    print_str("");

    picosynth_destroy(picosynth);

    for (;;)
        ;

    return 0;
}
