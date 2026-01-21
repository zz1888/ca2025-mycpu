// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package riscv.core

import chisel3._
import chisel3.util._
import riscv.core.InstructionTypes
import riscv.core.Instructions
import riscv.core.InstructionsTypeI
import riscv.core.InstructionsTypeR
import riscv.core.InstructionsTypeM
import riscv.core.InstructionsTypeDSP

class ALUControl extends Module {
  val io = IO(new Bundle {
    val opcode = Input(UInt(7.W))
    val funct3 = Input(UInt(3.W))
    val funct7 = Input(UInt(7.W))

    val alu_funct = Output(ALUFunctions())
  })

  io.alu_funct := ALUFunctions.zero

  switch(io.opcode) {
    is(InstructionTypes.I) {
      io.alu_funct := MuxLookup(
        io.funct3,
        ALUFunctions.zero
      )(
        IndexedSeq(
          InstructionsTypeI.addi  -> ALUFunctions.add,
          InstructionsTypeI.slli  -> ALUFunctions.sll,
          InstructionsTypeI.slti  -> ALUFunctions.slt,
          InstructionsTypeI.sltiu -> ALUFunctions.sltu,
          InstructionsTypeI.xori  -> ALUFunctions.xor,
          InstructionsTypeI.ori   -> ALUFunctions.or,
          InstructionsTypeI.andi  -> ALUFunctions.and,
          InstructionsTypeI.sri   -> Mux(io.funct7(5), ALUFunctions.sra, ALUFunctions.srl)
        )
      )
    }
    is(InstructionTypes.RM) {
      // Check if this is M-extension (funct7 = 0x01) or R-type (funct7 = 0x00/0x20)
      when(io.funct7 === 1.U) {
        // M-extension instructions (RV32M)
        io.alu_funct := MuxLookup(
          io.funct3,
          ALUFunctions.zero
        )(
          IndexedSeq(
            InstructionsTypeM.mul    -> ALUFunctions.mul,
            InstructionsTypeM.mulh   -> ALUFunctions.mulh,
            InstructionsTypeM.mulhsu -> ALUFunctions.mulhsu,
            InstructionsTypeM.mulhum -> ALUFunctions.mulhu,
            InstructionsTypeM.div    -> ALUFunctions.div,
            InstructionsTypeM.divu   -> ALUFunctions.divu,
            InstructionsTypeM.rem    -> ALUFunctions.rem,
            InstructionsTypeM.remu   -> ALUFunctions.remu
          )
        )
      }.otherwise {
        // R-type instructions (RV32I)
        io.alu_funct := MuxLookup(
          io.funct3,
          ALUFunctions.zero
        )(
          IndexedSeq(
            InstructionsTypeR.add_sub -> Mux(io.funct7(5), ALUFunctions.sub, ALUFunctions.add),
            InstructionsTypeR.sll     -> ALUFunctions.sll,
            InstructionsTypeR.slt     -> ALUFunctions.slt,
            InstructionsTypeR.sltu    -> ALUFunctions.sltu,
            InstructionsTypeR.xor     -> ALUFunctions.xor,
            InstructionsTypeR.or      -> ALUFunctions.or,
            InstructionsTypeR.and     -> ALUFunctions.and,
            InstructionsTypeR.sr      -> Mux(io.funct7(5), ALUFunctions.sra, ALUFunctions.srl)
          )
        )
      }
    }
    is(InstructionTypes.B) {
      io.alu_funct := ALUFunctions.add
    }
    is(InstructionTypes.L) {
      io.alu_funct := ALUFunctions.add
    }
    is(InstructionTypes.S) {
      io.alu_funct := ALUFunctions.add
    }
    is(Instructions.jal) {
      io.alu_funct := ALUFunctions.add
    }
    is(Instructions.jalr) {
      io.alu_funct := ALUFunctions.add
    }
    is(Instructions.lui) {
      io.alu_funct := ALUFunctions.add
    }
    is(Instructions.auipc) {
      io.alu_funct := ALUFunctions.add
    }
    is(InstructionTypes.CUSTOM) {
      // DSP extension instructions (custom-0)
      io.alu_funct := MuxLookup(
        io.funct3,
        ALUFunctions.zero
      )(
        IndexedSeq(
          InstructionsTypeDSP.qmul16 -> ALUFunctions.qmul16,
          InstructionsTypeDSP.sadd16 -> ALUFunctions.sadd16,
          InstructionsTypeDSP.ssub16 -> ALUFunctions.ssub16,
          InstructionsTypeDSP.sadd32 -> ALUFunctions.sadd32,
          InstructionsTypeDSP.ssub32 -> ALUFunctions.ssub32,
          InstructionsTypeDSP.qmul16r -> ALUFunctions.qmul16r,
          InstructionsTypeDSP.sshl16 -> ALUFunctions.sshl16,
          InstructionsTypeDSP.qmul32x16 -> ALUFunctions.qmul32x16
        )
      )
    }
  }
}
