/******************************************************************************
  * This file describes the AXI4-Lite interface
  *
  * Interface :
  *   _______________________________________________________________________
  *  | Global  | Write Addr | Write Data | Write Rsp | Read Addr | Read Data |
  *  |   -     |    aw      |     wr     |     b     |    ar     |     r     |
  *  |----------------------- -----------------------------------------------|
  *  | aclk    | awvalid    | wvalid     | bvalid    | arvalid   | rvalid    |
  *  | arstn   | awready    | wready     | bready    | arready   | rready    |
  *  |         | awaddr     | wdata      | bresp     | araddr    | rdata     |
  *  |         | awprot     | wstrb      |           | arprot    | rresp     |
  *  |_________|____________|____________|___________|___________|___________|
  *
  */

package spinal.lib.bus.amba4.axilite

import spinal.core._
import spinal.lib._


/**
  * Definition of the constants used by the AXI Lite bus
  */
object AxiLite4 {

  def apply(addressWidth : Int,
            dataWidth    : Int) = new AxiLite4(AxiLite4Config(
    addressWidth = addressWidth,
    dataWidth = dataWidth
  ))

  /**
    * Read Write response
    */
  object resp{
    def apply() = Bits(2 bits)
    def OKAY   = B"00" // Normal access success
    def EXOKAY = B"01" // Exclusive access okay
    def SLVERR = B"10" // Slave error
    def DECERR = B"11" // Decode error
  }

  /**
    * Access permissions
    */
  object prot{
    def apply() = Bits(3 bits)
    def UNPRIVILEGED_ACCESS = B"000"
    def PRIVILEGED_ACCESS   = B"001"
    def SECURE_ACCESS       = B"000"
    def NON_SECURE_ACCESS   = B"010"
    def DATA_ACCESS         = B"000"
    def INSTRUCTION_ACCESS  = B"100"
  }
}


/**
  * Define all access modes
  */
//trait AxiLite4Mode{
//  def write = false
//  def read = false
//}
//object WRITE_ONLY extends AxiLite4Mode{
//  override def write = true
//}
//object READ_ONLY extends AxiLite4Mode{
//  override def read = true
//}
//object READ_WRITE extends AxiLite4Mode{
//  override def write = true
//  override def read = true
//}


/**
  * Configuration class for the Axi Lite bus
  * @param addressWidth Width of the address bus
  * @param dataWidth    Width of the data bus
  */
case class AxiLite4Config(addressWidth : Int,
                          dataWidth    : Int,
                          readIssuingCapability     : Int = -1,
                          writeIssuingCapability    : Int = -1,
                          combinedIssuingCapability : Int = -1,
                          readDataReorderingDepth   : Int = -1){
  def bytePerWord = dataWidth/8
  def addressType = UInt(addressWidth bits)
  def dataType = Bits(dataWidth bits)

  require(dataWidth == 32 || dataWidth == 64, "Data width must be 32 or 64")
}


trait AxiLite4Bus

/**
  * Axi Lite interface definition
  * @param config Axi Lite configuration class
  */
case class AxiLite4(val config: AxiLite4Config) extends Bundle with IMasterSlave with AxiLite4Bus {

  val aw = Stream(AxiLite4Aw(config))
  val w  = Stream(AxiLite4W(config))
  val b  = Stream(AxiLite4B(config))
  val ar = Stream(AxiLite4Ar(config))
  val r  = Stream(AxiLite4R(config))

  //Because aw w b ar r are ... very lazy
  def writeCmd  = aw
  def writeData = w
  def writeRsp  = b
  def readCmd   = ar
  def readRsp   = r


  def >> (that : AxiLite4) : Unit = {
    this.readCmd drive that.readCmd
    this.writeCmd drive that.writeCmd
    this.writeData drive that.writeData
    that.readRsp drive this.readRsp
    that.writeRsp drive this.writeRsp
  }

  def <<(that : AxiLite4) : Unit = that >> this

  def <<(that : AxiLite4WriteOnly) : Unit = that >> this
  def >> (that : AxiLite4WriteOnly) : Unit = {
    this.writeCmd drive that.writeCmd
    this.writeData drive that.writeData
    that.writeRsp drive this.writeRsp
  }

  def <<(that : AxiLite4ReadOnly) : Unit = that >> this
  def >> (that : AxiLite4ReadOnly) : Unit = {
    this.readCmd drive that.readCmd
    that.readRsp drive this.readRsp
  }

  def toReadOnly(): AxiLite4ReadOnly ={
    val ret = AxiLite4ReadOnly(config)
    ret << this
    ret
  }

  def toWriteOnly(): AxiLite4WriteOnly ={
    val ret = AxiLite4WriteOnly(config)
    ret << this
    ret
  }

  def toShared() : AxiLite4Shared = {
    AxiLite4ToAxiLite4Shared(this)
  }

  def pipelined(
    aw: StreamPipe = StreamPipe.NONE,
    w: StreamPipe = StreamPipe.NONE,
    b: StreamPipe = StreamPipe.NONE,
    ar: StreamPipe = StreamPipe.NONE,
    r: StreamPipe = StreamPipe.NONE
  ): AxiLite4 = {
    val ret = cloneOf(this)
    ret.aw << this.aw.pipelined(aw)
    ret.w << this.w.pipelined(w)
    ret.b.pipelined(b) >> this.b
    ret.ar << this.ar.pipelined(ar)
    ret.r.pipelined(r) >> this.r
    ret
  }

  override def asMaster(): Unit = {
    master(aw,w)
    slave(b)

    master(ar)
    slave(r)
  }
}

object AxiLite4ToAxiLite4Shared{
  def apply(axilite : AxiLite4): AxiLite4Shared ={
    val axiLiteShared = new AxiLite4Shared(axilite.config)
    val arbiter = StreamArbiterFactory.roundRobin.build(new AxiLite4Ax(axilite.config),2)
    arbiter.io.inputs(0) << axilite.ar.asInstanceOf[Stream[AxiLite4Ax]]
    arbiter.io.inputs(1) << axilite.aw.asInstanceOf[Stream[AxiLite4Ax]]

    axiLiteShared.arw.arbitrationFrom(arbiter.io.output)
    axiLiteShared.arw.payload.assignSomeByName(arbiter.io.output.payload)
    axiLiteShared.arw.write := arbiter.io.chosenOH(1)
    axilite.w >> axiLiteShared.w
    axilite.b << axiLiteShared.b
    axilite.r << axiLiteShared.r
    axiLiteShared
  }

  def main(args: Array[String]) {
    SpinalVhdl(new Component{
      val axi = slave(AxiLite4(AxiLite4Config(32,32)))
      val axiLiteShared = master(AxiLite4ToAxiLite4Shared(axi))
    })
  }
}


object AxiLite4SpecRenamer{
  def apply(that : AxiLite4): Unit ={
    def doIt = {
      that.flatten.foreach((bt) => {
        bt.setName(bt.getName().replace("_payload_",""))
        bt.setName(bt.getName().replace("_valid","valid"))
        bt.setName(bt.getName().replace("_ready","ready"))
        if(bt.getName().startsWith("io_")) bt.setName(bt.getName().replaceFirst("io_",""))
      })
    }
    if(Component.current == that.component)
      that.component.addPrePopTask(() => {doIt})
    else
      doIt
  }
  def apply(that : AxiLite4ReadOnly): Unit ={
    def doIt = {
      that.flatten.foreach((bt) => {
        bt.setName(bt.getName().replace("_payload_",""))
        bt.setName(bt.getName().replace("_valid","valid"))
        bt.setName(bt.getName().replace("_ready","ready"))
        if(bt.getName().startsWith("io_")) bt.setName(bt.getName().replaceFirst("io_",""))
      })
    }
    if(Component.current == that.component)
      that.component.addPrePopTask(() => {doIt})
    else
      doIt
  }
}
