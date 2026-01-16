// SPDX-License-Identifier: MIT
package riscv

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import riscv.core._

class DSPTest extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "DSP Instructions"

  it should "perform Q15 fixed-point multiplication (QMUL16)" in {
    test(new ALU) { dut =>
      // Test case 1: 0.5 * 0.5 = 0.25
      // Q15: 0.5 = 0x4000 (16384), 0.25 = 0x2000 (8192)
      dut.io.func.poke(ALUFunctions.qmul16)
      dut.io.op1.poke(0x4000.U)  // 0.5 in Q15
      dut.io.op2.poke(0x4000.U)  // 0.5 in Q15
      dut.clock.step(1)
      val result1 = dut.io.result.peek().litValue
      println(f"QMUL16: 0x4000 * 0x4000 = 0x${result1}%04x (expected 0x2000)")
      assert(result1 == 0x2000, f"Expected 0x2000, got 0x${result1}%04x")

      // Test case 2: 1.0 * 0.5 = 0.5
      // Q15: 1.0 = 0x7FFF (32767, max positive)
      dut.io.op1.poke(0x7FFF.U)  // ~1.0 in Q15
      dut.io.op2.poke(0x4000.U)  // 0.5 in Q15
      dut.clock.step(1)
      val result2 = dut.io.result.peek().litValue
      println(f"QMUL16: 0x7FFF * 0x4000 = 0x${result2}%04x (expected ~0x3FFF)")
      // 0x7FFF * 0x4000 = 0x1FFFC000, >> 15 = 0x3FFF
      assert(result2 == 0x3FFF, f"Expected 0x3FFF, got 0x${result2}%04x")

      // Test case 3: -0.5 * 0.5 = -0.25
      // Q15: -0.5 = 0xC000 (-16384), -0.25 = 0xE000 (-8192)
      dut.io.op1.poke(0xC000.U)  // -0.5 in Q15
      dut.io.op2.poke(0x4000.U)  // 0.5 in Q15
      dut.clock.step(1)
      val result3 = dut.io.result.peek().litValue
      println(f"QMUL16: 0xC000 * 0x4000 = 0x${result3}%04x (expected 0xE000)")
      assert(result3 == 0xE000, f"Expected 0xE000, got 0x${result3}%04x")

      // Test case 4: -1.0 * -1.0 = 1.0
      // Q15: -1.0 = 0x8000 (-32768)
      dut.io.op1.poke(0x8000.U)  // -1.0 in Q15
      dut.io.op2.poke(0x8000.U)  // -1.0 in Q15
      dut.clock.step(1)
      val result4 = dut.io.result.peek().litValue
      println(f"QMUL16: 0x8000 * 0x8000 = 0x${result4}%04x (expected 0x8000)")
      // 0x8000 * 0x8000 = 0x40000000, >> 15 = 0x8000
      assert(result4 == 0x8000, f"Expected 0x8000, got 0x${result4}%04x")
    }
  }

  it should "perform Q15 multiply with rounding (QMUL16R)" in {
    test(new ALU) { dut =>
      dut.io.func.poke(ALUFunctions.qmul16r)

      // 0x4000 * 0x4001 = 0x2001 with rounding
      dut.io.op1.poke(0x4000.U)
      dut.io.op2.poke(0x4001.U)
      dut.clock.step(1)
      val result = dut.io.result.peek().litValue
      println(f"QMUL16R: 0x4000 * 0x4001 = 0x${result}%04x (expected 0x2001)")
      assert(result == 0x2001, f"Expected 0x2001, got 0x${result}%04x")

      // Negative rounding: -0.5 * 0.5 = -0.25
      dut.io.op1.poke(0xC000.U)
      dut.io.op2.poke(0x4000.U)
      dut.clock.step(1)
      val resultNeg = dut.io.result.peek().litValue
      println(f"QMUL16R: 0xC000 * 0x4000 = 0x${resultNeg}%04x (expected 0xE000)")
      assert(resultNeg == 0xE000, f"Expected 0xE000, got 0x${resultNeg}%04x")

      // Near-1.0 rounding: 0x7FFF * 0x4000 rounds up to 0x4000
      dut.io.op1.poke(0x7FFF.U)
      dut.io.op2.poke(0x4000.U)
      dut.clock.step(1)
      val resultNearOne = dut.io.result.peek().litValue
      println(f"QMUL16R: 0x7FFF * 0x4000 = 0x${resultNearOne}%04x (expected 0x4000)")
      assert(resultNearOne == 0x4000, f"Expected 0x4000, got 0x${resultNearOne}%04x")
    }
  }

  it should "perform 16-bit saturating shift-left (SSHL16)" in {
    test(new ALU) { dut =>
      dut.io.func.poke(ALUFunctions.sshl16)

      // 0x4000 << 1 should saturate to 0x7FFF
      dut.io.op1.poke(0x4000.U)
      dut.io.op2.poke(1.U)
      dut.clock.step(1)
      val result = dut.io.result.peek().litValue
      println(f"SSHL16: 0x4000 << 1 = 0x${result}%04x (expected 0x7FFF)")
      assert(result == 0x7FFF, f"Expected 0x7FFF, got 0x${result}%04x")

      // Non-saturating shift
      dut.io.op1.poke(0x1000.U)
      dut.io.op2.poke(2.U)
      dut.clock.step(1)
      val resultSmall = dut.io.result.peek().litValue
      println(f"SSHL16: 0x1000 << 2 = 0x${resultSmall}%04x (expected 0x4000)")
      assert(resultSmall == 0x4000, f"Expected 0x4000, got 0x${resultSmall}%04x")

      // Negative saturating shift
      dut.io.op1.poke(0x8000.U)
      dut.io.op2.poke(1.U)
      dut.clock.step(1)
      val resultNeg = dut.io.result.peek().litValue
      println(f"SSHL16: 0x8000 << 1 = 0x${resultNeg}%04x (expected 0x8000)")
      assert(resultNeg == 0x8000, f"Expected 0x8000, got 0x${resultNeg}%04x")
    }
  }

  it should "perform 16-bit saturating addition (SADD16)" in {
    test(new ALU) { dut =>
      dut.io.func.poke(ALUFunctions.sadd16)

      // Test case 1: Normal addition (no overflow)
      // 10000 + 5000 = 15000
      dut.io.op1.poke(10000.U)
      dut.io.op2.poke(5000.U)
      dut.clock.step(1)
      val result1 = dut.io.result.peek().litValue
      println(f"SADD16: 10000 + 5000 = $result1 (expected 15000)")
      assert(result1 == 15000, f"Expected 15000, got $result1")

      // Test case 2: Positive overflow (saturate to 32767)
      // 30000 + 10000 = 40000 -> saturate to 32767
      dut.io.op1.poke(30000.U)
      dut.io.op2.poke(10000.U)
      dut.clock.step(1)
      val result2 = dut.io.result.peek().litValue
      println(f"SADD16: 30000 + 10000 = $result2 (expected 32767, saturated)")
      assert(result2 == 32767, f"Expected 32767 (saturated), got $result2")

      // Test case 3: Negative overflow (saturate to -32768)
      // -30000 + -10000 = -40000 -> saturate to -32768
      dut.io.op1.poke((65536 - 30000).U)  // Two's complement: -30000 = 0x8AD0
      dut.io.op2.poke((65536 - 10000).U)  // Two's complement: -10000 = 0xD8F0
      dut.clock.step(1)
      val result3 = dut.io.result.peek().litValue.toInt
      val result3_signed = if (result3 > 32767) result3 - 65536 else result3
      println(f"SADD16: -30000 + -10000 = $result3_signed (expected -32768, saturated)")
      assert(result3_signed == -32768, f"Expected -32768 (saturated), got $result3_signed")

      // Test case 4: Mixed signs (no overflow)
      // 10000 + (-5000) = 5000
      dut.io.op1.poke(10000.U)
      dut.io.op2.poke((65536 - 5000).U)  // Two's complement: -5000 = 0xEC78
      dut.clock.step(1)
      val result4 = dut.io.result.peek().litValue
      println(f"SADD16: 10000 + (-5000) = $result4 (expected 5000)")
      assert(result4 == 5000, f"Expected 5000, got $result4")
    }
  }

  it should "perform 16-bit saturating subtraction (SSUB16)" in {
    test(new ALU) { dut =>
      dut.io.func.poke(ALUFunctions.ssub16)

      // Test case 1: Normal subtraction (no overflow)
      // 10000 - 5000 = 5000
      dut.io.op1.poke(10000.U)
      dut.io.op2.poke(5000.U)
      dut.clock.step(1)
      val result1 = dut.io.result.peek().litValue
      println(f"SSUB16: 10000 - 5000 = $result1 (expected 5000)")
      assert(result1 == 5000, f"Expected 5000, got $result1")

      // Test case 2: Positive overflow (saturate to 32767)
      // 30000 - (-10000) = 40000 -> saturate to 32767
      dut.io.op1.poke(30000.U)
      dut.io.op2.poke((65536 - 10000).U)  // Two's complement: -10000 = 0xD8F0
      dut.clock.step(1)
      val result2 = dut.io.result.peek().litValue
      println(f"SSUB16: 30000 - (-10000) = $result2 (expected 32767, saturated)")
      assert(result2 == 32767, f"Expected 32767 (saturated), got $result2")

      // Test case 3: Negative overflow (saturate to -32768)
      // -30000 - 10000 = -40000 -> saturate to -32768
      dut.io.op1.poke((65536 - 30000).U)  // Two's complement: -30000 = 0x8AD0
      dut.io.op2.poke(10000.U)
      dut.clock.step(1)
      val result3 = dut.io.result.peek().litValue.toInt
      val result3_signed = if (result3 > 32767) result3 - 65536 else result3
      println(f"SSUB16: -30000 - 10000 = $result3_signed (expected -32768, saturated)")
      assert(result3_signed == -32768, f"Expected -32768 (saturated), got $result3_signed")

      // Test case 4: Result is negative (no overflow)
      // 5000 - 10000 = -5000
      dut.io.op1.poke(5000.U)
      dut.io.op2.poke(10000.U)
      dut.clock.step(1)
      val result4 = dut.io.result.peek().litValue.toInt
      val result4_signed = if (result4 > 32767) result4 - 65536 else result4
      println(f"SSUB16: 5000 - 10000 = $result4_signed (expected -5000)")
      assert(result4_signed == -5000, f"Expected -5000, got $result4_signed")
    }
  }

  it should "perform 32-bit saturating add/sub (SADD32/SSUB32)" in {
    test(new ALU) { dut =>
      def toU32(v: Long): BigInt = if (v < 0) (BigInt(1) << 32) + v else BigInt(v)

      // SADD32 normal
      dut.io.func.poke(ALUFunctions.sadd32)
      dut.io.op1.poke(toU32(0x10000000L).U)
      dut.io.op2.poke(toU32(0x20000000L).U)
      dut.clock.step(1)
      val saddNormal = dut.io.result.peek().litValue
      println(f"SADD32: 0x10000000 + 0x20000000 = 0x$saddNormal%08x (expected 0x30000000)")
      assert(saddNormal == 0x30000000L, f"SADD32 normal expected 0x30000000, got 0x$saddNormal%08x")

      // SADD32 positive overflow
      dut.io.op1.poke(toU32(0x60000000L).U)
      dut.io.op2.poke(toU32(0x60000000L).U)
      dut.clock.step(1)
      val saddPos = dut.io.result.peek().litValue
      println(f"SADD32: 0x60000000 + 0x60000000 = 0x$saddPos%08x (expected 0x7FFFFFFF)")
      assert(saddPos == 0x7FFFFFFFL, f"SADD32 pos overflow expected 0x7FFFFFFF, got 0x$saddPos%08x")

      // SADD32 negative overflow
      dut.io.op1.poke(toU32(0x80000000L).U)
      dut.io.op2.poke(toU32(-1L).U)
      dut.clock.step(1)
      val saddNeg = dut.io.result.peek().litValue
      println(f"SADD32: 0x80000000 + 0xFFFFFFFF = 0x$saddNeg%08x (expected 0x80000000)")
      assert(saddNeg == 0x80000000L, f"SADD32 neg overflow expected 0x80000000, got 0x$saddNeg%08x")

      // SSUB32 normal
      dut.io.func.poke(ALUFunctions.ssub32)
      dut.io.op1.poke(toU32(0x30000000L).U)
      dut.io.op2.poke(toU32(0x10000000L).U)
      dut.clock.step(1)
      val ssubNormal = dut.io.result.peek().litValue
      println(f"SSUB32: 0x30000000 - 0x10000000 = 0x$ssubNormal%08x (expected 0x20000000)")
      assert(ssubNormal == 0x20000000L, f"SSUB32 normal expected 0x20000000, got 0x$ssubNormal%08x")

      // SSUB32 positive overflow
      dut.io.op1.poke(toU32(0x7FFFFFFFL).U)
      dut.io.op2.poke(toU32(-1L).U)
      dut.clock.step(1)
      val ssubPos = dut.io.result.peek().litValue
      println(f"SSUB32: 0x7FFFFFFF - 0xFFFFFFFF = 0x$ssubPos%08x (expected 0x7FFFFFFF)")
      assert(ssubPos == 0x7FFFFFFFL, f"SSUB32 pos overflow expected 0x7FFFFFFF, got 0x$ssubPos%08x")

      // SSUB32 negative overflow
      dut.io.op1.poke(toU32(0x80000000L).U)
      dut.io.op2.poke(toU32(1L).U)
      dut.clock.step(1)
      val ssubNeg = dut.io.result.peek().litValue
      println(f"SSUB32: 0x80000000 - 0x00000001 = 0x$ssubNeg%08x (expected 0x80000000)")
      assert(ssubNeg == 0x80000000L, f"SSUB32 neg overflow expected 0x80000000, got 0x$ssubNeg%08x")
    }
  }

  it should "perform DIV/REM operations (RV32M)" in {
    test(new Divider) { dut =>
      def runOp(op1: BigInt, op2: BigInt, funct3: Int): BigInt = {
        dut.io.start.poke(false.B)
        dut.clock.step(1)
        dut.io.op1.poke(op1.U)
        dut.io.op2.poke(op2.U)
        dut.io.funct3.poke(funct3.U)
        dut.io.start.poke(true.B)
        dut.clock.step(1)
        dut.io.start.poke(false.B)
        var cycles = 0
        while (dut.io.valid.peek().litToBoolean == false && cycles < 20) {
          dut.clock.step(1)
          cycles += 1
        }
        assert(dut.io.valid.peek().litToBoolean, "Divider did not assert valid")
        dut.io.result.peek().litValue
      }

      // DIV: 100 / 7 = 14
      val divRes = runOp(100, 7, InstructionsTypeM.div.litValue.toInt)
      println(f"DIV: 100 / 7 = 0x$divRes%08x (expected 0x0000000e)")
      assert(divRes == 14, s"DIV expected 14, got $divRes")

      // REM: 100 % 7 = 2
      val remRes = runOp(100, 7, InstructionsTypeM.rem.litValue.toInt)
      println(f"REM: 100 %% 7 = 0x$remRes%08x (expected 0x00000002)")
      assert(remRes == 2, s"REM expected 2, got $remRes")

      // DIVU: 100 / 7 = 14
      val divuRes = runOp(100, 7, InstructionsTypeM.divu.litValue.toInt)
      println(f"DIVU: 100 / 7 = 0x$divuRes%08x (expected 0x0000000e)")
      assert(divuRes == 14, s"DIVU expected 14, got $divuRes")

      // REMU: 100 % 7 = 2
      val remuRes = runOp(100, 7, InstructionsTypeM.remu.litValue.toInt)
      println(f"REMU: 100 %% 7 = 0x$remuRes%08x (expected 0x00000002)")
      assert(remuRes == 2, s"REMU expected 2, got $remuRes")

      // DIV by zero: result = -1 (0xFFFFFFFF)
      val divByZero = runOp(123, 0, InstructionsTypeM.div.litValue.toInt)
      println(f"DIV: 123 / 0 = 0x$divByZero%08x (expected 0xffffffff)")
      assert(divByZero == 0xFFFFFFFFL, f"DIV by zero expected 0xFFFFFFFF, got 0x$divByZero%08x")

      // REM by zero: result = dividend
      val remByZero = runOp(123, 0, InstructionsTypeM.rem.litValue.toInt)
      println(f"REM: 123 %% 0 = 0x$remByZero%08x (expected 0x0000007b)")
      assert(remByZero == 123, s"REM by zero expected 123, got $remByZero")

      // DIV overflow: 0x80000000 / -1 = 0x80000000
      val divOverflow = runOp(0x80000000L, 0xFFFFFFFFL, InstructionsTypeM.div.litValue.toInt)
      println(f"DIV: 0x80000000 / 0xFFFFFFFF = 0x$divOverflow%08x (expected 0x80000000)")
      assert(divOverflow == 0x80000000L, f"DIV overflow expected 0x80000000, got 0x$divOverflow%08x")
    }
  }
}
