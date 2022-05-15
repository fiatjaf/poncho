import java.nio.file.{Files, Path, Paths}
import scala.collection.immutable.Map
import scala.scalanative.unsigned._
import scodec.bits.ByteVector
import upickle.default._

import crypto.Crypto
import codecs._

case class Data(
    htlcAcceptedIds: Map[String, String] = Map.empty,
    channels: Map[String, ChannelData] = Map.empty
)

case class ChannelData(
    isActive: Boolean,
    lcss: LastCrossSignedState
)

object Database {
  import Picklers.given

  val path: Path = Paths.get("poncho.db").toAbsolutePath()
  if (!Files.exists(path)) {
    Files.createFile(path)
    Files.write(path, write(Data()).getBytes)
  }
  var data: Data = read[Data](path)

  def update(change: Data => Data) = {
    val newData = change(data)
    writeToOutputStream(newData, Files.newOutputStream(path))
    data = newData
  }
}

object Picklers {
  given ReadWriter[ByteVector] =
    readwriter[String].bimap[ByteVector](_.toHex, ByteVector.fromValidHex(_))
  given ReadWriter[ByteVector32] =
    readwriter[String]
      .bimap[ByteVector32](_.toHex, ByteVector32.fromValidHex(_))
  given ReadWriter[ByteVector64] =
    readwriter[String]
      .bimap[ByteVector64](_.toHex, ByteVector64.fromValidHex(_))
  given ReadWriter[MilliSatoshi] =
    readwriter[Long].bimap[MilliSatoshi](_.toLong, MilliSatoshi(_))
  given ReadWriter[CltvExpiry] =
    readwriter[Long].bimap[CltvExpiry](_.toLong, CltvExpiry(_))
  given ReadWriter[ULong] =
    readwriter[Long].bimap[ULong](_.toLong, _.toULong)

  given ReadWriter[LastCrossSignedState] = macroRW
  given ReadWriter[InitHostedChannel] = macroRW
  given ReadWriter[UpdateAddHtlc] = macroRW
  given ReadWriter[TlvStream[UpdateAddHtlcTlv]] =
    readwriter[List[Int]]
      .bimap[TlvStream[UpdateAddHtlcTlv]](
        _ => List.empty[Int],
        _ => TlvStream.empty
      )

  implicit val rw: ReadWriter[Data] = macroRW
  given ReadWriter[ChannelData] = macroRW
}
