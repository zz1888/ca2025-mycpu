// SPDX-License-Identifier: MIT
package riscv

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import riscv.core.Multiplier

class MultiplierTest extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "Multiplier"

  it should "correctly multiply 12345 * 67890" in {
    test(new Multiplier) { dut =>
      // Start multiplication
      dut.io.start.poke(true.B)
      dut.io.op1.poke(12345.U)
      dut.io.op2.poke(67890.U)
      dut.io.funct3.poke(0.U)  // MUL
      dut.clock.step(1)
      
      // Wait for busy to go low
      dut.io.start.poke(false.B)
      while (dut.io.busy.peek().litToBoolean) {
        dut.clock.step(1)
      }
      
      // Check result
      val expectedProduct = BigInt(12345) * BigInt(67890)
      val expected = expectedProduct & BigInt("FFFFFFFF", 16)
      val result = dut.io.result.peek().litValue
      
      println(s"12345 * 67890:")
      println(s"  Full product (dec): $expectedProduct")
      println(s"  Full product (hex): 0x${expectedProduct.toString(16).toUpperCase}")
      println(s"  Expected lower 32 (dec): $expected")
      println(s"  Expected lower 32 (hex): 0x${expected.toString(16).toUpperCase}")
      println(s"  Got (dec):               $result")
      println(s"  Got (hex):               0x${result.toString(16).toUpperCase}")
      println(s"  Match: ${result == expected}")
      
      // Verify the math
      assert(expectedProduct == 838102050L, s"Math error: 12345*67890 should be 838102050, got $expectedProduct")
      assert(expected.toString(16).toUpperCase == "31F46C22", s"Hex conversion: 838102050 should be 0x31F46C22, got 0x${expected.toString(16).toUpperCase}")
      
      assert(result == expected, s"Multiplier result incorrect: Expected 0x${expected.toString(16)}, got 0x${result.toString(16)}")
    }
  }

  it should "correctly multiply small numbers" in {
    test(new Multiplier) { dut =>
      val testCases = Seq(
        (7, 11, 77),
        (100, 200, 20000),
        (1024, 1024, 1048576)
      )
      
      for ((a, b, expected) <- testCases) {
        dut.io.start.poke(true.B)
        dut.io.op1.poke(a.U)
        dut.io.op2.poke(b.U)
        dut.io.funct3.poke(0.U)
        dut.clock.step(1)
        
        dut.io.start.poke(false.B)
        while (dut.io.busy.peek().litToBoolean) {
          dut.clock.step(1)
        }
        
        val result = dut.io.result.peek().litValue
        println(s"$a * $b = $result (expected $expected)")
        assert(result == expected)
      }
    }
  }

  it should "correctly compute MULH for signed numbers" in {
    test(new Multiplier) { dut =>
      // Test: (-12345) * 67890, upper 32 bits
      val a = -12345
      val b = 67890
      val product = BigInt(a) * BigInt(b)
      val expected = (product >> 32) & BigInt("FFFFFFFF", 16)
      
      // Convert negative number to unsigned 32-bit representation
      val a_unsigned = BigInt(a & 0xFFFFFFFFL)
      
      dut.io.start.poke(true.B)
      dut.io.op1.poke(a_unsigned.U)
      dut.io.op2.poke(b.U)
      dut.io.funct3.poke(1.U)  // MULH
      dut.clock.step(1)
      
      dut.io.start.poke(false.B)
      while (dut.io.busy.peek().litToBoolean) {
        dut.clock.step(1)
      }
      
      val result = dut.io.result.peek().litValue
      println(s"MULH($a, $b):")
      println(s"  Product: $product (0x${product.toString(16)})")
      println(s"  Expected upper: 0x${expected.toString(16)} ($expected)")
      println(s"  Got:            0x${result.toString(16)} ($result)")
      
      assert(result == expected)
    }
  }
}
