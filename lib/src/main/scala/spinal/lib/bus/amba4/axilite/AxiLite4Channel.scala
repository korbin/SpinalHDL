package spinal.lib.bus.amba4.axilite

import spinal.core._
import spinal.lib._

/**
  * Definition of the Write/Read address channel
  * @param config Axi Lite configuration class
  */
class AxiLite4Ax(val config: AxiLite4Config) extends Bundle {
  val addr = UInt(config.addressWidth bits)
  val prot = Bits(3 bits)

  import AxiLite4.prot._

  def setUnprivileged : Unit = prot := UNPRIVILEGED_ACCESS | SECURE_ACCESS | DATA_ACCESS
  def setPermissions ( permission : Bits ) : Unit = prot := permission

  override def clone: this.type = new AxiLite4Ax(config).asInstanceOf[this.type]
}

class AxiLite4Aw(config: AxiLite4Config) extends AxiLite4Ax(config){
  override def clone: this.type = new AxiLite4Aw(config).asInstanceOf[this.type]
}

object AxiLite4Aw{
  def apply(config: AxiLite4Config) = new AxiLite4Aw(config)

  implicit class StreamPimper(stream : Stream[AxiLite4Aw]) {
    def drive(sink: Stream[AxiLite4Aw]): Unit = AxiLite4Priv.driveAx(stream,sink)
  }
}

class AxiLite4Ar(config: AxiLite4Config) extends AxiLite4Ax(config){
  override def clone: this.type = new AxiLite4Ar(config).asInstanceOf[this.type]
}

object AxiLite4Ar{
  def apply(config: AxiLite4Config) = new AxiLite4Ar(config)
  implicit class StreamPimper(stream : Stream[AxiLite4Ar]){
    def drive(sink : Stream[AxiLite4Ar]): Unit = AxiLite4Priv.driveAx(stream,sink)
  }
}

class AxiLite4Arw(config: AxiLite4Config) extends AxiLite4Ax(config) {
  val write = Bool()
  override def clone: this.type = new AxiLite4Arw(config).asInstanceOf[this.type]
}

object AxiLite4Arw{
  def apply(config: AxiLite4Config) = new AxiLite4Arw(config)

  implicit class StreamPimper(stream : Stream[AxiLite4Arw]) {
    def drive(sink : Stream[AxiLite4Arw]): Unit ={
      AxiLite4Priv.driveAx(stream,sink)
      sink.write := stream.write
    }
  }
}

object AxiLite4Priv{
  def driveWeak[T <: Data](source : Bundle,sink : Bundle, by : T,to : T,defaultValue : () => T,allowResize : Boolean,allowDrop : Boolean) : Unit = {
    (to != null,by != null) match {
      case (false,false) =>
      case (true,false) => if(defaultValue != null) to := defaultValue() else LocatedPendingError(s"$source can't drive $to because this first doesn't has the corresponding pin")
      case (false,true) => if(!allowDrop) LocatedPendingError(s"$by can't drive $sink because this last one doesn't has the corresponding pin")
      case (true,true) => to := (if(allowResize) by.resized else by)
    }
  }

  def driveAx[T <: AxiLite4Ax](stream: Stream[T],sink: Stream[T]): Unit = {
    sink.arbitrationFrom(stream)
    assert(stream.config.addressWidth >= sink.config.addressWidth, s"Expect $stream addressWidth=${stream.config.addressWidth} >= $sink addressWidth=${sink.config.addressWidth}")

    sink.addr := stream.addr.resized
    driveWeak(stream,sink,stream.prot,sink.prot,() => B"010",false,true)
  }
}

/**
  * Definition of the Write data channel
  * @param config Axi Lite configuration class
  */
case class AxiLite4W(config: AxiLite4Config) extends Bundle {
  val data = Bits(config.dataWidth bits)
  val strb = Bits(config.dataWidth / 8 bits)

  def setStrb : Unit = strb := (1 << widthOf(strb))-1
  def setStrb(bytesLane : Bits) : Unit = strb := bytesLane
}

object AxiLite4W{
  implicit class StreamPimper(stream : Stream[AxiLite4W]) {
    def drive(sink: Stream[AxiLite4W]): Unit = {
      sink.arbitrationFrom(stream)
      sink.data := stream.data

      AxiLite4Priv.driveWeak(stream,sink,stream.strb,sink.strb,() => B(sink.strb.range -> true),false,false)
    }
  }
}

/**
  * Definition of the Write response channel
  * @param config Axi Lite configuration class
  */
case class AxiLite4B(config: AxiLite4Config) extends Bundle {
  val resp = Bits(2 bits)

  import AxiLite4.resp._

  def setOKAY()   : Unit = resp := OKAY
  def setEXOKAY() : Unit = resp := EXOKAY
  def setSLVERR() : Unit = resp := SLVERR
  def setDECERR() : Unit = resp := DECERR
  def isOKAY()   : Bool = resp === OKAY
  def isEXOKAY() : Bool = resp === EXOKAY
  def isSLVERR() : Bool = resp === SLVERR
  def isDECERR() : Bool = resp === DECERR
}

/** Companion object to create hard-wired AXI responses. */
object AxiLite4B {
  def okay(config: AxiLite4Config) = { val resp = new AxiLite4B(config); resp.setOKAY(); resp }
  def exclusiveOkay(config: AxiLite4Config) = { val resp = new AxiLite4B(config); resp.setEXOKAY(); resp }
  def slaveError(config: AxiLite4Config) = { val resp = new AxiLite4B(config); resp.setSLVERR(); resp }
  def decodeError(config: AxiLite4Config) = { val resp = new AxiLite4B(config); resp.setDECERR(); resp }

  implicit class StreamPimper(stream : Stream[AxiLite4B]) {
    def drive(sink: Stream[AxiLite4B]): Unit = {
      sink.arbitrationFrom(stream)

      AxiLite4Priv.driveWeak(stream,sink,stream.resp,sink.resp,() => AxiLite4.resp.OKAY,false,true)
    }
  }
}


/**
  * Definition of the Read data channel
  * @param config Axi Lite configuration class
  */
case class AxiLite4R(config: AxiLite4Config) extends Bundle {
  val data = Bits(config.dataWidth bits)
  val resp = Bits(2 bits)

  import AxiLite4.resp._

  def setOKAY()   : Unit = resp := OKAY
  def setEXOKAY() : Unit = resp := EXOKAY
  def setSLVERR() : Unit = resp := SLVERR
  def setDECERR() : Unit = resp := DECERR
  def isOKAY()   : Bool = resp === OKAY
  def isEXOKAY() : Bool = resp === EXOKAY
  def isSLVERR() : Bool = resp === SLVERR
  def isDECERR() : Bool = resp === DECERR
}

object AxiLite4R{
  implicit class StreamPimper(stream : Stream[AxiLite4R]) {
    def drive(sink: Stream[AxiLite4R]): Unit = {
      sink.arbitrationFrom(stream)
      sink.data := stream.data

      AxiLite4Priv.driveWeak(stream,sink,stream.resp,sink.resp,() => AxiLite4.resp.OKAY,false,true)
    }
  }
}
