// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package riscv.core

import chisel3._
import chisel3.util._
import riscv.Parameters

/**
 * Multi-cycle divider for RV32M extension (DIV/DIVU/REM/REMU)
 *
 * - DIV  (100): signed division
 * - DIVU (101): unsigned division
 * - REM  (110): signed remainder
 * - REMU (111): unsigned remainder
 *
 * Timing:
 * - Cycle 0: Start asserted, inputs latched
 * - Cycle 1..N: Busy
 * - Cycle N+1: valid asserted for 1 cycle
 */
class Divider extends Module {
  val io = IO(new Bundle {
    val start  = Input(Bool())
    val op1    = Input(UInt(32.W))
    val op2    = Input(UInt(32.W))
    val funct3 = Input(UInt(3.W))

    val result = Output(UInt(32.W))
    val valid  = Output(Bool())
    val busy   = Output(Bool())
  })

  object State extends ChiselEnum {
    val sIdle, sCompute, sDone = Value
  }
  val state = RegInit(State.sIdle)

  val op1Reg    = RegInit(0.U(32.W))
  val op2Reg    = RegInit(0.U(32.W))
  val funct3Reg = RegInit(0.U(3.W))
  val resultReg = RegInit(0.U(32.W))
  val counter   = RegInit(0.U(6.W))

  io.result := resultReg
  io.valid := false.B
  io.busy := state =/= State.sIdle

  // Latency configuration (emulates multi-cycle division)
  val LATENCY = 4.U

  switch(state) {
    is(State.sIdle) {
      when(io.start) {
        op1Reg := io.op1
        op2Reg := io.op2
        funct3Reg := io.funct3
        counter := 0.U
        state := State.sCompute
      }
    }
    is(State.sCompute) {
      when(counter === 0.U) {
        val op2Zero   = op2Reg === 0.U
        val overflow  = (op1Reg === "h80000000".U) && (op2Reg === "hFFFFFFFF".U)

        val op1Neg = op1Reg(31)
        val op2Neg = op2Reg(31)
        val op1Abs = Mux(op1Neg, (~op1Reg).asUInt + 1.U, op1Reg)
        val op2Abs = Mux(op2Neg, (~op2Reg).asUInt + 1.U, op2Reg)
        val denom  = Mux(op2Zero, 1.U, op2Abs)

        val unsignedQuot = op1Abs / denom
        val unsignedRemAbs  = op1Abs % denom
        val signedQuotNeg = op1Neg ^ op2Neg
        val signedQuot = Mux(signedQuotNeg, (~unsignedQuot).asUInt + 1.U, unsignedQuot)
        val signedRem  = Mux(op1Neg, (~unsignedRemAbs).asUInt + 1.U, unsignedRemAbs)

        val signedDiv = Mux(op2Zero, "hFFFFFFFF".U, Mux(overflow, "h80000000".U, signedQuot))
        val signedRemFinal = Mux(op2Zero, op1Reg, Mux(overflow, 0.U, signedRem))
        val unsignedDiv = Mux(op2Zero, "hFFFFFFFF".U, op1Reg / Mux(op2Zero, 1.U, op2Reg))
        val unsignedRem = Mux(op2Zero, op1Reg, op1Reg % Mux(op2Zero, 1.U, op2Reg))

        resultReg := MuxLookup(
          funct3Reg,
          signedDiv
        )(
          IndexedSeq(
            InstructionsTypeM.div  -> signedDiv,
            InstructionsTypeM.divu -> unsignedDiv,
            InstructionsTypeM.rem  -> signedRemFinal,
            InstructionsTypeM.remu -> unsignedRem
          )
        )
      }

      counter := counter + 1.U
      when(counter >= LATENCY) {
        state := State.sDone
      }
    }
    is(State.sDone) {
      io.valid := true.B
      state := State.sIdle
    }
  }
}
