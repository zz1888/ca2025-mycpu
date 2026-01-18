// SPDX-License-Identifier: MIT
// Hardware Synthesizer - Complete picosynth implementation in hardware
//
// This peripheral implements the full picosynth architecture in hardware:
//   - 4-voice polyphony
//   - Per-voice: Oscillator → Filter → Envelope → Output
//   - SVF Filter with LP/HP/BP modes and resonance
//   - AHDSR Envelope (Attack-Hold-Decay-Sustain-Release)
//   - 5 waveforms: Saw, Square, Triangle, Sine, Noise
//   - Envelope → Filter cutoff modulation
//   - DC Blocker on final output
//   - Q15 fixed-point arithmetic throughout
//
// Memory Map (Base: 0x80000000):
//   0x00: ID       - Peripheral ID (RO: 0x53594E54 = 'SYNT')
//   0x04: CTRL     - [0] enable, [7:4] voice_mask
//   0x08: STATUS   - [0] sample_ready, [7:4] active_voices
//   0x0C: SAMPLE   - Current mixed sample (RO, 16-bit signed)
//   0x10-0x2F: Voice 0 registers
//   0x30-0x4F: Voice 1 registers
//   0x50-0x6F: Voice 2 registers
//   0x70-0x8F: Voice 3 registers
//
// Voice registers (32 bytes per voice):
//   0x00: FREQ      - Phase increment (16-bit)
//   0x04: WAVE      - [2:0] waveform: 0=saw, 1=square, 2=tri, 3=sine, 4=noise
//   0x08: ENV_ADSR  - [7:0] attack, [15:8] hold, [23:16] decay, [31:24] release
//   0x0C: ENV_SUS   - [15:0] sustain level
//   0x10: FILTER    - [15:0] cutoff, [23:16] resonance(Q), [25:24] mode (0=LP,1=HP,2=BP)
//   0x14: MOD       - [15:0] env→filter amount (signed), [16] env→filter enable
//   0x18: GATE      - [0] gate, [1] trigger (auto-clear)
//   0x1C: reserved

package peripheral

import bus.AXI4LiteChannels
import bus.AXI4LiteSlave
import chisel3._
import chisel3.util._
import riscv.Parameters

// ============================================================================
// Sine lookup table (256 entries, Q15 format)
// ============================================================================
object SineLUT {
  // First quadrant only (0 to π/2), 64 entries
  // Full sine reconstructed by symmetry
  val table: Seq[Int] = (0 until 64).map { i =>
    val angle = i * math.Pi / 128.0
    (math.sin(angle) * 32767).toInt
  }
}

// ============================================================================
// SVF Filter (State Variable Filter)
// Implements LP/HP/BP with resonance control
// ============================================================================
class SVFFilter extends Module {
  val io = IO(new Bundle {
    val input = Input(SInt(16.W))
    val cutoff = Input(UInt(16.W))    // Filter cutoff frequency (0-32767)
    val resonance = Input(UInt(8.W))  // Q factor (0=none, 255=max)
    val mode = Input(UInt(2.W))       // 0=LP, 1=HP, 2=BP
    val output = Output(SInt(16.W))
    val tick = Input(Bool())
    val reset_state = Input(Bool())   // Reset filter state on trigger
  })

  // State variables
  val low = RegInit(0.S(32.W))   // Low-pass output (extra bits for precision)
  val band = RegInit(0.S(32.W))  // Band-pass output

  // Q15 multiply helper
  def q15mul(a: SInt, b: SInt): SInt = {
    val product = (a * b) >> 15
    product(15, 0).asSInt
  }

  // Convert cutoff to filter coefficient (f = cutoff / 32768)
  // Approximation: f ≈ 2 * sin(π * fc / fs) ≈ cutoff for low frequencies
  val f = Cat(0.U(1.W), io.cutoff(14, 0)).asSInt  // Use lower 15 bits as Q15

  // Convert resonance to damping: q = 1 - resonance/256
  // Higher resonance = lower damping = more resonant
  val q_inv = (256.U - io.resonance).asSInt  // 256 - res
  val q = (q_inv << 7)(15, 0).asSInt  // Scale to Q15 range (~0.5 to 1.0)

  when(io.reset_state) {
    // Clear filter state on trigger for clean note start
    low := 0.S
    band := 0.S
  }.elsewhen(io.tick) {
    // SVF equations:
    // high = input - low - q * band
    // band = band + f * high
    // low = low + f * band

    val high = io.input - low(15, 0).asSInt - q15mul(q, band(15, 0).asSInt)
    val new_band = band(15, 0).asSInt + q15mul(f, high)
    val new_low = low(15, 0).asSInt + q15mul(f, new_band)

    band := new_band.asSInt
    low := new_low.asSInt
  }

  // Select output based on mode
  io.output := MuxLookup(io.mode, low(15, 0).asSInt, Seq(
    0.U -> low(15, 0).asSInt,   // Low-pass
    1.U -> (io.input - low(15, 0).asSInt - q15mul(q, band(15, 0).asSInt)),  // High-pass
    2.U -> band(15, 0).asSInt   // Band-pass
  ))
}

// ============================================================================
// DC Blocker (removes DC offset)
// Single-pole high-pass filter: y[n] = x[n] - x[n-1] + R * y[n-1]
// ============================================================================
class DCBlocker extends Module {
  val io = IO(new Bundle {
    val input = Input(SInt(16.W))
    val output = Output(SInt(16.W))
    val tick = Input(Bool())
  })

  val x_prev = RegInit(0.S(16.W))
  val y_prev = RegInit(0.S(32.W))

  // R = 0.995 in Q15 ≈ 32604
  val R = 32604.S(16.W)

  def q15mul(a: SInt, b: SInt): SInt = {
    val product = (a * b) >> 15
    product(15, 0).asSInt
  }

  when(io.tick) {
    // y[n] = x[n] - x[n-1] + R * y[n-1]
    val diff = io.input - x_prev
    val feedback = q15mul(R, y_prev(15, 0).asSInt)
    y_prev := (diff + feedback).asSInt
    x_prev := io.input
  }

  io.output := y_prev(15, 0).asSInt
}

// ============================================================================
// Complete Voice with Oscillator, Filter, and Envelope
// ============================================================================
class HWSynthVoice extends Module {
  val io = IO(new Bundle {
    // Control
    val gate = Input(Bool())
    val trigger = Input(Bool())
    val freq = Input(UInt(16.W))
    val wave_type = Input(UInt(3.W))  // 0=saw, 1=square, 2=tri, 3=sine, 4=noise

    // AHDSR Envelope parameters
    val attack_rate = Input(UInt(8.W))
    val hold_time = Input(UInt(8.W))
    val decay_rate = Input(UInt(8.W))
    val sustain_level = Input(UInt(16.W))
    val release_rate = Input(UInt(8.W))

    // Filter parameters
    val filter_cutoff = Input(UInt(16.W))
    val filter_resonance = Input(UInt(8.W))
    val filter_mode = Input(UInt(2.W))

    // Modulation
    val env_to_filter = Input(SInt(16.W))  // Envelope → filter cutoff amount
    val env_mod_enable = Input(Bool())

    // Output
    val sample = Output(SInt(16.W))
    val active = Output(Bool())
    val env_out = Output(SInt(16.W))  // For debugging/modulation

    // Tick for sample generation (11.025 kHz)
    val tick = Input(Bool())
  })

  // ================= Oscillator =================
  val phase = RegInit(0.U(32.W))
  val noise_lfsr = RegInit("hACE1".U(16.W))  // Linear feedback shift register for noise

  // Advance phase on each tick
  when(io.tick) {
    phase := phase + Cat(io.freq, 0.U(16.W))

    // LFSR for noise (Galois LFSR, taps at bits 16, 14, 13, 11)
    val feedback = noise_lfsr(0) ^ noise_lfsr(2) ^ noise_lfsr(3) ^ noise_lfsr(5)
    noise_lfsr := Cat(feedback, noise_lfsr(15, 1))
  }

  val phase16 = phase(31, 16)

  // Waveform generators
  val saw_wave = (phase16.asSInt - 32768.S(17.W))(15, 0).asSInt

  val square_wave = Mux(phase16 < 32768.U, 32767.S(16.W), (-32767).S(16.W))

  val triangle_wave = {
    val p = phase16
    val rising = (p << 1)(15, 0)
    val falling = (65535.U - (p << 1))(15, 0)
    Mux(p < 32768.U,
        Mux(p < 16384.U, rising.asSInt, falling.asSInt),
        Mux(p < 49152.U, (-falling.asSInt), rising.asSInt - 32768.S))
  }

  // Sine wave using lookup table with linear interpolation
  val sine_wave = {
    val idx = phase16(15, 8)  // 256 positions
    val quadrant = idx(7, 6)  // Which quadrant (0-3)
    val pos = idx(5, 0)       // Position within quadrant (0-63)

    // Create ROM from lookup table
    val sinRom = VecInit(SineLUT.table.map(_.S(16.W)))

    // Mirror for quadrants
    val lookup_idx = Mux(quadrant(0), (63.U - pos), pos)
    val base_val = sinRom(lookup_idx)

    // Negate for quadrants 2, 3
    Mux(quadrant(1), -base_val, base_val)
  }

  val noise_wave = (noise_lfsr.asSInt - 32768.S)(15, 0).asSInt

  val osc_out = MuxLookup(io.wave_type, saw_wave, Seq(
    0.U -> saw_wave,
    1.U -> square_wave,
    2.U -> triangle_wave,
    3.U -> sine_wave,
    4.U -> noise_wave
  ))

  // ================= AHDSR Envelope Generator =================
  // States: 0=off, 1=attack, 2=hold, 3=decay, 4=sustain, 5=release
  val env_state = RegInit(0.U(3.W))
  val env_val = RegInit(0.S(20.W))
  val hold_counter = RegInit(0.U(16.W))

  val PEAK_VAL = 32767.S(20.W)

  // Attack increment
  val attack_inc = Cat(0.U(4.W), io.attack_rate, 0.U(8.W)).asSInt  // 20-bit positive

  // Decay/release multipliers
  // decay_rate << 6 gives 14-bit value, pad to 16-bit
  val decay_sub = Cat(0.U(2.W), io.decay_rate, 0.U(6.W))  // 16-bit
  val decay_mult = (32767.U(16.W) - decay_sub)(15, 0).asSInt  // ~0.99x depending on rate
  val release_sub = Cat(0.U(2.W), io.release_rate, 0.U(6.W))  // 16-bit
  val release_mult = (32767.U(16.W) - release_sub)(15, 0).asSInt

  // Q15 multiply helper
  def q15mul(a: SInt, b: SInt): SInt = {
    val product = (a * b) >> 15
    product(15, 0).asSInt
  }

  when(io.tick) {
    // Trigger can restart attack from ANY state (retrigger behavior)
    when(io.trigger) {
      env_state := 1.U  // Start attack
      env_val := 0.S    // Reset envelope to zero
      phase := 0.U      // Reset oscillator phase
      hold_counter := 0.U
    }.otherwise {
      switch(env_state) {
        is(0.U) { // Off
          env_val := 0.S
          when(io.gate) {
            env_state := 1.U  // Start attack
            phase := 0.U      // Reset oscillator phase
            hold_counter := 0.U
          }
        }
        is(1.U) { // Attack
          val next_val = env_val + attack_inc
          when(next_val >= PEAK_VAL) {
            env_val := PEAK_VAL
            env_state := Mux(io.hold_time > 0.U, 2.U, 3.U)  // Hold or Decay
            hold_counter := 0.U
          }.otherwise {
            env_val := next_val
          }
        }
        is(2.U) { // Hold
          hold_counter := hold_counter + 1.U
          when(hold_counter >= Cat(io.hold_time, 0.U(8.W))) {
            env_state := 3.U  // Move to decay
          }
        }
        is(3.U) { // Decay
          val sus = io.sustain_level.zext.asSInt
          val delta = env_val - sus
          val decayed = q15mul(delta(15, 0).asSInt, decay_mult)
          env_val := sus + decayed

          when(env_val <= sus + 32.S) {
            env_val := sus
            env_state := 4.U  // Move to sustain
          }
        }
        is(4.U) { // Sustain
          env_val := io.sustain_level.zext.asSInt
          when(!io.gate) {
            env_state := 5.U  // Move to release
          }
        }
        is(5.U) { // Release
          val released = q15mul(env_val(15, 0).asSInt, release_mult)
          env_val := released

          when(env_val < 32.S) {
            env_val := 0.S
            env_state := 0.U  // Off
          }
        }
      }
    }
  }

  val env_out_val = env_val(15, 0).asSInt
  io.env_out := env_out_val

  // ================= SVF Filter =================
  val filter = Module(new SVFFilter)
  filter.io.input := osc_out
  filter.io.tick := io.tick
  filter.io.mode := io.filter_mode
  filter.io.resonance := io.filter_resonance
  filter.io.reset_state := io.trigger  // Reset filter state on note trigger

  // Apply envelope modulation to filter cutoff
  val mod_amount = Mux(io.env_mod_enable,
    q15mul(env_out_val, io.env_to_filter),
    0.S(16.W))

  val modulated_cutoff = (io.filter_cutoff.asSInt + mod_amount)
  val clamped_cutoff = Mux(modulated_cutoff < 0.S, 0.U(16.W),
                       Mux(modulated_cutoff > 32767.S, 32767.U(16.W),
                           modulated_cutoff(15, 0).asUInt))
  filter.io.cutoff := clamped_cutoff

  // ================= Apply Envelope to Filter Output =================
  val filtered_out = filter.io.output
  io.sample := q15mul(filtered_out, env_out_val)
  io.active := env_state =/= 0.U
}

// ============================================================================
// Main HWSynth Module
// ============================================================================
class HWSynth extends Module {
  val io = IO(new Bundle {
    val channels = Flipped(new AXI4LiteChannels(8, Parameters.DataBits))
    val sample = Output(SInt(16.W))
    val sample_valid = Output(Bool())
  })

  // ================= Constants =================
  object Reg {
    val ID      = 0x00
    val CTRL    = 0x04
    val STATUS  = 0x08
    val SAMPLE  = 0x0C
    val VOICE0  = 0x10
    val VOICE1  = 0x30
    val VOICE2  = 0x50
    val VOICE3  = 0x70
  }

  val SYNTH_ID = "h53594E54".U  // 'SYNT'

  // ================= Sample Rate Tick =================
  private val SYS_CLK_HZ = 50000000
  private val SAMPLE_RATE_HZ = 11025
  private val DIV = SYS_CLK_HZ / SAMPLE_RATE_HZ

  private val CNT_WIDTH = log2Ceil(DIV + 1)
  val tickCnt = RegInit(0.U(CNT_WIDTH.W))
  val tick = (tickCnt === (DIV - 1).U)
  tickCnt := Mux(tick, 0.U, tickCnt + 1.U)

  // ================= Control Registers =================
  val enable = RegInit(false.B)
  val voice_mask = RegInit(0.U(4.W))

  // Per-voice configuration (4 voices)
  val voice_freq = RegInit(VecInit(Seq.fill(4)(0.U(16.W))))
  val voice_wave = RegInit(VecInit(Seq.fill(4)(0.U(3.W))))
  val voice_attack = RegInit(VecInit(Seq.fill(4)(0x20.U(8.W))))
  val voice_hold = RegInit(VecInit(Seq.fill(4)(0.U(8.W))))
  val voice_decay = RegInit(VecInit(Seq.fill(4)(0x10.U(8.W))))
  val voice_sustain = RegInit(VecInit(Seq.fill(4)(16383.U(16.W))))
  val voice_release = RegInit(VecInit(Seq.fill(4)(0x20.U(8.W))))
  val voice_filter_cutoff = RegInit(VecInit(Seq.fill(4)(32767.U(16.W))))  // Fully open
  val voice_filter_res = RegInit(VecInit(Seq.fill(4)(0.U(8.W))))
  val voice_filter_mode = RegInit(VecInit(Seq.fill(4)(0.U(2.W))))
  val voice_env_mod = RegInit(VecInit(Seq.fill(4)(0.S(16.W))))
  val voice_env_mod_en = RegInit(VecInit(Seq.fill(4)(false.B)))
  val voice_gate = RegInit(VecInit(Seq.fill(4)(false.B)))
  val voice_trigger = RegInit(VecInit(Seq.fill(4)(false.B)))

  // ================= Voice Instances =================
  val voices = Seq.fill(4)(Module(new HWSynthVoice))

  for (i <- 0 until 4) {
    voices(i).io.tick := tick && enable && voice_mask(i)
    voices(i).io.gate := voice_gate(i)
    voices(i).io.trigger := voice_trigger(i)
    voices(i).io.freq := voice_freq(i)
    voices(i).io.wave_type := voice_wave(i)
    voices(i).io.attack_rate := voice_attack(i)
    voices(i).io.hold_time := voice_hold(i)
    voices(i).io.decay_rate := voice_decay(i)
    voices(i).io.sustain_level := voice_sustain(i)
    voices(i).io.release_rate := voice_release(i)
    voices(i).io.filter_cutoff := voice_filter_cutoff(i)
    voices(i).io.filter_resonance := voice_filter_res(i)
    voices(i).io.filter_mode := voice_filter_mode(i)
    voices(i).io.env_to_filter := voice_env_mod(i)
    voices(i).io.env_mod_enable := voice_env_mod_en(i)
  }

  // Trigger is a pulse - latch it until the next tick processes it
  // On tick, if trigger was set, it stays set for this tick (envelope sees it)
  // Then it gets cleared for the next tick
  val trigger_latch = RegInit(VecInit(Seq.fill(4)(false.B)))
  
  when(tick && enable) {
    for (i <- 0 until 4) {
      when(voice_mask(i)) {
        // Clear the latch after it's been seen by the envelope
        trigger_latch(i) := voice_trigger(i)
        voice_trigger(i) := false.B
      }
    }
  }

  // ================= Voice Mixing =================
  val mix = Wire(SInt(20.W))
  mix := voices(0).io.sample +& voices(1).io.sample +&
         voices(2).io.sample +& voices(3).io.sample

  // Saturate to 16-bit
  val saturated = Mux(mix > 32767.S, 32767.S(16.W),
                  Mux(mix < (-32767).S, (-32767).S(16.W), mix(15, 0).asSInt))

  // ================= DC Blocker =================
  val dc_blocker = Module(new DCBlocker)
  dc_blocker.io.input := saturated
  dc_blocker.io.tick := tick && enable

  // ================= Output =================
  val sample_reg = RegInit(0.S(16.W))
  val sample_valid_reg = RegInit(false.B)

  when(tick && enable) {
    sample_reg := dc_blocker.io.output
    sample_valid_reg := true.B
  }.otherwise {
    sample_valid_reg := false.B
  }

  io.sample := sample_reg
  io.sample_valid := sample_valid_reg

  val active_voices = Cat(
    voices(3).io.active,
    voices(2).io.active,
    voices(1).io.active,
    voices(0).io.active
  )

  // ================= AXI4-Lite Slave =================
  val slave = Module(new AXI4LiteSlave(8, Parameters.DataBits))
  slave.io.channels <> io.channels

  val addr = slave.io.bundle.address & 0xff.U

  // Helper function for voice register access
  def handleVoiceWrite(voiceIdx: Int, baseAddr: Int): Unit = {
    when(addr >= baseAddr.U && addr < (baseAddr + 0x20).U) {
      val voff = addr - baseAddr.U
      val wdata = slave.io.bundle.write_data
      switch(voff) {
        is(0x00.U) { voice_freq(voiceIdx) := wdata(15, 0) }
        is(0x04.U) { voice_wave(voiceIdx) := wdata(2, 0) }
        is(0x08.U) {
          voice_attack(voiceIdx) := wdata(7, 0)
          voice_hold(voiceIdx) := wdata(15, 8)
          voice_decay(voiceIdx) := wdata(23, 16)
          voice_release(voiceIdx) := wdata(31, 24)
        }
        is(0x0C.U) { voice_sustain(voiceIdx) := wdata(15, 0) }
        is(0x10.U) {
          voice_filter_cutoff(voiceIdx) := wdata(15, 0)
          voice_filter_res(voiceIdx) := wdata(23, 16)
          voice_filter_mode(voiceIdx) := wdata(25, 24)
        }
        is(0x14.U) {
          voice_env_mod(voiceIdx) := wdata(15, 0).asSInt
          voice_env_mod_en(voiceIdx) := wdata(16)
        }
        is(0x18.U) {
          voice_gate(voiceIdx) := wdata(0)
          voice_trigger(voiceIdx) := wdata(1)
        }
      }
    }
  }

  // ================= Write Handling =================
  when(slave.io.bundle.write) {
    val wdata = slave.io.bundle.write_data

    switch(addr) {
      is(Reg.CTRL.U) {
        enable := wdata(0)
        voice_mask := wdata(7, 4)
      }
    }

    handleVoiceWrite(0, Reg.VOICE0)
    handleVoiceWrite(1, Reg.VOICE1)
    handleVoiceWrite(2, Reg.VOICE2)
    handleVoiceWrite(3, Reg.VOICE3)
  }

  // ================= Read Handling =================
  val read_data = WireDefault(0.U(32.W))

  switch(addr) {
    is(Reg.ID.U) { read_data := SYNTH_ID }
    is(Reg.CTRL.U) { read_data := Cat(0.U(24.W), voice_mask, 0.U(3.W), enable) }
    is(Reg.STATUS.U) { read_data := Cat(0.U(24.W), active_voices, 0.U(3.W), sample_valid_reg) }
    is(Reg.SAMPLE.U) { read_data := sample_reg.asUInt }
  }

  slave.io.bundle.read_valid := slave.io.bundle.read
  slave.io.bundle.read_data := read_data
}
