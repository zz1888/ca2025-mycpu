CROSS_COMPILE ?= $(HOME)/rv/toolchain/bin/riscv-none-elf-

ASFLAGS = -march=rv32im_zicsr -mabi=ilp32
# Optimization required: -O2 produces large stack frames that overflow in deep call chains
# RV32I lacks hardware multiply/divide, so -O2 significantly reduces code size
CFLAGS = -O2 -Wall -fno-builtin -march=rv32im_zicsr -mabi=ilp32
LDFLAGS = --oformat=elf32-littleriscv

AS := $(CROSS_COMPILE)as
CC := $(CROSS_COMPILE)gcc
LD := $(CROSS_COMPILE)ld
OBJCOPY := $(CROSS_COMPILE)objcopy -O binary -j .text -j .data -j .rodata -j .sdata

# Program targets (add new programs here)
PROGRAMS := nyancat uart shell driver profile_min test-simple test-mul test-mul-direct test-mul-simple test-mul-debug test-mul-raw test-dsp test-dsp-simple test-q15-mul test-performance test-performance-simple test-perf-core test-env-debug test-process-debug test-audio test-picosynth-music test-picosynth-simple test-picosynth-minimal test-picosynth-minimal-v2 test-picosynth-debug test-picosynth-manual test-synth-bypass test-malloc test-array synth-optimized synth-simple synth-full synth-adsr example test-div test-div-simple test-process-single test-wave-only test-osc-only test-env-osc test-env-api test-env-manual test-env-simple synth-simple-v2 test-hwsynth test-hwsynth-audio test-hwsynth-full test-hwsynth-sync test-hwsynth-debug test-hwsynth-minimal picosynth-hw
BINARIES := $(PROGRAMS:%=%.asmbin)

%.asmbin: %.elf
	$(OBJCOPY) -O binary -j .text -j .data $< $@

.DEFAULT_GOAL := all

all: $(BINARIES)

# All programs use proper init.S with ABI compliance and .bss clearing
nyancat.asmbin: nyancat.c nyancat-data.h init.o link.lds
	$(CC) $(CFLAGS) -DNYANCAT_COMPRESSION_DELTA=$(NYANCAT_COMPRESSION_DELTA) -c -o nyancat.o nyancat.c
	$(CROSS_COMPILE)ld -o nyancat.elf -T link.lds $(LDFLAGS) nyancat.o init.o
	$(OBJCOPY) -O binary -j .text -j .data nyancat.elf $@

uart.asmbin: uart.c mmio.h init.o link.lds
	$(CC) $(CFLAGS) -c -o uart.o uart.c
	$(CROSS_COMPILE)ld -o uart.elf -T link.lds $(LDFLAGS) uart.o init.o
	$(OBJCOPY) -O binary -j .text -j .data uart.elf $@

shell.asmbin: shell.c mmio.h init.o link.lds
	$(CC) $(CFLAGS) -c -o shell.o shell.c
	$(CROSS_COMPILE)ld -o shell.elf -T link.lds $(LDFLAGS) shell.o init.o
	$(OBJCOPY) -O binary -j .text -j .data shell.elf $@

# picosynth.asmbin:  picosynth.c mmio.h init.o link.lds
#	$(CC) $(CFLAGS) -c -o picosynth.o picosynth.c
#	$(CC) -o picosynth.elf -T link.lds -march=rv32im_zicsr -mabi=ilp32 -nostartfiles picosynth.o init.o
#	$(OBJCOPY) -O binary -j .text -j .data picosynth.elf $@
# Shell as library (without main) for driver
uart-lib.o: uart.c mmio.h
	$(CC) $(CFLAGS) -DUART_AS_LIBRARY -c -o $@ uart.c
shell-lib.o: shell.c mmio.h
	$(CC) $(CFLAGS) -DSHELL_AS_LIBRARY -c -o $@ shell.c

# PicoSynth driver with all dependencies
driver.o: driver.c test.h mmio.h
	$(CC) $(CFLAGS) -c -o $@ driver.c


# Test suites
test-q15.o: test-q15.c test.h picosynth.h
	$(CC) $(CFLAGS) -c -o $@ test-q15.c

test-waveform.o: test-waveform.c test.h picosynth.h
	$(CC) $(CFLAGS) -c -o $@ test-waveform.c

test-envelope.o: test-envelope.c test.h picosynth.h
	$(CC) $(CFLAGS) -c -o $@ test-envelope.c

test-synth.o: test-synth.c test.h picosynth.h
	$(CC) $(CFLAGS) -c -o $@ test-synth.c

test-midi.o: test-midi.c test.h midifile.h
	$(CC) $(CFLAGS) -c -o $@ test-midi.c

# PicoSynth core
picosynth.o: picosynth.c picosynth.h dsp-math.h
	$(CC) $(CFLAGS) -c -o $@ picosynth.c
driver.asmbin: driver.o picosynth.o midifile.o test-q15.o test-waveform.o test-envelope.o test-synth.o test-midi.o uart-lib.o shell-lib.o init.o link.lds
	$(CC) -o driver.elf -T link.lds -nostartfiles -march=rv32im_zicsr -mabi=ilp32 \
		driver.o picosynth.o midifile.o test-q15.o test-waveform.o test-envelope.o test-synth.o test-midi.o \
		uart-lib.o shell-lib.o init.o \
		
	$(OBJCOPY) -O binary -j .text -j .data driver.elf $@

# Profile_min - standalone synthesizer with correct print functions (no division)
profile_min.asmbin: profile_min.c mmio.h init.o link.lds
	$(CC) $(CFLAGS) -c -o profile_min.o profile_min.c
	$(CC) -o profile_min.elf -T link.lds -nostartfiles -march=rv32im_zicsr -mabi=ilp32 \
		profile_min.o init.o \
		
	$(OBJCOPY) -O binary -j .text -j .data profile_min.elf $@

# Test-simple - minimal diagnostic test (no complex dependencies)
test-simple.asmbin: test-simple.c mmio.h uart-lib.o init.o link.lds
	$(CC) $(CFLAGS) -c -o test-simple.o test-simple.c
	$(CC) -o test-simple.elf -T link.lds -nostartfiles -march=rv32im_zicsr -mabi=ilp32 \
		test-simple.o uart-lib.o init.o \
		
	$(OBJCOPY) -O binary -j .text -j .data test-simple.elf $@

# Test-mul - M-extension multiplication test
test-mul.asmbin: test-mul.c mini_libc.h init.o link.lds
	$(CC) $(CFLAGS) -c -o test-mul.o test-mul.c
	$(CC) -o test-mul.elf -T link.lds -nostartfiles -march=rv32im_zicsr -mabi=ilp32 \
		test-mul.o init.o \
		
	$(OBJCOPY) -O binary -j .text -j .data test-mul.elf $@

# Test-mul-direct - Direct M-extension hardware test using inline assembly
test-mul-direct.asmbin: test-mul-direct.c mini_libc.h init.o link.lds
	$(CC) $(CFLAGS) -c -o test-mul-direct.o test-mul-direct.c
	$(CC) -o test-mul-direct.elf -T link.lds -nostartfiles -march=rv32im_zicsr -mabi=ilp32 \
		test-mul-direct.o init.o \
		
	$(OBJCOPY) -O binary -j .text -j .data test-mul-direct.elf $@

# Test-mul-simple - Simplest M-extension test
test-mul-simple.asmbin: test-mul-simple.c mini_libc.h init.o link.lds
	$(CC) $(CFLAGS) -c -o test-mul-simple.o test-mul-simple.c
	$(CC) -o test-mul-simple.elf -T link.lds -nostartfiles -march=rv32im_zicsr -mabi=ilp32 \
		test-mul-simple.o init.o \
		
	$(OBJCOPY) -O binary -j .text -j .data test-mul-simple.elf $@

# Test-mul-debug - Debug M-extension with large numbers
test-mul-debug.asmbin: test-mul-debug.c mini_libc.h init.o link.lds
	$(CC) $(CFLAGS) -c -o test-mul-debug.o test-mul-debug.c
	$(CC) -o test-mul-debug.elf -T link.lds -nostartfiles -march=rv32im_zicsr -mabi=ilp32 \
		test-mul-debug.o init.o \
		
	$(OBJCOPY) -O binary -j .text -j .data test-mul-debug.elf $@

# Test-mul-raw - Test RAW hazards with MUL
test-mul-raw.asmbin: test-mul-raw.c mini_libc.h init.o link.lds
	$(CC) $(CFLAGS) -c -o test-mul-raw.o test-mul-raw.c
	$(CC) -o test-mul-raw.elf -T link.lds -nostartfiles -march=rv32im_zicsr -mabi=ilp32 \
		test-mul-raw.o init.o \
		
	$(OBJCOPY) -O binary -j .text -j .data test-mul-raw.elf $@

# Test-dsp - DSP custom instructions test (QMUL16, SADD16, SSUB16)
test-dsp.asmbin: test-dsp.c mini_libc.h init.o link.lds
	$(CC) $(CFLAGS) -c -o test-dsp.o test-dsp.c
	$(CC) -o test-dsp.elf -T link.lds -nostartfiles -march=rv32im_zicsr -mabi=ilp32 \
		test-dsp.o init.o \
		
	$(OBJCOPY) -O binary -j .text -j .data test-dsp.elf $@

# Test-dsp-simple - Simplified DSP test (hex output only, no division)
test-dsp-simple.asmbin: test-dsp-simple.c mini_libc.h init.o link.lds
	$(CC) $(CFLAGS) -c -o test-dsp-simple.o test-dsp-simple.c
	$(CC) -o test-dsp-simple.elf -T link.lds -nostartfiles -march=rv32im_zicsr -mabi=ilp32 \
		test-dsp-simple.o init.o \
		
	$(OBJCOPY) -O binary -j .text -j .data test-dsp-simple.elf $@

# Test-q15-mul - Test optimized q15_mul() using QMUL16 instruction
test-q15-mul.asmbin: test-q15-mul.c picosynth.c picosynth.h mmio.h init.o link.lds
	$(CC) $(CFLAGS) -c -o test-q15-mul.o test-q15-mul.c
	$(CC) $(CFLAGS) -c -o picosynth.o picosynth.c
	$(CC) -o test-q15-mul.elf -T link.lds -nostartfiles -march=rv32im_zicsr -mabi=ilp32 \
		test-q15-mul.o picosynth.o init.o \
		
	$(OBJCOPY) -O binary -j .text -j .data test-q15-mul.elf $@

# Test-performance - Performance benchmark for optimized picosynth
test-performance.asmbin: test-performance.c picosynth.c picosynth.h dsp-math.h mmio.h init.o link.lds
	$(CC) $(CFLAGS) -c -o test-performance.o test-performance.c
	$(CC) $(CFLAGS) -c -o picosynth.o picosynth.c
	$(CC) -o test-performance.elf -T link.lds -nostartfiles -march=rv32im_zicsr -mabi=ilp32 \
		test-performance.o picosynth.o init.o \
		
	$(OBJCOPY) -O binary -j .text -j .data test-performance.elf $@

# Test-performance-simple - Simplified performance test
test-performance-simple.asmbin: test-performance-simple.c picosynth.c picosynth.h dsp-math.h mmio.h init.o link.lds
	$(CC) $(CFLAGS) -c -o test-performance-simple.o test-performance-simple.c
	$(CC) $(CFLAGS) -c -o picosynth.o picosynth.c
	$(CROSS_COMPILE)ld -o test-performance-simple.elf -T link.lds $(LDFLAGS) test-performance-simple.o picosynth.o init.o
	$(OBJCOPY) -O binary -j .text -j .data test-performance-simple.elf $@

# Example piano synth (audio FIFO output, no -lgcc)
example.asmbin: example.c picosynth.c picosynth.h dsp-math.h mmio.h init.o link.lds
	$(CC) $(CFLAGS) -c -o example.o example.c
	$(CC) $(CFLAGS) -c -o picosynth.o picosynth.c
	$(CROSS_COMPILE)ld -o example.elf -T link.lds $(LDFLAGS) example.o picosynth.o init.o
	$(OBJCOPY) -O binary -j .text -j .data -j .rodata example.elf $@
# Test-perf-core - Core DSP performance test (no complex initialization)
test-perf-core.asmbin: test-perf-core.c picosynth.c picosynth.h mmio.h init.o link.lds
	$(CC) $(CFLAGS) -c -o test-perf-core.o test-perf-core.c
	$(CC) $(CFLAGS) -c -o picosynth.o picosynth.c
	$(CC) -o test-perf-core.elf -T link.lds -nostartfiles -march=rv32im_zicsr -mabi=ilp32 \
		test-perf-core.o picosynth.o init.o \
		
	$(OBJCOPY) -O binary -j .text -j .data test-perf-core.elf $@

test-env-debug.asmbin: test-env-debug.c picosynth.c picosynth.h mmio.h init.o link.lds
	$(CC) $(CFLAGS) -c -o test-env-debug.o test-env-debug.c
	$(CC) $(CFLAGS) -c -o picosynth-debug.o picosynth.c
	$(CC) -o test-env-debug.elf -T link.lds -nostartfiles -march=rv32im_zicsr -mabi=ilp32 \
		test-env-debug.o picosynth-debug.o init.o \
		
	$(OBJCOPY) -O binary -j .text -j .data test-env-debug.elf $@

test-process-debug.asmbin: test-process-debug.c picosynth.c picosynth.h mmio.h init.o link.lds
	$(CC) $(CFLAGS) -c -o test-process-debug.o test-process-debug.c
	$(CC) $(CFLAGS) -c -o picosynth-proc.o picosynth.c
	$(CC) -o test-process-debug.elf -T link.lds -nostartfiles -march=rv32im_zicsr -mabi=ilp32 \
		test-process-debug.o picosynth-proc.o init.o \
		
	$(OBJCOPY) -O binary -j .text -j .data test-process-debug.elf $@

test-process-minimal.asmbin: test-process-minimal.c picosynth.c picosynth.h mmio.h init.o link.lds
	$(CC) $(CFLAGS) -c -o test-process-minimal.o test-process-minimal.c
	$(CC) $(CFLAGS) -c -o picosynth-min.o picosynth.c
	$(CC) -o test-process-minimal.elf -T link.lds -nostartfiles -march=rv32im_zicsr -mabi=ilp32 \
		test-process-minimal.o picosynth-min.o init.o \
		
	$(OBJCOPY) -O binary -j .text -j .data test-process-minimal.elf $@

test-audio.asmbin: test-audio.c mmio.h init.o link.lds
	$(CC) $(CFLAGS) -c -o test-audio.o test-audio.c
	$(CC) -o test-audio.elf -T link.lds -nostartfiles -march=rv32im_zicsr -mabi=ilp32 \
		test-audio.o init.o \
		
	$(OBJCOPY) -O binary -j .text -j .data test-audio.elf $@

test-picosynth-music.asmbin: test-picosynth-music.c picosynth.c picosynth.h mmio.h init.o link.lds
	$(CC) $(CFLAGS) -c -o test-picosynth-music.o test-picosynth-music.c
	$(CC) $(CFLAGS) -c -o picosynth-music.o picosynth.c
	$(CC) -o test-picosynth-music.elf -T link.lds -nostartfiles -march=rv32im_zicsr -mabi=ilp32 \
		test-picosynth-music.o picosynth-music.o init.o \
		
	$(OBJCOPY) -O binary -j .text -j .data test-picosynth-music.elf $@

test-picosynth-simple.asmbin: test-picosynth-simple.c picosynth.c picosynth.h mmio.h init.o link.lds
	$(CC) $(CFLAGS) -c -o test-picosynth-simple.o test-picosynth-simple.c
	$(CC) $(CFLAGS) -c -o picosynth-simple.o picosynth.c
	$(CC) -o test-picosynth-simple.elf -T link.lds -nostartfiles -march=rv32im_zicsr -mabi=ilp32 \
		test-picosynth-simple.o picosynth-simple.o init.o \
		
	$(OBJCOPY) -O binary -j .text -j .data test-picosynth-simple.elf $@

test-picosynth-minimal.asmbin: test-picosynth-minimal.c picosynth.c picosynth.h mmio.h init.o link.lds
	$(CC) $(CFLAGS) -c -o test-picosynth-minimal.o test-picosynth-minimal.c
	$(CC) $(CFLAGS) -c -o picosynth-minimal.o picosynth.c
	$(CC) -o test-picosynth-minimal.elf -T link.lds -nostartfiles -march=rv32im_zicsr -mabi=ilp32 \
		test-picosynth-minimal.o picosynth-minimal.o init.o \
		
	$(OBJCOPY) -O binary -j .text -j .data test-picosynth-minimal.elf $@

test-picosynth-minimal-v2.asmbin: test-picosynth-minimal-v2.c picosynth.c picosynth.h mmio.h init.o link.lds
	$(CC) $(CFLAGS) -c -o test-picosynth-minimal-v2.o test-picosynth-minimal-v2.c
	$(CC) $(CFLAGS) -c -o picosynth-minimal-v2.o picosynth.c
	$(CC) -o test-picosynth-minimal-v2.elf -T link.lds -nostartfiles -march=rv32im_zicsr -mabi=ilp32 \
		test-picosynth-minimal-v2.o picosynth-minimal-v2.o init.o \
		
	$(OBJCOPY) -O binary -j .text -j .data test-picosynth-minimal-v2.elf $@

test-synth-bypass.asmbin: test-synth-bypass.c mmio.h init.o link.lds
	$(CC) $(CFLAGS) -c -o test-synth-bypass.o test-synth-bypass.c
	$(CC) -o test-synth-bypass.elf -T link.lds -nostartfiles -march=rv32im_zicsr -mabi=ilp32 \
		test-synth-bypass.o init.o \
		
	$(OBJCOPY) -O binary -j .text -j .data test-synth-bypass.elf $@

test-picosynth-debug.asmbin: test-picosynth-debug.c picosynth.c picosynth.h mmio.h init.o link.lds
	$(CC) $(CFLAGS) -c -o test-picosynth-debug.o test-picosynth-debug.c
	$(CC) $(CFLAGS) -c -o picosynth-debug2.o picosynth.c
	$(CC) -o test-picosynth-debug.elf -T link.lds -nostartfiles -march=rv32im_zicsr -mabi=ilp32 \
		test-picosynth-debug.o picosynth-debug2.o init.o \
		
	$(OBJCOPY) -O binary -j .text -j .data test-picosynth-debug.elf $@

test-picosynth-manual.asmbin: test-picosynth-manual.c picosynth.c picosynth.h mmio.h init.o link.lds
	$(CC) $(CFLAGS) -c -o test-picosynth-manual.o test-picosynth-manual.c
	$(CC) $(CFLAGS) -c -o picosynth-manual.o picosynth.c
	$(CC) -o test-picosynth-manual.elf -T link.lds -nostartfiles -march=rv32im_zicsr -mabi=ilp32 \
		test-picosynth-manual.o picosynth-manual.o init.o \
		
	$(OBJCOPY) -O binary -j .text -j .data test-picosynth-manual.elf $@

test-wave-only.asmbin: test-wave-only.c picosynth.c picosynth.h mmio.h init.o link.lds
	$(CC) $(CFLAGS) -c -o test-wave-only.o test-wave-only.c
	$(CC) $(CFLAGS) -c -o picosynth-wave.o picosynth.c
	$(CC) -o test-wave-only.elf -T link.lds -nostartfiles -march=rv32im_zicsr -mabi=ilp32 \
		test-wave-only.o picosynth-wave.o init.o \
		
	$(OBJCOPY) -O binary -j .text -j .data test-wave-only.elf $@

test-malloc.asmbin: test-malloc.c picosynth.c mmio.h init.o link.lds
	$(CC) $(CFLAGS) -c -o test-malloc.o test-malloc.c
	$(CC) $(CFLAGS) -c -o picosynth-malloc.o picosynth.c
	$(CC) -o test-malloc.elf -T link.lds -nostartfiles -march=rv32im_zicsr -mabi=ilp32 \
		test-malloc.o picosynth-malloc.o init.o \
		
	$(OBJCOPY) -O binary -j .text -j .data test-malloc.elf $@

test-array.asmbin: test-array.c picosynth.c mmio.h init.o link.lds
	$(CC) $(CFLAGS) -c -o test-array.o test-array.c
	$(CC) $(CFLAGS) -c -o picosynth-array.o picosynth.c
	$(CC) -o test-array.elf -T link.lds -nostartfiles -march=rv32im_zicsr -mabi=ilp32 \
		test-array.o picosynth-array.o init.o \
		
	$(OBJCOPY) -O binary -j .text -j .data test-array.elf $@

synth-optimized.asmbin: synth-optimized.c mmio.h init.o link.lds
	$(CC) $(CFLAGS) -c -o synth-optimized.o synth-optimized.c
	$(CC) -o synth-optimized.elf -T link.lds -nostartfiles -march=rv32im_zicsr -mabi=ilp32 \
		synth-optimized.o init.o \
		
	$(OBJCOPY) -O binary -j .text -j .data synth-optimized.elf $@

synth-simple.asmbin: synth-simple.c mmio.h init.o link.lds
	$(CC) $(CFLAGS) -c -o synth-simple.o synth-simple.c
	$(CC) -o synth-simple.elf -T link.lds -nostartfiles -march=rv32im_zicsr -mabi=ilp32 \
		synth-simple.o init.o \
		
	$(OBJCOPY) -O binary -j .text -j .data synth-simple.elf $@

synth-full.asmbin: synth-full.c mmio.h init.o link.lds
	$(CC) $(CFLAGS) -c -o synth-full.o synth-full.c
	$(CC) -o synth-full.elf -T link.lds -nostartfiles -march=rv32im_zicsr -mabi=ilp32 \
		synth-full.o init.o \
		
	$(OBJCOPY) -O binary -j .text -j .data synth-full.elf $@

synth-adsr.asmbin: synth-adsr.c mmio.h init.o link.lds
	$(CC) $(CFLAGS) -c -o synth-adsr.o synth-adsr.c
	$(CC) -o synth-adsr.elf -T link.lds -nostartfiles -march=rv32im_zicsr -mabi=ilp32 \
		synth-adsr.o init.o \
		
	$(OBJCOPY) -O binary -j .text -j .data synth-adsr.elf $@

test-div.asmbin: test-div.c mmio.h init.o link.lds
	$(CC) $(CFLAGS) -c -o test-div.o test-div.c
	$(CC) -o test-div.elf -T link.lds -nostartfiles -march=rv32im_zicsr -mabi=ilp32 \
		test-div.o init.o \
		
	$(OBJCOPY) -O binary -j .text -j .data test-div.elf $@

test-div-simple.asmbin: test-div-simple.c mmio.h init.o link.lds
	$(CC) $(CFLAGS) -c -o test-div-simple.o test-div-simple.c
	$(CC) -o test-div-simple.elf -T link.lds -nostartfiles -march=rv32im_zicsr -mabi=ilp32 \
		test-div-simple.o init.o \
		
	$(OBJCOPY) -O binary -j .text -j .data -j .sdata test-div-simple.elf $@

test-process-single.asmbin: test-process-single.c picosynth.c picosynth.h mmio.h init.o link.lds
	$(CC) $(CFLAGS) -c -o test-process-single.o test-process-single.c
	$(CC) $(CFLAGS) -c -o picosynth-single.o picosynth.c
	$(CC) -o test-process-single.elf -T link.lds -nostartfiles -march=rv32im_zicsr -mabi=ilp32 \
		test-process-single.o picosynth-single.o init.o \
		
	$(OBJCOPY) -O binary -j .text -j .data test-process-single.elf $@

test-osc-only.asmbin: test-osc-only.c picosynth.c picosynth.h mmio.h init.o link.lds
	$(CC) $(CFLAGS) -c -o test-osc-only.o test-osc-only.c
	$(CC) $(CFLAGS) -c -o picosynth-osc.o picosynth.c
	$(CC) -o test-osc-only.elf -T link.lds -nostartfiles -march=rv32im_zicsr -mabi=ilp32 \
		test-osc-only.o picosynth-osc.o init.o \
		
	$(OBJCOPY) -O binary -j .text -j .data test-osc-only.elf $@

test-env-osc.asmbin: test-env-osc.c picosynth.c picosynth.h mmio.h init.o link.lds
	$(CC) $(CFLAGS) -c -o test-env-osc.o test-env-osc.c
	$(CC) $(CFLAGS) -c -o picosynth-env-osc.o picosynth.c
	$(CC) -o test-env-osc.elf -T link.lds -nostartfiles -march=rv32im_zicsr -mabi=ilp32 \
		test-env-osc.o picosynth-env-osc.o init.o \
		
	$(OBJCOPY) -O binary -j .text -j .data test-env-osc.elf $@

test-env-api.asmbin: test-env-api.c picosynth.c picosynth.h mmio.h init.o link.lds
	$(CC) $(CFLAGS) -c -o test-env-api.o test-env-api.c
	$(CC) $(CFLAGS) -c -o picosynth-env-api.o picosynth.c
	$(CC) -o test-env-api.elf -T link.lds -nostartfiles -march=rv32im_zicsr -mabi=ilp32 \
		test-env-api.o picosynth-env-api.o init.o \
		
	$(OBJCOPY) -O binary -j .text -j .data test-env-api.elf $@

test-env-manual.asmbin: test-env-manual.c picosynth.c picosynth.h mmio.h init.o link.lds
	$(CC) $(CFLAGS) -c -o test-env-manual.o test-env-manual.c
	$(CC) $(CFLAGS) -c -o picosynth-env-manual.o picosynth.c
	$(CC) -o test-env-manual.elf -T link.lds -nostartfiles -march=rv32im_zicsr -mabi=ilp32 \
		test-env-manual.o picosynth-env-manual.o init.o \
		
	$(OBJCOPY) -O binary -j .text -j .data test-env-manual.elf $@

test-env-simple.asmbin: test-env-simple.c picosynth.c picosynth.h mmio.h init.o link.lds
	$(CC) $(CFLAGS) -c -o test-env-simple.o test-env-simple.c
	$(CC) $(CFLAGS) -c -o picosynth-env-simple.o picosynth.c
	$(CC) -o test-env-simple.elf -T link.lds -nostartfiles -march=rv32im_zicsr -mabi=ilp32 \
		test-env-simple.o picosynth-env-simple.o init.o \
		
	$(OBJCOPY) -O binary -j .text -j .data test-env-simple.elf $@

# Simplified synth v2 - standalone, no picosynth dependency
synth-simple-v2.asmbin: synth-simple-v2.c mmio.h init.o link.lds
	$(CC) $(CFLAGS) -c -o synth-simple-v2.o synth-simple-v2.c
	$(CC) -o synth-simple-v2.elf -T link.lds -nostartfiles -march=rv32im_zicsr -mabi=ilp32 \
		synth-simple-v2.o init.o \
		
	$(OBJCOPY) -O binary -j .text -j .data synth-simple-v2.elf $@

# Hardware synth test - uses HWSynth peripheral at 0x80000000
test-hwsynth.asmbin: test-hwsynth.c hwsynth.h mmio.h init.o link.lds
	$(CC) $(CFLAGS) -c -o test-hwsynth.o test-hwsynth.c
	$(CC) -o test-hwsynth.elf -T link.lds -nostartfiles -march=rv32im_zicsr -mabi=ilp32 \
		test-hwsynth.o init.o \
		
	$(OBJCOPY) -O binary -j .text -j .data test-hwsynth.elf $@

# Hardware synth audio test - HWSynth -> AudioPeripheral -> WAV output
test-hwsynth-audio.asmbin: test-hwsynth-audio.c hwsynth.h mmio.h init.o link.lds
	$(CC) $(CFLAGS) -c -o test-hwsynth-audio.o test-hwsynth-audio.c
	$(CC) -o test-hwsynth-audio.elf -T link.lds -nostartfiles -march=rv32im_zicsr -mabi=ilp32 \
		test-hwsynth-audio.o init.o \
		
	$(OBJCOPY) -O binary -j .text -j .data test-hwsynth-audio.elf $@

init.o: init.S
	$(AS) -R $(ASFLAGS) -o $@ $<

# Generate nyancat animation data from upstream source
# Configure compression mode via NYANCAT_COMPRESSION_DELTA (default: 1 for delta-RLE)
# - NYANCAT_COMPRESSION_DELTA=1: delta-RLE compression (91% reduction, 4.7KB)
# - NYANCAT_COMPRESSION_DELTA=0: baseline RLE (87% reduction, 6.7KB)
NYANCAT_COMPRESSION_DELTA ?= 1

nyancat-data.h: ../../scripts/gen-nyancat-data.py
ifeq ($(NYANCAT_COMPRESSION_DELTA),1)
	python3 $< --delta --output $@
else
	python3 $< --output $@
endif

# Nyancat build depends on generated data
nyancat.o: nyancat.c nyancat-data.h

update: $(BINARIES)
	cp -f $(BINARIES) ../src/main/resources
	@echo ""
	@echo "Updated $(BINARIES) to ../src/main/resources"

clean:
	$(RM) *.o *.elf *.asmbin init_minimal.S nyancat-data.h

# Convenience targets (prevent implicit rule interference)
$(PROGRAMS): %: %.asmbin

.PHONY: all update clean $(PROGRAMS)


# Complete hardware synth test - all features: SVF filter, AHDSR, waveforms, DC blocker
test-hwsynth-full.asmbin: test-hwsynth-full.c hwsynth.h mmio.h init.o link.lds
	$(CC) $(CFLAGS) -c -o test-hwsynth-full.o test-hwsynth-full.c
	$(CC) -o test-hwsynth-full.elf -T link.lds -nostartfiles -march=rv32im_zicsr -mabi=ilp32 		test-hwsynth-full.o init.o 		
	$(OBJCOPY) -O binary -j .text -j .data test-hwsynth-full.elf $@


# Hardware synth synchronized test - uses audio FIFO for timing
test-hwsynth-sync.asmbin: test-hwsynth-sync.c hwsynth.h mmio.h init.o link.lds
	$(CC) $(CFLAGS) -c -o test-hwsynth-sync.o test-hwsynth-sync.c
	$(CC) -o test-hwsynth-sync.elf -T link.lds -nostartfiles -march=rv32im_zicsr -mabi=ilp32 test-hwsynth-sync.o init.o 
	$(OBJCOPY) -O binary -j .text -j .data test-hwsynth-sync.elf $@

# Hardware synth debug test
test-hwsynth-debug.asmbin: test-hwsynth-debug.c hwsynth.h mmio.h init.o link.lds
	$(CC) $(CFLAGS) -c -o test-hwsynth-debug.o test-hwsynth-debug.c
	$(CC) -o test-hwsynth-debug.elf -T link.lds -nostartfiles -march=rv32im_zicsr -mabi=ilp32 test-hwsynth-debug.o init.o 
	$(OBJCOPY) -O binary -j .text -j .data test-hwsynth-debug.elf $@

# Hardware synth minimal test - basic functionality check
test-hwsynth-minimal.asmbin: test-hwsynth-minimal.c hwsynth.h mmio.h init.o link.lds
	$(CC) $(CFLAGS) -c -o test-hwsynth-minimal.o test-hwsynth-minimal.c
	$(CC) -o test-hwsynth-minimal.elf -T link.lds -nostartfiles -march=rv32im_zicsr -mabi=ilp32 test-hwsynth-minimal.o init.o 
	$(OBJCOPY) -O binary -j .text -j .data test-hwsynth-minimal.elf $@

# Hardware synth music demo - plays melody using HWSynth peripheral
picosynth-hw.asmbin: picosynth-hw.c hwsynth.h mmio.h init.o link.lds
	$(CC) $(CFLAGS) -c -o picosynth-hw.o picosynth-hw.c
	$(CC) -o picosynth-hw.elf -T link.lds -nostartfiles -march=rv32im_zicsr -mabi=ilp32 picosynth-hw.o init.o 
	$(OBJCOPY) -O binary -j .text -j .data -j .rodata picosynth-hw.elf $@
