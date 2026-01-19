// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

#include <verilated.h>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <iostream>
#include <memory>
#include <queue>
#include <vector>
#include <deque>
// Terminal I/O for interactive UART
#include <fcntl.h>
#include <termios.h>
#include <unistd.h>

#include <SDL2/SDL.h>

#include "VTop.h"

static constexpr uint32_t AUDIO_BASE = 0x60000000;
static std::deque<int16_t> audio_fifo;
static constexpr uint32_t SAMPLE_RATE = 11025;  // 11 kHz for picosynth

// WAV file header structure
struct WavHeader {
    // RIFF chunk
    char riff[4] = {'R', 'I', 'F', 'F'};
    uint32_t file_size;  // File size - 8
    char wave[4] = {'W', 'A', 'V', 'E'};
    
    // fmt subchunk
    char fmt[4] = {'f', 'm', 't', ' '};
    uint32_t fmt_size = 16;  // PCM format size
    uint16_t audio_format = 1;  // PCM = 1
    uint16_t num_channels = 1;  // Mono
    uint32_t sample_rate = SAMPLE_RATE;
    uint32_t byte_rate;  // SampleRate * NumChannels * BitsPerSample/8
    uint16_t block_align;  // NumChannels * BitsPerSample/8
    uint16_t bits_per_sample = 16;
    
    // data subchunk
    char data[4] = {'d', 'a', 't', 'a'};
    uint32_t data_size;  // NumSamples * NumChannels * BitsPerSample/8
};

class SdlAudioOut
{
    SDL_AudioDeviceID device = 0;
    bool enabled = true;
    std::vector<int16_t> buffer;
    static constexpr size_t CHUNK_SAMPLES = 512;

public:
    bool init()
    {
        if (SDL_Init(SDL_INIT_AUDIO) != 0) {
            std::cerr << "⚠️  SDL audio init failed: " << SDL_GetError()
                      << "\n";
            return false;
        }

        SDL_AudioSpec want{};
        want.freq = SAMPLE_RATE;
        want.format = AUDIO_S16SYS;
        want.channels = 1;
        want.samples = 1024;
        want.callback = nullptr;

        SDL_AudioSpec have{};
        device = SDL_OpenAudioDevice(nullptr, 0, &want, &have, 0);
        if (device == 0) {
            std::cerr << "⚠️  SDL audio open failed: " << SDL_GetError()
                      << "\n";
            SDL_QuitSubSystem(SDL_INIT_AUDIO);
            return false;
        }

        if (have.freq != SAMPLE_RATE || have.format != AUDIO_S16SYS ||
            have.channels != 1) {
            std::cerr << "⚠️  SDL audio device format mismatch (freq="
                      << have.freq << ", format=" << have.format
                      << ", channels=" << static_cast<int>(have.channels)
                      << ")\n";
        }

        buffer.reserve(CHUNK_SAMPLES);
        SDL_PauseAudioDevice(device, 0);
        enabled = true;
        return true;
    }

    void push(int16_t sample)
    {
        if (!enabled)
            return;
        buffer.push_back(sample);
        if (buffer.size() >= CHUNK_SAMPLES)
            flush();
    }

    void drain()
    {
        if (!enabled)
            return;
        flush();
        while (SDL_GetQueuedAudioSize(device) > 0)
            SDL_Delay(10);
    }

    void shutdown()
    {
        if (!enabled)
            return;
        drain();
        SDL_CloseAudioDevice(device);
        SDL_QuitSubSystem(SDL_INIT_AUDIO);
        enabled = false;
    }

private:
    void flush()
    {
        if (buffer.empty())
            return;
        SDL_QueueAudio(device, buffer.data(),
                       buffer.size() * sizeof(int16_t));
        buffer.clear();
    }
};

// UART terminal interface for interactive mode
// Simulates 115200 baud, 8N2 (8 data bits, no parity, 2 stop bits)
class UartTerminal
{
    // TX state machine (CPU -> Terminal)
    enum class TxState { IDLE, START, DATA, STOP };
    TxState tx_state = TxState::IDLE;
    uint32_t tx_counter = 0;
    uint8_t tx_bit_idx = 0;
    uint8_t tx_data = 0;
    bool tx_prev = true;

    // RX state machine (Terminal -> CPU)
    enum class RxState { IDLE, START, DATA, STOP };
    RxState rx_state = RxState::IDLE;
    uint32_t rx_counter = 0;
    uint8_t rx_bit_idx = 0;
    uint8_t rx_shift = 0;
    std::queue<uint8_t> rx_fifo;

    // Timing: cycles per bit at 50MHz / 115200 baud
    // UART.scala: BIT_CNT = ((freq + baud/2) / baud - 1) = 433
    // Hardware counts 433 to 0 (inclusive), so actual cycles per bit = 434
    static constexpr uint32_t CYCLES_PER_BIT = 434;
    static constexpr uint32_t HALF_BIT = CYCLES_PER_BIT / 2;

    // Terminal settings
    struct termios orig_termios{};
    bool raw_mode = false;
    bool is_tty = false;

public:
    ~UartTerminal()
    {
        if (raw_mode)
            disable_raw_mode();
    }

    void enable_raw_mode()
    {
        if (raw_mode)
            return;
        is_tty = isatty(STDIN_FILENO);
        // Set stdin to non-blocking
        int flags = fcntl(STDIN_FILENO, F_GETFL, 0);
        if (flags == -1) {
            perror("fcntl F_GETFL");
            return;
        }
        if (fcntl(STDIN_FILENO, F_SETFL, flags | O_NONBLOCK) == -1) {
            perror("fcntl F_SETFL");
            return;
        }
        // Configure raw mode only for TTY (skip for piped input)
        if (is_tty) {
            tcgetattr(STDIN_FILENO, &orig_termios);
            struct termios raw = orig_termios;
            // Input flags: disable CR-to-NL, NL-to-CR, ignore CR, flow control
            raw.c_iflag &= ~(ICRNL | INLCR | IGNCR | IXON | IXOFF);
            // Output flags: disable output processing (no NL-to-CRNL)
            raw.c_oflag &= ~(OPOST);
            // Local flags: disable echo, canonical mode, signals, extensions
            raw.c_lflag &= ~(ECHO | ICANON | ISIG | IEXTEN);
            raw.c_cc[VMIN] = 0;  // Non-blocking read
            raw.c_cc[VTIME] = 0;
            tcsetattr(STDIN_FILENO, TCSAFLUSH, &raw);
        }
        raw_mode = true;
    }

    void disable_raw_mode()
    {
        if (!raw_mode)
            return;
        if (is_tty)
            tcsetattr(STDIN_FILENO, TCSAFLUSH, &orig_termios);
        raw_mode = false;
    }

    // Poll stdin for input, queue bytes for transmission to CPU
    void poll_input()
    {
        char c;
        while (read(STDIN_FILENO, &c, 1) == 1) {
            rx_fifo.push(static_cast<uint8_t>(c));
            // Track Ctrl-C for early exit in terminal mode
            if (c == 0x03)
                ctrl_c_received = true;
        }
    }

    size_t rx_pending() const { return rx_fifo.size(); }
    bool got_ctrl_c() const { return ctrl_c_received; }
    bool sent_ctrl_c() const { return ctrl_c_sent; }
    bool tx_is_idle() const { return tx_state == TxState::IDLE; }

    // Get current RX line state without advancing state machine
    bool current_rx_line() const { return rx_line_value; }

    bool ctrl_c_received = false;   // Track Ctrl-C was queued
    bool ctrl_c_in_flight = false;  // Track Ctrl-C is being serialized
    bool ctrl_c_sent = false;       // Track Ctrl-C transmission complete

    uint64_t debug_cycle = 0;  // Track cycle for debug
    bool debug_enabled = false;

    void set_debug(bool en, uint64_t cyc)
    {
        debug_enabled = en;
        debug_cycle = cyc;
    }

    // Process TX line from CPU (detect and print characters)
    void process_tx(bool tx_line)
    {
        // Detailed line tracing: log every transition and periodically during
        // frame
        if (debug_enabled && tx_state != TxState::IDLE) {
            // Log transitions
            if (tx_line != tx_prev) {
                fprintf(
                    stderr,
                    "[%llu] TX_LINE: %d -> %d (state=%d, counter=%u, bit=%d)\n",
                    (unsigned long long) debug_cycle, tx_prev ? 1 : 0,
                    tx_line ? 1 : 0, (int) tx_state, tx_counter, tx_bit_idx);
            }
            // Log every 100 cycles during active transmission
            if (tx_counter % 100 == 0 && tx_state == TxState::DATA) {
                fprintf(
                    stderr,
                    "[%llu] TX_SAMPLE: line=%d counter=%u bit=%d data=0x%02x\n",
                    (unsigned long long) debug_cycle, tx_line ? 1 : 0,
                    tx_counter, tx_bit_idx, tx_data);
            }
        }

        switch (tx_state) {
        case TxState::IDLE:
            // Detect falling edge (start bit)
            if (tx_prev && !tx_line) {
                tx_state = TxState::START;
                tx_counter = HALF_BIT;  // Sample at middle of start bit
                tx_data = 0;
                tx_bit_idx = 0;
                if (debug_enabled)
                    fprintf(stderr, "[%llu] TX: Start bit detected\n",
                            (unsigned long long) debug_cycle);
            }
            break;

        case TxState::START:
            if (++tx_counter >= CYCLES_PER_BIT) {
                // Verify start bit is still low
                if (!tx_line) {
                    tx_state = TxState::DATA;
                    tx_counter = 0;
                } else {
                    tx_state = TxState::IDLE;  // False start
                }
            }
            break;

        case TxState::DATA:
            if (++tx_counter >= CYCLES_PER_BIT) {
                tx_counter = 0;
                // Sample data bit (LSB first)
                tx_data |= (tx_line ? 1 : 0) << tx_bit_idx;
                if (debug_enabled)
                    fprintf(stderr,
                            "[%llu] TX: bit %d = %d, data so far = 0x%02x\n",
                            (unsigned long long) debug_cycle, tx_bit_idx,
                            tx_line ? 1 : 0, tx_data);
                if (++tx_bit_idx >= 8)
                    tx_state = TxState::STOP;
            }
            break;

        case TxState::STOP:
            if (++tx_counter >=
                CYCLES_PER_BIT * 2) {  // 2 stop bits (8N2 format)
                if (debug_enabled)
                    fprintf(stderr, "[%llu] TX: Received char 0x%02x '%c'\n",
                            (unsigned long long) debug_cycle, tx_data,
                            (tx_data >= 32 && tx_data < 127) ? tx_data : '.');
                putchar(tx_data);
                fflush(stdout);
                tx_state = TxState::IDLE;
            }
            break;
        }
        tx_prev = tx_line;
    }

    bool rx_line_value = true;  // Current RX line value (cached)

    // Generate RX line to CPU (serialize queued bytes)
    // Returns the line value and updates rx_line_value cache
    bool get_rx_line()
    {
        switch (rx_state) {
        case RxState::IDLE:
            if (!rx_fifo.empty()) {
                rx_shift = rx_fifo.front();
                rx_fifo.pop();
                rx_state = RxState::START;
                rx_counter = 0;
                rx_bit_idx = 0;
                rx_line_value = false;  // Start bit (low)
                // Track when Ctrl-C starts transmitting to CPU
                if (rx_shift == 0x03 && ctrl_c_received)
                    ctrl_c_in_flight = true;
                return rx_line_value;
            }
            rx_line_value = true;  // Idle (high)
            return rx_line_value;

        case RxState::START:
            rx_line_value = false;  // Start bit is always low
            if (++rx_counter >= CYCLES_PER_BIT) {
                rx_counter = 0;
                rx_state = RxState::DATA;
            }
            return false;  // Start bit

        case RxState::DATA: {
            // Capture current bit BEFORE any state changes (LSB first)
            bool bit = (rx_shift >> rx_bit_idx) & 1;
            rx_line_value = bit;  // Cache for between-edge reads
            if (++rx_counter >= CYCLES_PER_BIT) {
                rx_counter = 0;
                if (++rx_bit_idx >= 8) {
                    rx_state = RxState::STOP;
                    // Don't change rx_line_value here - keep returning current
                    // bit until next get_rx_line() call
                }
            }
            return bit;
        }

        case RxState::STOP:
            rx_line_value = true;                      // Stop bits (high)
            if (++rx_counter >= CYCLES_PER_BIT * 2) {  // 2 stop bits
                rx_state = RxState::IDLE;
                // Mark Ctrl-C as fully sent when its transmission completes
                if (ctrl_c_in_flight) {
                    ctrl_c_sent = true;
                    ctrl_c_in_flight = false;
                }
            }
            return rx_line_value;
        }
        return true;
    }
};

class Memory
{
    std::vector<uint32_t> mem;

public:
    explicit Memory(size_t size) : mem(size, 0) {}

    inline uint32_t read(uint32_t addr) const
    {
        addr >>= 2;
        return (addr < mem.size()) ? mem[addr] : 0;
    }

    void load(const char *filename, size_t base = 0x1000)
    {
        std::ifstream f(filename, std::ios::binary | std::ios::ate);
        if (!f)
            throw std::runtime_error(std::string("Cannot open ") + filename);
        auto pos = f.tellg();
        if (pos < 0)
            throw std::runtime_error(std::string("Cannot determine size: ") +
                                     filename);
        size_t size = static_cast<size_t>(pos);
        if (base + size > mem.size() * 4)
            throw std::runtime_error(std::string("File too large: ") +
                                     filename);
        f.seekg(0);
        f.read(reinterpret_cast<char *>(&mem[base >> 2]), size);
        if (!f)
            throw std::runtime_error(std::string("Read error: ") + filename);
    }

    inline void write(uint32_t addr, uint32_t val, uint8_t strobe)
    {
        addr >>= 2;
        if (addr >= mem.size())
            return;
        uint32_t mask =
            ((strobe & 1) ? 0x000000FF : 0) | ((strobe & 2) ? 0x0000FF00 : 0) |
            ((strobe & 4) ? 0x00FF0000 : 0) | ((strobe & 8) ? 0xFF000000 : 0);
        mem[addr] = (mem[addr] & ~mask) | (val & mask);
    }
};

int main(int argc, char **argv)
{
    Verilated::commandArgs(argc, argv);

    const char *binary = nullptr;
    
    bool interactive_mode = false;
    bool sdl_audio_enabled = true;  // Enable SDL audio by default
    for (int i = 1; i < argc; i++) {
        if ((!strcmp(argv[i], "-instruction") || !strcmp(argv[i], "-i")) &&
            i + 1 < argc)
            binary = argv[++i];
        else if (!strcmp(argv[i], "--terminal") || !strcmp(argv[i], "-t"))
            interactive_mode = true;
        else if (!strcmp(argv[i], "--audio") || !strcmp(argv[i], "-a"))
            sdl_audio_enabled = true;
    }

    auto top = std::make_unique<VTop>();
    Memory mem(4 * 1024 * 1024);  // 4MB (stack starts at 0x400000)

    if (!binary) {
        std::cerr
            << "Usage: " << argv[0]
            << " -i <binary.asmbin> [--headless|-H] [--terminal|-t] [--audio|-a]\n"
            << "  --headless: Skip VGA display\n"
            << "  --terminal: Interactive UART terminal (Ctrl-C to exit)\n"
            << "  --audio: Enable SDL audio output\n";
        return 1;
    }
    try {
        mem.load(binary);
        std::cout << "Loaded: " << binary << "\n";
    } catch (const std::exception &e) {
        std::cerr << e.what() << "\n";
        return 1;
    }

    // Audio MMIO support (samples collected to audio_fifo, saved as WAV on exit)
    std::cout << "🎵 Audio MMIO enabled (11 kHz, mono, 16-bit)\n";
    std::cout << "   Audio MMIO: 0x60000000 (ID), 0x60000004 (STATUS), 0x60000008 (DATA)\n";
    std::cout << "   Audio will be saved to output.wav on exit\n";

    SdlAudioOut sdl_audio;
    if (sdl_audio_enabled) {
        if (sdl_audio.init())
            std::cout << "🔊 SDL audio output enabled\n";
        else
            std::cout << "⚠️  SDL audio output disabled (init failed)\n";
    }
    
    // UART terminal for interactive mode
    UartTerminal uart;
    bool uart_debug = getenv("UART_DEBUG") != nullptr;
    if (interactive_mode) {
        // Disable stdout buffering for immediate character output
        setvbuf(stdout, NULL, _IONBF, 0);
        std::cout << "Interactive UART terminal mode (Ctrl-C to exit)\n";
        std::cout << "Type characters to send to MyCPU via UART\n";
        std::cout << "----------------------------------------\n";
        std::cout.flush();
        uart.enable_raw_mode();
    }

    // Interactive terminal mode: no cycle limit (user exits with Ctrl-C)
    // Batch mode: 500M cycles to prevent runaway simulations
    const uint64_t max_cycles = interactive_mode ? UINT64_MAX : 500000000;
    uint64_t cycle = 0, last_report = 0;
    
    // Auto-exit detection: if PC is stuck in a small loop for too long, exit
    uint32_t stuck_pc_base = 0xFFFFFFFF;  // Track base address of stuck region
    uint64_t stuck_cycles = 0;
    const uint64_t STUCK_PC_THRESHOLD = 5000000000;  // 50M cycles (enough for audio output)
    const uint32_t STUCK_PC_RANGE = 16;  // Allow PC to vary within 16 bytes (small loop)

    // Early exit tracking for terminal mode (Ctrl-C detection)
    uint64_t tx_idle_cycles = 0;  // Count cycles of TX idle after Ctrl-C
    // After Ctrl-C is sent, wait for TX to be idle for this many cycles
    // This ensures "Goodbye!" message completes before exit
    // ~50K cycles = ~10 char times of idle = clearly done transmitting
    const uint64_t TX_IDLE_EXIT_THRESHOLD = 50000;

    // Reset sequence
    top->reset = 1;
    top->clock = 0;
    for (int i = 0; i < 5; i++) {
        top->clock = !top->clock;
        top->eval();
    }
    top->reset = 0;

    // Initialize inputs
    top->io_signal_interrupt = 0;
    top->io_instruction_valid = 1;
    top->io_mem_slave_read_valid = 0;
    top->io_mem_slave_read_data = 0;
    top->io_uart_rxd = 1;
    top->io_cpu_debug_read_address = 0;
    top->io_cpu_csr_debug_read_address = 0;

    uint32_t inst = mem.read(0x1000);

    std::cout << "🔧 DEBUG: Stuck PC detection enabled (threshold=" << STUCK_PC_THRESHOLD << " cycles)\n";
    std::cerr << "⚠️  STDERR TEST: If you see this, stderr is working!\n";
    std::cerr.flush();
    
    while (cycle < max_cycles && !Verilated::gotFinish()) {
        // Capture current clock state before toggle
        bool prev_clock = top->clock;
        
        // Progress report every 10M cycles (suppress in terminal mode)
        if (!interactive_mode && cycle - last_report >= 10000000) {
            std::cout << "[" << cycle / 1000000 << "M] PC=0x"
            << std::hex << top->io_instruction_address 
            << " (stuck:" << std::dec << stuck_cycles << ")" << "\n";

            last_report = cycle;
        }
        
        top->io_instruction = inst;
        top->clock = !top->clock;

        // Single authoritative eval() after clock toggle.
        // This creates a stable snapshot of all DUT outputs for this clock
        // edge.
        top->eval();
        
        // Auto-exit detection: check if PC is stuck in small loop (e.g., _exit)
        // Check on every iteration when clock is high
        if (top->clock) {
            uint32_t current_pc = top->io_instruction_address;
            
            // Check if PC is within STUCK_PC_RANGE of the base address
            // Use absolute difference to handle small loops that cross alignment boundaries
            bool in_stuck_region = (stuck_pc_base != 0xFFFFFFFF) && 
                                   (current_pc >= stuck_pc_base - STUCK_PC_RANGE) &&
                                   (current_pc <= stuck_pc_base + STUCK_PC_RANGE);
            
            if (in_stuck_region) {
                // PC is still in the stuck region, increment counter
                stuck_cycles++;
                if (stuck_cycles >= STUCK_PC_THRESHOLD) {
                    std::cout << "\n⚠️  PC stuck around 0x" << std::hex 
                              << stuck_pc_base << std::dec 
                              << " for " << stuck_cycles 
                              << " cycles. Auto-exiting...\n";
                    break;
                }
            } else {
                // PC moved to a new region, reset tracking
                stuck_pc_base = current_pc;  // Use actual PC, not aligned
                stuck_cycles = 1;
            }
        }

        // =====================================================================
        // CAPTURE PHASE: Snapshot all DUT outputs immediately after eval().
        // This implements the "Capture and Defer" pattern recommended for
        // Verilator testbenches to avoid race conditions between multiple
        // eval() calls within a single clock phase.
        // =====================================================================

        // Capture memory interface signals (immune to later state changes)
        bool mem_read_req = top->io_mem_slave_read;
        bool mem_write_req = top->io_mem_slave_write;
        uint32_t mem_address = top->io_mem_slave_address;
        uint32_t mem_write_data = top->io_mem_slave_write_data;
        uint8_t mem_write_strobe = (top->io_mem_slave_write_strobe_0) |
                                   (top->io_mem_slave_write_strobe_1 << 1) |
                                   (top->io_mem_slave_write_strobe_2 << 2) |
                                   (top->io_mem_slave_write_strobe_3 << 3);
        
        // Capture audio output signals
        bool audio_sample_valid = top->io_audio_sample_valid;
        int16_t audio_sample = (int16_t)top->io_audio_sample;


        // Capture UART TX line for serial output
        bool uart_txd = top->io_uart_txd;

        // =====================================================================
        // REACTION PHASE: Act on captured state. Order no longer matters.
        // ====================================================================
        // Memory handling using captured signals (immune to VGA eval effects)
                // MEMORY READ HANDLING
        if (top->clock && mem_read_req) {
            if ((mem_address & 0xFFF00000) == AUDIO_BASE) {
                uint32_t offset = mem_address & 0xFF;
                if (offset == 0x00) {  // ID register
                    top->io_mem_slave_read_data = 0x41554449;  // 'AUDI'
                } else if (offset == 0x04) {  // STATUS register
                    // Simulate FIFO status based on audio_fifo size
                    bool fifo_empty = audio_fifo.empty();
                    bool fifo_full = audio_fifo.size() >= 8;
                    top->io_mem_slave_read_data = (fifo_full << 1) | fifo_empty;
                } else {
                    // Other registers return 0
                    top->io_mem_slave_read_data = 0;
                }
                top->io_mem_slave_read_valid = 1;
            } else {
                // Regular memory read
                top->io_mem_slave_read_data = mem.read(mem_address);
                top->io_mem_slave_read_valid = 1;
            }
        }

        // AUDIO OUTPUT HANDLING (capture samples from audio peripheral)
        static size_t audio_sample_count = 0;
        
        if (top->clock && audio_sample_valid) {
            audio_sample_count++;
            // Push to FIFO if not full (max 16384 samples)
            if (audio_fifo.size() < 16384) {
                audio_fifo.push_back(audio_sample);
                sdl_audio.push(audio_sample);
                // Debug: print first few and periodic samples
                if (audio_fifo.size() <= 5 || audio_fifo.size() % 1000 == 0) {
                    std::cerr << "🎵 Audio sample #" << audio_sample_count 
                             << " (FIFO #" << audio_fifo.size() << "): value=" << audio_sample << "\n";
                    std::cerr.flush();
                }
            }
        }
        
        // MEMORY WRITE HANDLING (RAM only via io_mem_slave)
        if (top->clock && mem_write_req) {
                mem.write(mem_address, mem_write_data, mem_write_strobe);
        }
        // UART handling: TX always processed, RX depends on mode
        // Uses captured uart_txd signal for consistent state
        if (top->clock) {
            // TX: deserialize CPU output to stdout (both interactive and
            // loopback) - use captured uart_txd
            uart.set_debug(uart_debug, cycle);
            uart.process_tx(uart_txd);

            if (interactive_mode) {
                // Poll stdin every 64 CPU cycles for responsive input
                // Note: cycle increments every iteration, so 128 iterations =
                // 64 CPU cycles We check (cycle >> 1) to get CPU cycle count,
                // then mask with 0x3F
                if (!((cycle >> 1) & 0x3F)) {
                    uart.poll_input();
                }
                // Advance RX state machine and get line value (only on rising
                // edge)
                uart.get_rx_line();

                // Track TX idle time after Ctrl-C was sent to CPU
                // This ensures we wait for "Goodbye!" to finish transmitting
                if (uart.sent_ctrl_c()) {
                    if (uart.tx_is_idle()) {
                        tx_idle_cycles++;
                    } else {
                        tx_idle_cycles = 0;  // Reset if TX becomes active
                    }
                }
            }
        }

        // =====================================================================
        // DRIVE PHASE: Set DUT inputs for next cycle
        // =====================================================================

        // RX input handling
        if (interactive_mode) {
            // Use UART terminal RX line
            top->io_uart_rxd = uart.current_rx_line();

            // Early exit when TX has been idle for a while after Ctrl-C
            // This means "Goodbye!" message has finished transmitting
            if (uart.sent_ctrl_c() && tx_idle_cycles > TX_IDLE_EXIT_THRESHOLD) {
                break;
            }
        } else {
            // Loopback mode: connect TX output to RX input for self-test
            // Use captured uart_txd for consistent loopback
            top->io_uart_rxd = uart_txd;
        }

        // Final eval() to propagate input changes (RXD, memory responses)
        // before the next clock edge. This settles combinational logic.
        top->eval();
        inst = mem.read(top->io_instruction_address);
        cycle++;
    }

    // Restore terminal settings before summary (fixes \n handling)
    uart.disable_raw_mode();

    // Summary output
    std::cout << "\nDone: " << cycle << " cycles";
    std::cout << "\nFinal PC: 0x" << std::hex << top->io_instruction_address
              << std::dec << "\n";

    // Debug: Print audio FIFO status
    std::cout << "🔊 Audio FIFO size: " << audio_fifo.size() << " samples\n";
    std::cout.flush();

    // Save audio_fifo to WAV file if any samples were captured
    if (!audio_fifo.empty()) {
        const char* wav_filename = "output.wav";
        std::ofstream wav_file(wav_filename, std::ios::binary);
        
        if (wav_file) {
            // Convert deque to vector for easier handling
            std::vector<int16_t> samples(audio_fifo.begin(), audio_fifo.end());
            
            // Prepare WAV header
            WavHeader header;
            header.data_size = samples.size() * sizeof(int16_t);
            header.file_size = 36 + header.data_size;  // 44 - 8
            header.byte_rate = SAMPLE_RATE * 1 * 16 / 8;  // SampleRate * Channels * BitsPerSample/8
            header.block_align = 1 * 16 / 8;  // Channels * BitsPerSample/8
            
            // Write WAV file
            wav_file.write(reinterpret_cast<const char*>(&header), sizeof(WavHeader));
            wav_file.write(reinterpret_cast<const char*>(samples.data()), header.data_size);
            wav_file.close();
            
            std::cout << "💾 Saved " << samples.size() << " samples to " << wav_filename << "\n";
            std::cout << "   Duration: " << (samples.size() / (float)SAMPLE_RATE) << " seconds\n";
            std::cout << "   Play with: aplay " << wav_filename << " or copy to Windows and double-click\n";
        } else {
            std::cerr << "⚠️  Failed to create WAV file\n";
        }
    }

    sdl_audio.shutdown();

    // Print VGA color diagnostics (only if VGA was used)

    return 0;
}
