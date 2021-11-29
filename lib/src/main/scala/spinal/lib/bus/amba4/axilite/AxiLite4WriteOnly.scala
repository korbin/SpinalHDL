package spinal.lib.bus.amba4.axilite


import spinal.core._
import spinal.lib._

case class AxiLite4WriteOnly(config: AxiLite4Config) extends Bundle with IMasterSlave with AxiLite4Bus {
  val aw = Stream(AxiLite4Aw(config))
  val w  = Stream(AxiLite4W(config))
  val b  = Stream(AxiLite4B(config))

  def writeCmd  = aw
  def writeData = w
  def writeRsp  = b

  def <<(that : AxiLite4) : Unit = that >> this
  def >> (that : AxiLite4) : Unit = {
    this.writeCmd drive that.writeCmd
    this.writeData drive that.writeData
    that.writeRsp drive this.writeRsp
  }

  def <<(that : AxiLite4WriteOnly) : Unit = that >> this
  def >> (that : AxiLite4WriteOnly) : Unit = {
    this.writeCmd drive that.writeCmd
    this.writeData drive that.writeData
    that.writeRsp drive this.writeRsp
  }

  def awValidPipe() : AxiLite4WriteOnly = {
    val sink = AxiLite4WriteOnly(config)
    sink.aw << this.aw.validPipe()
    sink.w  << this.w
    sink.b  >> this.b
    sink
  }

  override def asMaster(): Unit = {
    master(aw,w)
    slave(b)
  }
}
