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
      dut.io.func.poke(ALUFunctions.qmul16)

      // Test case 1: 0.5 * 0.5 = 0.25
      // Q15: 0.5 = 0x4000 (16384), 0.25 = 0x2000 (8192)
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

      // Edge case 5: Zero multiplication
      dut.io.op1.poke(0x0.U)
      dut.io.op2.poke(0x7FFF.U)
      dut.clock.step(1)
      val resultZero1 = dut.io.result.peek().litValue
      println(f"QMUL16: 0x0000 * 0x7FFF = 0x${resultZero1}%04x (expected 0x0000)")
      assert(resultZero1 == 0, f"Expected 0x0000, got 0x${resultZero1}%04x")

      dut.io.op1.poke(0x7FFF.U)
      dut.io.op2.poke(0x0.U)
      dut.clock.step(1)
      val resultZero2 = dut.io.result.peek().litValue
      println(f"QMUL16: 0x7FFF * 0x0000 = 0x${resultZero2}%04x (expected 0x0000)")
      assert(resultZero2 == 0, f"Expected 0x0000, got 0x${resultZero2}%04x")

      // Edge case 6: Q15_MAX * Q15_MAX (~1.0 * ~1.0)
      dut.io.op1.poke(0x7FFF.U)
      dut.io.op2.poke(0x7FFF.U)
      dut.clock.step(1)
      val resultMaxMax = dut.io.result.peek().litValue
      // 0x7FFF * 0x7FFF = 0x3FFF0001, >> 15 = 0x7FFE
      println(f"QMUL16: 0x7FFF * 0x7FFF = 0x${resultMaxMax}%04x (expected 0x7FFE)")
      assert(resultMaxMax == 0x7FFE, f"Expected 0x7FFE, got 0x${resultMaxMax}%04x")

      // Edge case 7: Q15_MIN * Q15_MAX (-1.0 * ~1.0)
      dut.io.op1.poke(0x8000.U)
      dut.io.op2.poke(0x7FFF.U)
      dut.clock.step(1)
      val resultMinMax = dut.io.result.peek().litValue
      // -32768 * 32767 = -1073709056, >> 15 = -32767 = 0x8001
      println(f"QMUL16: 0x8000 * 0x7FFF = 0x${resultMinMax}%04x (expected 0x8001)")
      assert(resultMinMax == 0x8001, f"Expected 0x8001, got 0x${resultMinMax}%04x")

      // Edge case 8: Small values (precision test)
      dut.io.op1.poke(0x0001.U)  // Smallest positive Q15
      dut.io.op2.poke(0x0001.U)
      dut.clock.step(1)
      val resultSmall = dut.io.result.peek().litValue
      // 1 * 1 = 1, >> 15 = 0
      println(f"QMUL16: 0x0001 * 0x0001 = 0x${resultSmall}%04x (expected 0x0000)")
      assert(resultSmall == 0, f"Expected 0x0000, got 0x${resultSmall}%04x")

      // Edge case 9: -0.5 * -0.5 = 0.25
      dut.io.op1.poke(0xC000.U)  // -0.5
      dut.io.op2.poke(0xC000.U)  // -0.5
      dut.clock.step(1)
      val resultNegNeg = dut.io.result.peek().litValue
      // -16384 * -16384 = 268435456, >> 15 = 8192 = 0x2000
      println(f"QMUL16: 0xC000 * 0xC000 = 0x${resultNegNeg}%04x (expected 0x2000)")
      assert(resultNegNeg == 0x2000, f"Expected 0x2000, got 0x${resultNegNeg}%04x")
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

      // REM overflow: 0x80000000 % -1 = 0
      val remOverflow = runOp(0x80000000L, 0xFFFFFFFFL, InstructionsTypeM.rem.litValue.toInt)
      println(f"REM: 0x80000000 %% 0xFFFFFFFF = 0x$remOverflow%08x (expected 0x00000000)")
      assert(remOverflow == 0L, f"REM overflow expected 0x00000000, got 0x$remOverflow%08x")

      // Negative dividend / positive divisor: -100 / 7 = -14
      val divNegPos = runOp(0xFFFFFF9CL, 7, InstructionsTypeM.div.litValue.toInt)
      println(f"DIV: -100 / 7 = 0x$divNegPos%08x (expected 0xFFFFFFF2 = -14)")
      assert(divNegPos == 0xFFFFFFF2L, f"DIV -100/7 expected 0xFFFFFFF2, got 0x$divNegPos%08x")

      // Negative dividend % positive divisor: -100 % 7 = -2
      val remNegPos = runOp(0xFFFFFF9CL, 7, InstructionsTypeM.rem.litValue.toInt)
      println(f"REM: -100 %% 7 = 0x$remNegPos%08x (expected 0xFFFFFFFE = -2)")
      assert(remNegPos == 0xFFFFFFFEL, f"REM -100%%7 expected 0xFFFFFFFE, got 0x$remNegPos%08x")

      // Negative dividend / negative divisor: -100 / -7 = 14
      val divNegNeg = runOp(0xFFFFFF9CL, 0xFFFFFFF9L, InstructionsTypeM.div.litValue.toInt)
      println(f"DIV: -100 / -7 = 0x$divNegNeg%08x (expected 0x0000000E = 14)")
      assert(divNegNeg == 14L, f"DIV -100/-7 expected 0x0000000E, got 0x$divNegNeg%08x")

      // Negative dividend % negative divisor: -100 % -7 = -2
      val remNegNeg = runOp(0xFFFFFF9CL, 0xFFFFFFF9L, InstructionsTypeM.rem.litValue.toInt)
      println(f"REM: -100 %% -7 = 0x$remNegNeg%08x (expected 0xFFFFFFFE = -2)")
      assert(remNegNeg == 0xFFFFFFFEL, f"REM -100%%-7 expected 0xFFFFFFFE, got 0x$remNegNeg%08x")

      // Positive dividend / negative divisor: 100 / -7 = -14
      val divPosNeg = runOp(100, 0xFFFFFFF9L, InstructionsTypeM.div.litValue.toInt)
      println(f"DIV: 100 / -7 = 0x$divPosNeg%08x (expected 0xFFFFFFF2 = -14)")
      assert(divPosNeg == 0xFFFFFFF2L, f"DIV 100/-7 expected 0xFFFFFFF2, got 0x$divPosNeg%08x")

      // DIVU with large unsigned: 0xFFFFFFFF / 2 = 0x7FFFFFFF
      val divuLarge = runOp(0xFFFFFFFFL, 2, InstructionsTypeM.divu.litValue.toInt)
      println(f"DIVU: 0xFFFFFFFF / 2 = 0x$divuLarge%08x (expected 0x7FFFFFFF)")
      assert(divuLarge == 0x7FFFFFFFL, f"DIVU large expected 0x7FFFFFFF, got 0x$divuLarge%08x")

      // REMU with large unsigned: 0xFFFFFFFF % 2 = 1
      val remuLarge = runOp(0xFFFFFFFFL, 2, InstructionsTypeM.remu.litValue.toInt)
      println(f"REMU: 0xFFFFFFFF %% 2 = 0x$remuLarge%08x (expected 0x00000001)")
      assert(remuLarge == 1L, f"REMU large expected 0x00000001, got 0x$remuLarge%08x")

      // DIVU by zero: result = 0xFFFFFFFF
      val divuByZero = runOp(123, 0, InstructionsTypeM.divu.litValue.toInt)
      println(f"DIVU: 123 / 0 = 0x$divuByZero%08x (expected 0xFFFFFFFF)")
      assert(divuByZero == 0xFFFFFFFFL, f"DIVU by zero expected 0xFFFFFFFF, got 0x$divuByZero%08x")

      // REMU by zero: result = dividend
      val remuByZero = runOp(456, 0, InstructionsTypeM.remu.litValue.toInt)
      println(f"REMU: 456 %% 0 = 0x$remuByZero%08x (expected 0x000001C8)")
      assert(remuByZero == 456L, s"REMU by zero expected 456, got $remuByZero")

      // 1 / 1 = 1
      val divOne = runOp(1, 1, InstructionsTypeM.div.litValue.toInt)
      println(f"DIV: 1 / 1 = 0x$divOne%08x (expected 0x00000001)")
      assert(divOne == 1L, s"DIV 1/1 expected 1, got $divOne")

      // 0 / 1 = 0
      val divZeroNum = runOp(0, 1, InstructionsTypeM.div.litValue.toInt)
      println(f"DIV: 0 / 1 = 0x$divZeroNum%08x (expected 0x00000000)")
      assert(divZeroNum == 0L, s"DIV 0/1 expected 0, got $divZeroNum")

      // x / x = 1
      val divSame = runOp(12345, 12345, InstructionsTypeM.div.litValue.toInt)
      println(f"DIV: 12345 / 12345 = 0x$divSame%08x (expected 0x00000001)")
      assert(divSame == 1L, s"DIV x/x expected 1, got $divSame")

      // x % x = 0
      val remSame = runOp(12345, 12345, InstructionsTypeM.rem.litValue.toInt)
      println(f"REM: 12345 %% 12345 = 0x$remSame%08x (expected 0x00000000)")
      assert(remSame == 0L, s"REM x%%x expected 0, got $remSame")
    }
  }

  it should "perform Q15 32x16 multiply (QMUL32x16)" in {
    test(new ALU) { dut =>
      dut.io.func.poke(ALUFunctions.qmul32x16)

      // Test case 1: 0x00010000 * 0x4000 >> 15 = 0x00008000
      // (65536 * 16384) >> 15 = 32768
      dut.io.op1.poke(0x00010000L.U)  // 65536 (32-bit)
      dut.io.op2.poke(0x4000.U)       // 0.5 in Q15 (16-bit)
      dut.clock.step(1)
      val result1 = dut.io.result.peek().litValue
      println(s"QMUL32x16: 0x00010000 * 0x4000 = 0x${result1.toString(16)} (expected 0x8000)")
      assert(result1 == 0x8000L, s"Expected 0x8000, got 0x${result1.toString(16)}")

      // Test case 2: Large 32-bit * Q15 0.5
      // 0x40000000 * 0x4000 >> 15 = 0x20000000
      dut.io.op1.poke(0x40000000L.U)
      dut.io.op2.poke(0x4000.U)
      dut.clock.step(1)
      val result2 = dut.io.result.peek().litValue
      println(s"QMUL32x16: 0x40000000 * 0x4000 = 0x${result2.toString(16)} (expected 0x20000000)")
      assert(result2 == 0x20000000L, s"Expected 0x20000000, got 0x${result2.toString(16)}")

      // Test case 3: Negative 32-bit * positive Q15
      // -65536 * 0.5 = -32768 (0xFFFF8000)
      dut.io.op1.poke(0xFFFF0000L.U)  // -65536
      dut.io.op2.poke(0x4000.U)       // 0.5
      dut.clock.step(1)
      val result3 = dut.io.result.peek().litValue
      println(s"QMUL32x16: 0xFFFF0000 * 0x4000 = 0x${result3.toString(16)} (expected 0xFFFF8000)")
      assert(result3 == 0xFFFF8000L, s"Expected 0xFFFF8000, got 0x${result3.toString(16)}")

      // Test case 4: Positive 32-bit * negative Q15
      // 65536 * -0.5 = -32768
      dut.io.op1.poke(0x00010000L.U)
      dut.io.op2.poke(0xC000.U)  // -0.5 in Q15
      dut.clock.step(1)
      val result4 = dut.io.result.peek().litValue
      println(s"QMUL32x16: 0x00010000 * 0xC000 = 0x${result4.toString(16)} (expected 0xFFFF8000)")
      assert(result4 == 0xFFFF8000L, s"Expected 0xFFFF8000, got 0x${result4.toString(16)}")

      // Edge case 5: Zero multiplication
      dut.io.op1.poke(0x0L.U)
      dut.io.op2.poke(0x7FFF.U)
      dut.clock.step(1)
      val resultZero1 = dut.io.result.peek().litValue
      println(s"QMUL32x16: 0x00000000 * 0x7FFF = 0x${resultZero1.toString(16)} (expected 0x0)")
      assert(resultZero1 == 0L, s"Expected 0x0, got 0x${resultZero1.toString(16)}")

      dut.io.op1.poke(0x7FFFFFFFL.U)
      dut.io.op2.poke(0x0.U)
      dut.clock.step(1)
      val resultZero2 = dut.io.result.peek().litValue
      println(s"QMUL32x16: 0x7FFFFFFF * 0x0000 = 0x${resultZero2.toString(16)} (expected 0x0)")
      assert(resultZero2 == 0L, s"Expected 0x0, got 0x${resultZero2.toString(16)}")

      // Edge case 6: INT32_MAX * Q15_MAX
      // 0x7FFFFFFF * 0x7FFF >> 15 = 2147483647 * 32767 >> 15 = 0x7FFEFFFF
      dut.io.op1.poke(0x7FFFFFFFL.U)
      dut.io.op2.poke(0x7FFF.U)
      dut.clock.step(1)
      val resultMaxMax = dut.io.result.peek().litValue
      println(s"QMUL32x16: 0x7FFFFFFF * 0x7FFF = 0x${resultMaxMax.toString(16)} (expected 0x7FFEFFFF)")
      assert(resultMaxMax == 0x7FFEFFFFL, s"Expected 0x7FFEFFFF, got 0x${resultMaxMax.toString(16)}")

      // Edge case 7: INT32_MIN * Q15_MAX
      // 0x80000000 * 0x7FFF >> 15 = -2147483648 * 32767 >> 15 = 0x80010000
      dut.io.op1.poke(0x80000000L.U)
      dut.io.op2.poke(0x7FFF.U)
      dut.clock.step(1)
      val resultMinMax = dut.io.result.peek().litValue
      println(s"QMUL32x16: 0x80000000 * 0x7FFF = 0x${resultMinMax.toString(16)} (expected 0x80010000)")
      assert(resultMinMax == 0x80010000L, s"Expected 0x80010000, got 0x${resultMinMax.toString(16)}")

      // Edge case 8: INT32_MIN * Q15_MIN (-1.0 * -1.0)
      // 0x80000000 * 0x8000 >> 15 = overflow case
      dut.io.op1.poke(0x80000000L.U)
      dut.io.op2.poke(0x8000.U)
      dut.clock.step(1)
      val resultMinMin = dut.io.result.peek().litValue
      println(s"QMUL32x16: 0x80000000 * 0x8000 = 0x${resultMinMin.toString(16)} (expected 0x80000000)")
      assert(resultMinMin == 0x80000000L, s"Expected 0x80000000, got 0x${resultMinMin.toString(16)}")

      // Edge case 9: Negative 32-bit * negative Q15 = positive
      // -1 * -16384 >> 15 = 16384 >> 15 = 0
      dut.io.op1.poke(0xFFFFFFFFL.U)  // -1
      dut.io.op2.poke(0xC000.U)       // -0.5
      dut.clock.step(1)
      val resultNegNeg = dut.io.result.peek().litValue
      println(s"QMUL32x16: 0xFFFFFFFF * 0xC000 = 0x${resultNegNeg.toString(16)} (expected 0x0)")
      assert(resultNegNeg == 0L, s"Expected 0x0, got 0x${resultNegNeg.toString(16)}")

      // Edge case 10: 1 * Q15 (identity-ish)
      dut.io.op1.poke(0x00008000L.U)  // 32768 (1.0 in <<15 format)
      dut.io.op2.poke(0x6000.U)       // 0.75 in Q15
      dut.clock.step(1)
      val resultIdentity = dut.io.result.peek().litValue
      // 32768 * 24576 >> 15 = 24576 = 0x6000
      println(s"QMUL32x16: 0x00008000 * 0x6000 = 0x${resultIdentity.toString(16)} (expected 0x6000)")
      assert(resultIdentity == 0x6000L, s"Expected 0x6000, got 0x${resultIdentity.toString(16)}")
    }
  }

  it should "perform 64-bit division" in {
    test(new Divider) { dut =>
      def run64Op(op1Low: BigInt, op1High: BigInt, op2Low: BigInt, op2High: BigInt, funct3: Int): (BigInt, BigInt) = {
        dut.io.start.poke(false.B)
        dut.clock.step(1)
        dut.io.op1.poke(op1Low.U)
        dut.io.op1_high.poke(op1High.U)
        dut.io.op2.poke(op2Low.U)
        dut.io.op2_high.poke(op2High.U)
        dut.io.funct3.poke(funct3.U)
        dut.io.use_64.poke(true.B)
        dut.io.start.poke(true.B)
        dut.clock.step(1)
        dut.io.start.poke(false.B)
        var cycles = 0
        while (dut.io.valid.peek().litToBoolean == false && cycles < 30) {
          dut.clock.step(1)
          cycles += 1
        }
        assert(dut.io.valid.peek().litToBoolean, "Divider did not assert valid")
        (dut.io.result.peek().litValue, dut.io.result_high.peek().litValue)
      }

      // Test: 0x100000000 / 7 = 0x24924924 (64-bit division)
      // Input: 0x0000000100000000
      val (resLow, resHigh) = run64Op(0L, 1L, 7L, 0L, 5) // DIVU
      val fullResult = (resHigh << 32) | resLow
      println(s"DIV64: 0x100000000 / 7 = 0x${fullResult.toString(16)}")
      // 4294967296 / 7 = 613566756 = 0x24924924
      assert(fullResult == 613566756L, s"Expected 613566756, got $fullResult")

      // Test: 0x100000000 % 7 = 4
      val (remLow, remHigh) = run64Op(0L, 1L, 7L, 0L, 7) // REMU
      val fullRem = (remHigh << 32) | remLow
      println(s"REM64: 0x100000000 % 7 = 0x${fullRem.toString(16)} (expected 4)")
      assert(fullRem == 4L, s"Expected 4, got $fullRem")

      // 64-bit divide by zero: result = 0xFFFFFFFFFFFFFFFF
      val (divZeroLow, divZeroHigh) = run64Op(123L, 456L, 0L, 0L, 5) // DIVU
      val divZeroResult = (divZeroHigh << 32) | divZeroLow
      println(s"DIV64: 0x1C800000007B / 0 = 0x${divZeroResult.toString(16)} (expected 0xFFFFFFFFFFFFFFFF)")
      assert(divZeroResult == BigInt("FFFFFFFFFFFFFFFF", 16), s"DIV64 by zero expected all 1s, got 0x${divZeroResult.toString(16)}")

      // 64-bit remainder by zero: result = dividend
      val (remZeroLow, remZeroHigh) = run64Op(123L, 456L, 0L, 0L, 7) // REMU
      val remZeroResult = (remZeroHigh << 32) | remZeroLow
      val expectedRem = (BigInt(456) << 32) | 123
      println(s"REM64: 0x${expectedRem.toString(16)} % 0 = 0x${remZeroResult.toString(16)} (expected dividend)")
      assert(remZeroResult == expectedRem, s"REM64 by zero expected dividend, got 0x${remZeroResult.toString(16)}")

      // Large 64-bit / large 64-bit: 0xFFFFFFFFFFFFFFFF / 0x100000000 = 0xFFFFFFFF
      val (largeDivLow, largeDivHigh) = run64Op(0xFFFFFFFFL, 0xFFFFFFFFL, 0L, 1L, 5) // DIVU
      val largeDivResult = (largeDivHigh << 32) | largeDivLow
      println(s"DIV64: 0xFFFFFFFFFFFFFFFF / 0x100000000 = 0x${largeDivResult.toString(16)} (expected 0xFFFFFFFF)")
      assert(largeDivResult == 0xFFFFFFFFL, s"DIV64 large expected 0xFFFFFFFF, got 0x${largeDivResult.toString(16)}")

      // 64-bit exact division (no remainder): 0x200000000 / 2 = 0x100000000
      val (exactDivLow, exactDivHigh) = run64Op(0L, 2L, 2L, 0L, 5) // DIVU
      val exactDivResult = (exactDivHigh << 32) | exactDivLow
      println(s"DIV64: 0x200000000 / 2 = 0x${exactDivResult.toString(16)} (expected 0x100000000)")
      assert(exactDivResult == BigInt("100000000", 16), s"DIV64 exact expected 0x100000000, got 0x${exactDivResult.toString(16)}")

      // 64-bit exact division remainder = 0
      val (exactRemLow, exactRemHigh) = run64Op(0L, 2L, 2L, 0L, 7) // REMU
      val exactRemResult = (exactRemHigh << 32) | exactRemLow
      println(s"REM64: 0x200000000 % 2 = 0x${exactRemResult.toString(16)} (expected 0x0)")
      assert(exactRemResult == 0L, s"REM64 exact expected 0, got 0x${exactRemResult.toString(16)}")

      // 1 / 1 = 1 (64-bit)
      val (oneOneLow, oneOneHigh) = run64Op(1L, 0L, 1L, 0L, 5) // DIVU
      val oneOneResult = (oneOneHigh << 32) | oneOneLow
      println(s"DIV64: 1 / 1 = 0x${oneOneResult.toString(16)} (expected 0x1)")
      assert(oneOneResult == 1L, s"DIV64 1/1 expected 1, got $oneOneResult")

      // Small dividend / large divisor = 0
      val (smallLargeLow, smallLargeHigh) = run64Op(100L, 0L, 0L, 1L, 5) // DIVU: 100 / 0x100000000
      val smallLargeResult = (smallLargeHigh << 32) | smallLargeLow
      println(s"DIV64: 100 / 0x100000000 = 0x${smallLargeResult.toString(16)} (expected 0x0)")
      assert(smallLargeResult == 0L, s"DIV64 small/large expected 0, got $smallLargeResult")

      // Small dividend % large divisor = dividend
      val (smallLargeRemLow, smallLargeRemHigh) = run64Op(100L, 0L, 0L, 1L, 7) // REMU
      val smallLargeRemResult = (smallLargeRemHigh << 32) | smallLargeRemLow
      println(s"REM64: 100 % 0x100000000 = 0x${smallLargeRemResult.toString(16)} (expected 100)")
      assert(smallLargeRemResult == 100L, s"REM64 small%%large expected 100, got $smallLargeRemResult")
    }
  }
}
