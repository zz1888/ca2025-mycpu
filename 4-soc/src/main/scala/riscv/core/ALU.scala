// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package riscv.core

import chisel3._
import chisel3.util._
import riscv.Parameters

/**
 * ALU function encoding. Maps to RV32I instruction funct3/funct7 combinations.
 *
 * The ALUControl module translates instruction encoding to these operations.
 * Special case 'zero' produces constant 0 for non-ALU instructions that still
 * flow through the execute stage (e.g., stores use ALU for address calculation
 * but don't need a separate zero output).
 * 
 * M-extension functions (mul, mulh, mulhsu, mulhu) are handled by the
 * separate Multiplier module, not by the ALU directly.
 */
object ALUFunctions extends ChiselEnum {
  val zero, add, sub, sll, slt, xor, or, and, srl, sra, sltu,
      mul, mulh, mulhsu, mulhu, div, divu, rem, remu,
      qmul16, sadd16, ssub16, sadd32, ssub32,
      qmul16r, sshl16, qmul32x16 = Value
}

/**
 * Arithmetic Logic Unit: combinational compute engine for the Execute stage.
 *
 * Implements all RV32I integer operations in a single cycle:
 * - Arithmetic: ADD (also used for address calculation), SUB
 * - Logical: AND, OR, XOR (bitwise operations)
 * - Shift: SLL (left), SRL (logical right), SRA (arithmetic right, sign-extends)
 * - Compare: SLT (signed), SLTU (unsigned) - output 1 if op1 < op2, else 0
 *
 * RV32M multiplication operations are NOT handled here - they use the
 * separate Multiplier module for multi-cycle execution. The ALU simply
 * passes through the multiplier result when func is mul/mulh/mulhsu/mulhu.
 *
 * Shift amounts use only lower 5 bits of op2 (RISC-V spec: shamt[4:0]).
 * Comparison results are 1-bit values zero-extended to 32 bits.
 *
 * Critical path: SLT/SLTU comparisons and SRA (requires sign extension logic).
 */
class ALU extends Module {
  val io = IO(new Bundle {
    val func = Input(ALUFunctions())

    val op1 = Input(UInt(Parameters.DataWidth))
    val op2 = Input(UInt(Parameters.DataWidth))

    val result = Output(UInt(Parameters.DataWidth))
    
    // Multiplier/divider passthrough (M-extension handled externally)
    val mul_result = Input(UInt(Parameters.DataWidth))
    val div_result = Input(UInt(Parameters.DataWidth))
  })

  io.result := 0.U
  switch(io.func) {
    is(ALUFunctions.add) {
      io.result := io.op1 + io.op2
    }
    is(ALUFunctions.sub) {
      io.result := io.op1 - io.op2
    }
    is(ALUFunctions.sll) {
      io.result := io.op1 << io.op2(4, 0)
    }
    is(ALUFunctions.slt) {
      io.result := io.op1.asSInt < io.op2.asSInt
    }
    is(ALUFunctions.xor) {
      io.result := io.op1 ^ io.op2
    }
    is(ALUFunctions.or) {
      io.result := io.op1 | io.op2
    }
    is(ALUFunctions.and) {
      io.result := io.op1 & io.op2
    }
    is(ALUFunctions.srl) {
      io.result := io.op1 >> io.op2(4, 0)
    }
    is(ALUFunctions.sra) {
      io.result := (io.op1.asSInt >> io.op2(4, 0)).asUInt
    }
    is(ALUFunctions.sltu) {
      io.result := io.op1 < io.op2
    }
    // M-extension: passthrough multiplier result
    is(ALUFunctions.mul, ALUFunctions.mulh, ALUFunctions.mulhsu, ALUFunctions.mulhu) {
      io.result := io.mul_result
    }
    is(ALUFunctions.div, ALUFunctions.divu, ALUFunctions.rem, ALUFunctions.remu) {
      io.result := io.div_result
    }
    // DSP extension: Q15 multiply and saturating arithmetic
    is(ALUFunctions.qmul16) {
      // Q15 fixed-point multiply: (a * b) >> 15
      // Extract lower 16 bits as signed values
      val a = io.op1(15, 0).asSInt
      val b = io.op2(15, 0).asSInt
      // 16-bit × 16-bit = 32-bit product
      val product = (a * b).asUInt
      // Right shift by 15 to maintain Q15 format, sign-extend to 32 bits
      val result16 = (product >> 15)(15, 0).asSInt
      io.result := result16.asUInt
    }
    is(ALUFunctions.qmul16r) {
      // Q15 multiply with rounding: (a * b + 0x4000) >> 15
      val a = io.op1(15, 0).asSInt
      val b = io.op2(15, 0).asSInt
      val product = (a * b).asSInt
      val roundPos = (1 << 14).S(32.W)
      val roundNeg = ((1 << 14) - 1).S(32.W)
      val rounded = Mux(product >= 0.S, product + roundPos, product + roundNeg)
      val result16 = (rounded >> 15)(15, 0).asSInt
      io.result := result16.asUInt
    }
    is(ALUFunctions.sadd16) {
      // 16-bit saturating add
      val a = io.op1(15, 0).asSInt
      val b = io.op2(15, 0).asSInt
      val sum = a +& b  // 17-bit result to detect overflow
      // Saturate to [-32768, 32767]
      val saturated = Mux(sum > 32767.S, 32767.S,
                      Mux(sum < -32768.S, -32768.S, sum(15, 0).asSInt))
      // Sign-extend to 32 bits
      io.result := saturated.asUInt
    }
    is(ALUFunctions.ssub16) {
      // 16-bit saturating subtract
      val a = io.op1(15, 0).asSInt
      val b = io.op2(15, 0).asSInt
      val diff = a -& b  // 17-bit result to detect overflow
      // Saturate to [-32768, 32767]
      val saturated = Mux(diff > 32767.S, 32767.S,
                      Mux(diff < -32768.S, -32768.S, diff(15, 0).asSInt))
      // Sign-extend to 32 bits
      io.result := saturated.asUInt
    }
    is(ALUFunctions.sadd32) {
      val a = io.op1.asSInt
      val b = io.op2.asSInt
      val sumWide = a +& b
      val sum = sumWide(31, 0).asSInt
      val overflow = (a(31) === b(31)) && (sum(31) =/= a(31))
      val max = 0x7FFFFFFF.S(32.W)
      val min = (-0x80000000L).S(32.W)
      val saturated = Mux(overflow, Mux(a(31), min, max), sum)
      io.result := saturated.asUInt
    }
    is(ALUFunctions.ssub32) {
      val a = io.op1.asSInt
      val b = io.op2.asSInt
      val diffWide = a -& b
      val diff = diffWide(31, 0).asSInt
      val overflow = (a(31) =/= b(31)) && (diff(31) =/= a(31))
      val max = 0x7FFFFFFF.S(32.W)
      val min = (-0x80000000L).S(32.W)
      val saturated = Mux(overflow, Mux(a(31), min, max), diff)
      io.result := saturated.asUInt
    }
    is(ALUFunctions.sshl16) {
      // 16-bit saturating shift-left by shamt[4:0]
      val a = io.op1(15, 0).asSInt
      val shamt = io.op2(4, 0)
      val a32 = a.asTypeOf(SInt(32.W))
      val shifted = (a32 << shamt).asSInt
      val max = 32767.S(32.W)
      val min = (-32768).S(32.W)
      val saturated = Mux(shifted > max, max, Mux(shifted < min, min, shifted))
      io.result := saturated(15, 0).asSInt.asUInt
    }
    is(ALUFunctions.qmul32x16) {
      // Q15 32x16 multiply: (op1[31:0] * op2[15:0]) >> 15
      // Used in picosynth for: ((int64_t) state * coeff) >> 15
      val a = io.op1.asSInt                // 32-bit signed operand
      val b = io.op2(15, 0).asSInt         // 16-bit signed operand (sign-extended)
      val product = (a * b).asUInt         // 48-bit product
      // Right shift by 15, keep lower 32 bits
      io.result := (product >> 15)(31, 0)
    }
  }
}
