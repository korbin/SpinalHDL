package spinal.lib.bus.amba4.axilite

import spinal.core._
import spinal.lib._

case class AxiLite4Shared(config: AxiLite4Config) extends Bundle with IMasterSlave with AxiLite4Bus {
  val arw = Stream(AxiLite4Arw(config))
  val w  = Stream(AxiLite4W(config))
  val b   = Stream(AxiLite4B(config))
  val r   = Stream(AxiLite4R(config))

  def sharedCmd = arw
  def writeData = w
  def writeRsp = b
  def readRsp = r

  def <<(that : AxiLite4Shared) : Unit = that >> this
  def >> (that : AxiLite4Shared) : Unit = {
    this.sharedCmd >> that.sharedCmd
    this.writeData >> that.writeData
    that.writeRsp >> this.writeRsp
    that.readRsp >> this.readRsp
  }

  def arwValidPipe() : AxiLite4Shared = {
    val sink = AxiLite4Shared(config)
    sink.arw << this.arw.validPipe()
    sink.w << this.w
    sink.r >> this.r
    sink.b >> this.b
    sink
  }

  def toAxiLite4() : AxiLite4 = {
    val ret = AxiLite4(config)
    ret.ar.payload.assignSomeByName(this.arw.payload)
    ret.aw.payload.assignSomeByName(this.arw.payload)
    ret.ar.valid := this.arw.valid && !this.arw.write
    ret.aw.valid := this.arw.valid && this.arw.write
    this.arw.ready := this.arw.write ? ret.aw.ready | ret.ar.ready
    this.w >> ret.w
    this.r << ret.r
    this.b << ret.b
    ret
  }

  def toAxiLite4ReadOnly() : AxiLite4ReadOnly = {
    val ret = AxiLite4ReadOnly(config)
    ret.ar.payload.assignSomeByName(this.arw.payload)
    ret.ar.valid := this.arw.valid
    this.arw.ready := ret.ar.ready
    this.r << ret.r
    ret
  }

  def toAxiLite4WriteOnly() : AxiLite4WriteOnly = {
    val ret = AxiLite4WriteOnly(config)
    ret.aw.payload.assignSomeByName(this.arw.payload)
    ret.aw.valid := this.arw.valid && this.arw.write
    this.arw.ready := ret.aw.ready
    this.w >> ret.w
    this.b << ret.b
    ret
  }

  override def asMaster(): Unit = {
    master(arw, w)
    slave(b,r)
  }
}
