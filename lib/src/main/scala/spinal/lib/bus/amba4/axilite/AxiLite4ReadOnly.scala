package spinal.lib.bus.amba4.axilite

import spinal.core._
import spinal.lib._

case class AxiLite4ReadOnly(config: AxiLite4Config) extends Bundle with IMasterSlave with AxiLite4Bus {
  val ar = Stream(AxiLite4Ar(config))
  val r  = Stream(AxiLite4R(config))

  def readCmd   = ar
  def readRsp   = r

  def <<(that : AxiLite4) : Unit = that >> this
  def >> (that : AxiLite4) : Unit = {
    this.readCmd drive that.readCmd
    that.readRsp drive this.readRsp
  }

  def <<(that : AxiLite4ReadOnly) : Unit = that >> this
  def >> (that : AxiLite4ReadOnly) : Unit = {
    this.readCmd drive that.readCmd
    that.readRsp drive this.readRsp
  }

  def arValidPipe() : AxiLite4ReadOnly = {
    val sink = AxiLite4ReadOnly(config)
    sink.ar << this.ar.validPipe()
    sink.r  >> this.r
    sink
  }


  def pipelined(
    ar: StreamPipe = StreamPipe.NONE,
    r: StreamPipe = StreamPipe.NONE
  ): AxiLite4ReadOnly = {
    val ret = cloneOf(this)
    ret.ar << this.ar.pipelined(ar)
    ret.r.pipelined(r) >> this.r
    ret
  }

  override def asMaster(): Unit = {
    master(ar)
    slave(r)
  }
}
