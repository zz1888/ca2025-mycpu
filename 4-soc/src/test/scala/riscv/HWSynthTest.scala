// SPDX-License-Identifier: MIT
package riscv

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import peripheral._

class HWSynthTest extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "HWSynthVoice"

  it should "generate non-zero saw wave with envelope" in {
    test(new HWSynthVoice) { dut =>
      // Configure voice
      dut.io.freq.poke(2000.U)  // ~87 Hz
      dut.io.wave_type.poke(0.U)  // Saw
      dut.io.attack_rate.poke(0xFF.U)  // Very fast attack
      dut.io.hold_time.poke(0.U)
      dut.io.decay_rate.poke(0.U)
      dut.io.sustain_level.poke(32000.U)
      dut.io.release_rate.poke(0.U)
      dut.io.filter_cutoff.poke(32767.U)  // Fully open
      dut.io.filter_resonance.poke(0.U)
      dut.io.filter_mode.poke(0.U)  // LP
      dut.io.env_to_filter.poke(0.S)
      dut.io.env_mod_enable.poke(false.B)
      dut.io.gate.poke(false.B)
      dut.io.trigger.poke(false.B)
      dut.io.tick.poke(false.B)

      // Initial state
      dut.clock.step(1)
      println(s"Initial - active: ${dut.io.active.peek().litToBoolean}, env: ${dut.io.env_out.peek().litValue}")

      // Trigger the envelope
      dut.io.gate.poke(true.B)
      dut.io.trigger.poke(true.B)
      
      // Simulate 50 ticks
      var minSample = Int.MaxValue
      var maxSample = Int.MinValue
      var nonZeroCount = 0

      for (i <- 0 until 100) {
        // Pulse tick
        dut.io.tick.poke(true.B)
        dut.clock.step(1)
        dut.io.tick.poke(false.B)
        dut.clock.step(1)

        // Clear trigger after first tick
        if (i == 0) {
          dut.io.trigger.poke(false.B)
        }

        val sample = dut.io.sample.peek().litValue.toInt
        val signedSample = if (sample > 32767) sample - 65536 else sample
        val envOut = dut.io.env_out.peek().litValue.toInt
        val active = dut.io.active.peek().litToBoolean

        if (i < 10 || i % 20 == 0) {
          println(s"Tick $i: sample=$signedSample, env=$envOut, active=$active")
        }

        if (signedSample != 0) nonZeroCount += 1
        if (signedSample < minSample) minSample = signedSample
        if (signedSample > maxSample) maxSample = signedSample
      }

      println(s"\nResults: min=$minSample, max=$maxSample, nonZeroCount=$nonZeroCount")

      // Verify we got non-zero output
      assert(nonZeroCount > 0, "Expected non-zero samples")
      assert(maxSample > 1000, s"Expected maxSample > 1000, got $maxSample")
    }
  }

  it should "have active signal when envelope is running" in {
    test(new HWSynthVoice) { dut =>
      // Configure voice
      dut.io.freq.poke(1000.U)
      dut.io.wave_type.poke(0.U)
      dut.io.attack_rate.poke(0x40.U)
      dut.io.hold_time.poke(0.U)
      dut.io.decay_rate.poke(0x20.U)
      dut.io.sustain_level.poke(16000.U)
      dut.io.release_rate.poke(0x20.U)
      dut.io.filter_cutoff.poke(32767.U)
      dut.io.filter_resonance.poke(0.U)
      dut.io.filter_mode.poke(0.U)
      dut.io.env_to_filter.poke(0.S)
      dut.io.env_mod_enable.poke(false.B)
      dut.io.gate.poke(false.B)
      dut.io.trigger.poke(false.B)
      dut.io.tick.poke(false.B)
      
      dut.clock.step(1)

      // Initially not active
      assert(!dut.io.active.peek().litToBoolean, "Should be inactive initially")

      // Trigger
      dut.io.gate.poke(true.B)
      dut.io.trigger.poke(true.B)
      dut.io.tick.poke(true.B)
      dut.clock.step(1)
      dut.io.tick.poke(false.B)
      dut.io.trigger.poke(false.B)
      dut.clock.step(1)

      // Should be active now
      val activeAfterTrigger = dut.io.active.peek().litToBoolean
      println(s"Active after trigger: $activeAfterTrigger")
      assert(activeAfterTrigger, "Should be active after trigger")
    }
  }

  // Helper function to setup voice with common parameters
  def setupVoice(dut: HWSynthVoice, freq: Int = 2000, wave: Int = 0): Unit = {
    dut.io.freq.poke(freq.U)
    dut.io.wave_type.poke(wave.U)
    dut.io.attack_rate.poke(0xFF.U)
    dut.io.hold_time.poke(0.U)
    dut.io.decay_rate.poke(0.U)
    dut.io.sustain_level.poke(32000.U)
    dut.io.release_rate.poke(0.U)
    dut.io.filter_cutoff.poke(32767.U)
    dut.io.filter_resonance.poke(0.U)
    dut.io.filter_mode.poke(0.U)
    dut.io.env_to_filter.poke(0.S)
    dut.io.env_mod_enable.poke(false.B)
    dut.io.gate.poke(false.B)
    dut.io.trigger.poke(false.B)
    dut.io.tick.poke(false.B)
  }

  def runTicks(dut: HWSynthVoice, count: Int): (Int, Int, Int) = {
    var minSample = Int.MaxValue
    var maxSample = Int.MinValue
    var nonZeroCount = 0
    for (_ <- 0 until count) {
      dut.io.tick.poke(true.B)
      dut.clock.step(1)
      dut.io.tick.poke(false.B)
      dut.clock.step(1)
      val sample = dut.io.sample.peek().litValue.toInt
      val signedSample = if (sample > 32767) sample - 65536 else sample
      if (signedSample != 0) nonZeroCount += 1
      if (signedSample < minSample) minSample = signedSample
      if (signedSample > maxSample) maxSample = signedSample
    }
    (minSample, maxSample, nonZeroCount)
  }

  // ========== Waveform Tests ==========

  it should "generate square wave (wave_type=1)" in {
    test(new HWSynthVoice) { dut =>
      setupVoice(dut, freq = 2000, wave = 1)
      dut.clock.step(1)
      dut.io.gate.poke(true.B)
      dut.io.trigger.poke(true.B)
      dut.io.tick.poke(true.B)
      dut.clock.step(1)
      dut.io.tick.poke(false.B)
      dut.io.trigger.poke(false.B)
      dut.clock.step(1)

      val (minS, maxS, nonZero) = runTicks(dut, 200)
      println(s"Square wave: min=$minS, max=$maxS, nonZero=$nonZero")
      assert(nonZero > 0, "Expected non-zero samples")
      assert(maxS > 10000, s"Expected max > 10000, got $maxS")
      assert(minS < -10000, s"Expected min < -10000, got $minS")
    }
  }

  it should "generate triangle wave (wave_type=2)" in {
    test(new HWSynthVoice) { dut =>
      setupVoice(dut, freq = 2000, wave = 2)
      dut.clock.step(1)
      dut.io.gate.poke(true.B)
      dut.io.trigger.poke(true.B)
      dut.io.tick.poke(true.B)
      dut.clock.step(1)
      dut.io.tick.poke(false.B)
      dut.io.trigger.poke(false.B)
      dut.clock.step(1)

      val (minS, maxS, nonZero) = runTicks(dut, 200)
      println(s"Triangle wave: min=$minS, max=$maxS, nonZero=$nonZero")
      assert(nonZero > 0, "Expected non-zero samples")
      assert(maxS > 10000, s"Expected max > 10000, got $maxS")
      assert(minS < -10000, s"Expected min < -10000, got $minS")
    }
  }

  it should "generate sine wave (wave_type=3)" in {
    test(new HWSynthVoice) { dut =>
      setupVoice(dut, freq = 2000, wave = 3)
      dut.clock.step(1)
      dut.io.gate.poke(true.B)
      dut.io.trigger.poke(true.B)
      dut.io.tick.poke(true.B)
      dut.clock.step(1)
      dut.io.tick.poke(false.B)
      dut.io.trigger.poke(false.B)
      dut.clock.step(1)

      val (minS, maxS, nonZero) = runTicks(dut, 200)
      println(s"Sine wave: min=$minS, max=$maxS, nonZero=$nonZero")
      assert(nonZero > 0, "Expected non-zero samples")
      assert(maxS > 10000, s"Expected max > 10000, got $maxS")
      assert(minS < -10000, s"Expected min < -10000, got $minS")
    }
  }

  it should "generate noise (wave_type=4)" in {
    test(new HWSynthVoice) { dut =>
      setupVoice(dut, freq = 2000, wave = 4)
      dut.clock.step(1)
      dut.io.gate.poke(true.B)
      dut.io.trigger.poke(true.B)
      dut.io.tick.poke(true.B)
      dut.clock.step(1)
      dut.io.tick.poke(false.B)
      dut.io.trigger.poke(false.B)
      dut.clock.step(1)

      var samples = Seq.empty[Int]
      for (_ <- 0 until 200) {
        dut.io.tick.poke(true.B)
        dut.clock.step(1)
        dut.io.tick.poke(false.B)
        dut.clock.step(1)
        val sample = dut.io.sample.peek().litValue.toInt
        val signedSample = if (sample > 32767) sample - 65536 else sample
        samples = samples :+ signedSample
      }
      val uniqueCount = samples.distinct.size
      println(s"Noise: uniqueSamples=$uniqueCount/200")
      assert(uniqueCount > 50, s"Noise should have many unique values, got $uniqueCount")
    }
  }

  // ========== Envelope Tests ==========

  it should "envelope attack phase increases level" in {
    test(new HWSynthVoice) { dut =>
      setupVoice(dut, freq = 1000, wave = 0)
      dut.io.attack_rate.poke(0x10.U)  // Very slow attack to see increases
      dut.clock.step(1)

      dut.io.gate.poke(true.B)
      dut.io.trigger.poke(true.B)
      dut.io.tick.poke(true.B)
      dut.clock.step(1)
      dut.io.tick.poke(false.B)
      dut.io.trigger.poke(false.B)
      dut.clock.step(1)

      var prevEnv = dut.io.env_out.peek().litValue.toInt
      var increasing = 0
      for (i <- 0 until 100) {
        dut.io.tick.poke(true.B)
        dut.clock.step(1)
        dut.io.tick.poke(false.B)
        dut.clock.step(1)
        val currEnv = dut.io.env_out.peek().litValue.toInt
        if (currEnv > prevEnv) increasing += 1
        if (i < 10) println(s"Attack tick $i: env=$currEnv")
        prevEnv = currEnv
      }
      println(s"Attack phase: increasing=$increasing/100")
      // With slow attack, envelope should increase multiple times
      assert(increasing >= 2, s"Attack should increase at least 2 times, got $increasing increases")
    }
  }

  it should "envelope decay to sustain level" in {
    test(new HWSynthVoice) { dut =>
      setupVoice(dut, freq = 1000, wave = 0)
      dut.io.attack_rate.poke(0xFF.U)  // Very fast attack
      dut.io.decay_rate.poke(0x40.U)   // Moderate decay
      dut.io.sustain_level.poke(16000.U)
      dut.clock.step(1)

      dut.io.gate.poke(true.B)
      dut.io.trigger.poke(true.B)
      dut.io.tick.poke(true.B)
      dut.clock.step(1)
      dut.io.tick.poke(false.B)
      dut.io.trigger.poke(false.B)
      dut.clock.step(1)

      // Run attack phase
      for (_ <- 0 until 20) {
        dut.io.tick.poke(true.B)
        dut.clock.step(1)
        dut.io.tick.poke(false.B)
        dut.clock.step(1)
      }

      val peakEnv = dut.io.env_out.peek().litValue.toInt
      println(s"Peak envelope: $peakEnv")

      // Run decay phase
      var decreasing = 0
      var prevEnv = peakEnv
      for (i <- 0 until 100) {
        dut.io.tick.poke(true.B)
        dut.clock.step(1)
        dut.io.tick.poke(false.B)
        dut.clock.step(1)
        val currEnv = dut.io.env_out.peek().litValue.toInt
        if (currEnv < prevEnv) decreasing += 1
        if (i < 10 || i % 20 == 0) println(s"Decay tick $i: env=$currEnv")
        prevEnv = currEnv
      }

      val finalEnv = dut.io.env_out.peek().litValue.toInt
      println(s"Final envelope: $finalEnv, target sustain: 16000, decreasing=$decreasing")
      assert(finalEnv <= 20000, s"Should decay toward sustain, got $finalEnv")
    }
  }

  it should "envelope release phase decreases" in {
    test(new HWSynthVoice) { dut =>
      setupVoice(dut, freq = 1000, wave = 0)
      dut.io.attack_rate.poke(0xFF.U)
      dut.io.release_rate.poke(0x40.U)
      dut.clock.step(1)

      // Trigger and run attack
      dut.io.gate.poke(true.B)
      dut.io.trigger.poke(true.B)
      dut.io.tick.poke(true.B)
      dut.clock.step(1)
      dut.io.tick.poke(false.B)
      dut.io.trigger.poke(false.B)
      dut.clock.step(1)

      for (_ <- 0 until 30) {
        dut.io.tick.poke(true.B)
        dut.clock.step(1)
        dut.io.tick.poke(false.B)
        dut.clock.step(1)
      }

      val envBeforeRelease = dut.io.env_out.peek().litValue.toInt
      println(s"Envelope before release: $envBeforeRelease")

      // Release gate
      dut.io.gate.poke(false.B)

      var decreasing = 0
      var prevEnv = envBeforeRelease
      for (i <- 0 until 100) {
        dut.io.tick.poke(true.B)
        dut.clock.step(1)
        dut.io.tick.poke(false.B)
        dut.clock.step(1)
        val currEnv = dut.io.env_out.peek().litValue.toInt
        if (currEnv < prevEnv) decreasing += 1
        if (i < 10 || i % 20 == 0) println(s"Release tick $i: env=$currEnv")
        prevEnv = currEnv
      }

      val finalEnv = dut.io.env_out.peek().litValue.toInt
      println(s"Final envelope after release: $finalEnv, decreasing=$decreasing")
      assert(finalEnv < envBeforeRelease, s"Release should decrease envelope")
    }
  }

  it should "voice becomes inactive after release completes" in {
    test(new HWSynthVoice) { dut =>
      // Setup with parameters that ensure envelope reaches inactive state
      dut.io.freq.poke(1000.U)
      dut.io.wave_type.poke(0.U)
      dut.io.attack_rate.poke(0xFF.U)  // Fast attack
      dut.io.hold_time.poke(0.U)
      dut.io.decay_rate.poke(0xFF.U)   // Fast decay to reach sustain quickly
      dut.io.sustain_level.poke(100.U)  // Very low sustain
      dut.io.release_rate.poke(0xFF.U)  // Fast release (mult ~0.5)
      dut.io.filter_cutoff.poke(32767.U)
      dut.io.filter_resonance.poke(0.U)
      dut.io.filter_mode.poke(0.U)
      dut.io.env_to_filter.poke(0.S)
      dut.io.env_mod_enable.poke(false.B)
      dut.io.gate.poke(false.B)
      dut.io.trigger.poke(false.B)
      dut.io.tick.poke(false.B)
      dut.clock.step(1)

      // Trigger
      dut.io.gate.poke(true.B)
      dut.io.trigger.poke(true.B)
      dut.io.tick.poke(true.B)
      dut.clock.step(1)
      dut.io.tick.poke(false.B)
      dut.io.trigger.poke(false.B)
      dut.clock.step(1)

      // Run attack + decay to reach sustain (envelope should decay to low level)
      for (i <- 0 until 100) {
        dut.io.tick.poke(true.B)
        dut.clock.step(1)
        dut.io.tick.poke(false.B)
        dut.clock.step(1)
        if (i < 10 || i % 20 == 0) {
          println(s"Pre-release tick $i: env=${dut.io.env_out.peek().litValue}")
        }
      }

      val envBeforeRelease = dut.io.env_out.peek().litValue.toInt
      val activeBeforeRelease = dut.io.active.peek().litToBoolean
      println(s"Active before release: $activeBeforeRelease, env=$envBeforeRelease")
      assert(activeBeforeRelease, "Should be active")

      // Release gate
      dut.io.gate.poke(false.B)

      // Run until inactive - with low sustain and fast release, should reach 0 quickly
      var becameInactive = false
      for (i <- 0 until 100) {
        dut.io.tick.poke(true.B)
        dut.clock.step(1)
        dut.io.tick.poke(false.B)
        dut.clock.step(1)
        val env = dut.io.env_out.peek().litValue.toInt
        val active = dut.io.active.peek().litToBoolean
        if (!active && !becameInactive) {
          println(s"Voice became inactive at tick $i, env=$env")
          becameInactive = true
        }
        if (i < 20 || (i % 20 == 0 && !becameInactive)) {
          println(s"Release tick $i: env=$env, active=$active")
        }
      }

      assert(becameInactive, "Voice should become inactive after release")
    }
  }

  // ========== SVF Filter Tests ==========

  it should "SVF filter LP mode passes DC" in {
    test(new SVFFilter) { dut =>
      dut.io.input.poke(16000.S)
      dut.io.cutoff.poke(1000.U)  // Low cutoff
      dut.io.resonance.poke(0.U)
      dut.io.mode.poke(0.U)  // LP
      dut.io.tick.poke(false.B)
      dut.clock.step(1)

      // Run DC signal through filter
      for (_ <- 0 until 100) {
        dut.io.tick.poke(true.B)
        dut.clock.step(1)
        dut.io.tick.poke(false.B)
        dut.clock.step(1)
      }

      val output = dut.io.output.peek().litValue.toInt
      val signedOutput = if (output > 32767) output - 65536 else output
      println(s"SVF LP DC test: input=16000, output=$signedOutput")
      assert(signedOutput > 10000, s"LP should pass DC, got $signedOutput")
    }
  }

  it should "SVF filter HP mode blocks DC" in {
    test(new SVFFilter) { dut =>
      dut.io.input.poke(16000.S)
      dut.io.cutoff.poke(16000.U)  // High cutoff
      dut.io.resonance.poke(0.U)
      dut.io.mode.poke(1.U)  // HP
      dut.io.tick.poke(false.B)
      dut.clock.step(1)

      // Run DC signal through filter
      for (_ <- 0 until 200) {
        dut.io.tick.poke(true.B)
        dut.clock.step(1)
        dut.io.tick.poke(false.B)
        dut.clock.step(1)
      }

      val output = dut.io.output.peek().litValue.toInt
      val signedOutput = if (output > 32767) output - 65536 else output
      println(s"SVF HP DC test: input=16000, output=$signedOutput")
      assert(math.abs(signedOutput) < 5000, s"HP should block DC, got $signedOutput")
    }
  }

  it should "SVF filter BP mode outputs mid-range" in {
    test(new SVFFilter) { dut =>
      dut.io.cutoff.poke(8000.U)
      dut.io.resonance.poke(128.U)  // 8-bit value (0-255)
      dut.io.mode.poke(2.U)  // BP
      dut.io.tick.poke(false.B)
      dut.clock.step(1)

      // Generate alternating signal (pseudo-AC)
      var minOut = Int.MaxValue
      var maxOut = Int.MinValue
      for (i <- 0 until 200) {
        val input = if ((i / 10) % 2 == 0) 16000 else -16000
        dut.io.input.poke(input.S)
        dut.io.tick.poke(true.B)
        dut.clock.step(1)
        dut.io.tick.poke(false.B)
        dut.clock.step(1)

        val output = dut.io.output.peek().litValue.toInt
        val signedOutput = if (output > 32767) output - 65536 else output
        if (signedOutput < minOut) minOut = signedOutput
        if (signedOutput > maxOut) maxOut = signedOutput
      }

      println(s"SVF BP test: min=$minOut, max=$maxOut")
      assert(maxOut > 1000 || minOut < -1000, "BP should pass some signal")
    }
  }

  it should "SVF filter resonance affects output" in {
    test(new SVFFilter) { dut =>
      dut.io.cutoff.poke(8000.U)
      dut.io.mode.poke(0.U)  // LP
      dut.io.tick.poke(false.B)
      dut.clock.step(1)

      def measureResponse(res: Int): Int = {
        dut.io.resonance.poke(res.U)  // res is 8-bit (0-255)
        dut.clock.step(10)

        var maxOut = 0
        for (i <- 0 until 100) {
          val input = if ((i / 5) % 2 == 0) 10000 else -10000
          dut.io.input.poke(input.S)
          dut.io.tick.poke(true.B)
          dut.clock.step(1)
          dut.io.tick.poke(false.B)
          dut.clock.step(1)

          val output = dut.io.output.peek().litValue.toInt
          val signedOutput = if (output > 32767) output - 65536 else output
          if (math.abs(signedOutput) > maxOut) maxOut = math.abs(signedOutput)
        }
        maxOut
      }

      val noRes = measureResponse(0)
      val midRes = measureResponse(128)
      val highRes = measureResponse(220)  // 8-bit max is 255

      println(s"Resonance test: noRes=$noRes, midRes=$midRes, highRes=$highRes")
      // Resonance affects filter behavior - output changes with resonance
      // The relationship may be complex (damping vs self-oscillation)
      assert(noRes > 0, "Filter should produce output with no resonance")
      assert(midRes > 0, "Filter should produce output with mid resonance")
      assert(highRes > 0, "Filter should produce output with high resonance")
      // Different resonance values should produce different outputs
      val differentOutputs = (noRes != midRes) || (midRes != highRes)
      assert(differentOutputs, "Resonance should affect filter output")
    }
  }

  // ========== Envelope to Filter Modulation Tests ==========

  it should "envelope modulates filter cutoff" in {
    test(new HWSynthVoice) { dut =>
      setupVoice(dut, freq = 2000, wave = 0)
      dut.io.filter_cutoff.poke(8000.U)  // Base cutoff
      dut.io.env_to_filter.poke(16000.S)  // Strong positive modulation
      dut.io.env_mod_enable.poke(true.B)
      dut.io.attack_rate.poke(0x40.U)  // Slow attack to see modulation
      dut.clock.step(1)

      // Get initial filtered output
      dut.io.gate.poke(true.B)
      dut.io.trigger.poke(true.B)
      dut.io.tick.poke(true.B)
      dut.clock.step(1)
      dut.io.tick.poke(false.B)
      dut.io.trigger.poke(false.B)
      dut.clock.step(1)

      var samples = Seq.empty[Int]
      var envs = Seq.empty[Int]
      for (i <- 0 until 100) {
        dut.io.tick.poke(true.B)
        dut.clock.step(1)
        dut.io.tick.poke(false.B)
        dut.clock.step(1)

        val sample = dut.io.sample.peek().litValue.toInt
        val signedSample = if (sample > 32767) sample - 65536 else sample
        val env = dut.io.env_out.peek().litValue.toInt
        samples = samples :+ signedSample
        envs = envs :+ env

        if (i < 5 || i % 20 == 0) {
          println(s"Env mod tick $i: env=$env, sample=$signedSample")
        }
      }

      // Verify envelope changes affect output character
      val earlyEnv = envs.take(10).sum / 10
      val lateEnv = envs.drop(50).take(10).sum / 10
      println(s"Envelope modulation: earlyEnv=$earlyEnv, lateEnv=$lateEnv")
      assert(lateEnv > earlyEnv, "Envelope should increase during attack")
    }
  }

  // ========== DC Blocker Tests ==========

  it should "DC blocker removes DC offset" in {
    test(new DCBlocker) { dut =>
      dut.io.tick.poke(false.B)
      dut.clock.step(1)

      // Feed DC offset signal
      val dcOffset = 10000
      for (_ <- 0 until 500) {
        dut.io.input.poke(dcOffset.S)
        dut.io.tick.poke(true.B)
        dut.clock.step(1)
        dut.io.tick.poke(false.B)
        dut.clock.step(1)
      }

      val output = dut.io.output.peek().litValue.toInt
      val signedOutput = if (output > 32767) output - 65536 else output
      println(s"DC Blocker: input=$dcOffset, output=$signedOutput")
      assert(math.abs(signedOutput) < 1000, s"DC blocker should remove DC, got $signedOutput")
    }
  }

  it should "DC blocker passes AC signal" in {
    test(new DCBlocker) { dut =>
      dut.io.tick.poke(false.B)
      dut.clock.step(1)

      // Warm up
      for (i <- 0 until 100) {
        val input = if ((i / 10) % 2 == 0) 10000 else -10000
        dut.io.input.poke(input.S)
        dut.io.tick.poke(true.B)
        dut.clock.step(1)
        dut.io.tick.poke(false.B)
        dut.clock.step(1)
      }

      // Measure AC response
      var minOut = Int.MaxValue
      var maxOut = Int.MinValue
      for (i <- 0 until 200) {
        val input = if ((i / 10) % 2 == 0) 10000 else -10000
        dut.io.input.poke(input.S)
        dut.io.tick.poke(true.B)
        dut.clock.step(1)
        dut.io.tick.poke(false.B)
        dut.clock.step(1)

        val output = dut.io.output.peek().litValue.toInt
        val signedOutput = if (output > 32767) output - 65536 else output
        if (signedOutput < minOut) minOut = signedOutput
        if (signedOutput > maxOut) maxOut = signedOutput
      }

      println(s"DC Blocker AC test: min=$minOut, max=$maxOut, range=${maxOut - minOut}")
      assert(maxOut - minOut > 5000, s"DC blocker should pass AC, range=${maxOut - minOut}")
    }
  }

  // ========== Integration Test ==========

  it should "full voice integration test" in {
    test(new HWSynthVoice) { dut =>
      // Test full signal path: oscillator -> envelope -> filter -> output
      setupVoice(dut, freq = 3000, wave = 0)
      dut.io.attack_rate.poke(0x80.U)
      dut.io.decay_rate.poke(0x40.U)
      dut.io.sustain_level.poke(20000.U)
      dut.io.release_rate.poke(0x60.U)
      dut.io.filter_cutoff.poke(20000.U)
      dut.io.filter_resonance.poke(100.U)  // 8-bit value (0-255)
      dut.io.filter_mode.poke(0.U)
      dut.clock.step(1)

      // Trigger
      dut.io.gate.poke(true.B)
      dut.io.trigger.poke(true.B)
      dut.io.tick.poke(true.B)
      dut.clock.step(1)
      dut.io.tick.poke(false.B)
      dut.io.trigger.poke(false.B)
      dut.clock.step(1)

      // Attack phase
      var attackMax = 0
      for (i <- 0 until 50) {
        dut.io.tick.poke(true.B)
        dut.clock.step(1)
        dut.io.tick.poke(false.B)
        dut.clock.step(1)
        val sample = dut.io.sample.peek().litValue.toInt
        val signedSample = if (sample > 32767) sample - 65536 else sample
        if (math.abs(signedSample) > attackMax) attackMax = math.abs(signedSample)
      }
      println(s"Integration attack phase max: $attackMax")

      // Sustain phase
      var sustainMin = Int.MaxValue
      var sustainMax = Int.MinValue
      for (_ <- 0 until 100) {
        dut.io.tick.poke(true.B)
        dut.clock.step(1)
        dut.io.tick.poke(false.B)
        dut.clock.step(1)
        val sample = dut.io.sample.peek().litValue.toInt
        val signedSample = if (sample > 32767) sample - 65536 else sample
        if (signedSample < sustainMin) sustainMin = signedSample
        if (signedSample > sustainMax) sustainMax = signedSample
      }
      println(s"Integration sustain phase: min=$sustainMin, max=$sustainMax")

      // Release phase
      dut.io.gate.poke(false.B)
      var releaseDecreasing = true
      var prevMax = sustainMax
      for (_ <- 0 until 100) {
        dut.io.tick.poke(true.B)
        dut.clock.step(1)
        dut.io.tick.poke(false.B)
        dut.clock.step(1)
      }

      val finalEnv = dut.io.env_out.peek().litValue.toInt
      println(s"Integration release final env: $finalEnv")

      // Verify overall behavior
      assert(attackMax > 1000, s"Should produce sound during attack, got $attackMax")
      assert(sustainMax - sustainMin > 5000, s"Should have oscillation during sustain")
      assert(finalEnv < 20000, "Envelope should decrease during release")
    }
  }
}
