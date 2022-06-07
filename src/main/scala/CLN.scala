import java.nio.file.{Files, Path, Paths}
import scala.util.Try
import scala.util.chaining._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.Future
import scala.scalanative.unsigned._
import scala.scalanative.loop.EventLoop.loop
import scala.scalanative.loop.{Poll, Timer}
import scala.util.{Failure, Success}
import secp256k1.Keys
import sha256.Hkdf
import ujson._
import scodec.bits.ByteVector
import scodec.codecs.uint16

import unixsocket.UnixSocket
import codecs.HostedChannelCodecs._
import codecs._
import secp256k1.Secp256k1

class CLN {
  private var initCallback = () => {}
  private var rpcAddr: String = ""
  private var hsmSecret: Path = Paths.get("")
  private var nextId = 0
  private var onStartup = true

  Timer.timeout(FiniteDuration(10, "seconds")) { () => onStartup = false }

  def rpc(
      method: String,
      params: ujson.Obj = ujson.Obj()
  ): Future[ujson.Value] = {
    if (rpcAddr == "") {
      return Future.failed(PonchoException("rpc address is not known yet"))
    }

    nextId += 1

    val payload =
      ujson.write(
        ujson.Obj(
          "jsonrpc" -> "2.0",
          "id" -> nextId,
          "method" -> method,
          "params" -> params
        )
      )

    UnixSocket
      .call(rpcAddr, payload)
      .future
      .map(ujson.read(_))
      .flatMap(read =>
        if (read.obj.contains("error")) {
          Future.failed(PonchoException(read("error")("message").str))
        } else {
          Future.successful(read("result"))
        }
      )
  }

  def answer(req: ujson.Value)(result: ujson.Value): Unit = {
    System.out.println(
      ujson.write(
        ujson.Obj(
          "jsonrpc" -> "2.0",
          "id" -> req("id").num,
          "result" -> result
        )
      )
    )
  }

  def answer(req: ujson.Value)(errorMessage: String): Unit = {
    System.out.println(
      ujson.write(
        ujson.Obj(
          "jsonrpc" -> "2.0",
          "id" -> req("id").num,
          "error" -> ujson.Obj(
            "message" -> errorMessage
          )
        )
      )
    )
  }

  def getPrivateKey(): ByteVector32 = {
    val salt = Array[UByte](0.toByte.toUByte)
    val info = "nodeid".getBytes().map(_.toUByte)
    val secret = Files.readAllBytes(hsmSecret).map(_.toUByte)

    val sk = Hkdf.hkdf(salt, secret, info, 32)
    ByteVector32(ByteVector(sk.map(_.toByte)))
  }

  lazy val ourPubKey = ByteVector(
    Keys
      .loadPrivateKey(getPrivateKey().bytes.toArray.map(_.toUByte))
      .toOption
      .get
      .publicKey()
      ._1
      .map(_.toByte)
  )

  def getChainHash(): Future[ByteVector32] =
    rpc("getinfo", ujson.Obj())
      .map(_("network").str)
      .map({
        case "bitcoin" =>
          "6fe28c0ab6f1b372c1a6a246ae63f74f931e8365e15a089c68d6190000000000"
        case "testnet" =>
          "43497fd7f826957108f4a30fd9cec3aeba79972084e90ead01ea330900000000"
        case "signet" =>
          "06226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f"
        case "regtest" =>
          "b291211d4bb2b7e1b7a4758225e69e50104091a637213d033295c010f55ffb18"
        case chain =>
          throw IllegalArgumentException(s"unknown chain name '$chain'")
      })
      .map(ByteVector32.fromValidHex(_))

  def getCurrentBlock(): Future[BlockHeight] =
    rpc("getchaininfo").map(info => BlockHeight(info("headercount").num.toLong))

  def getPeerFromChannel(scid: ShortChannelId): Future[Option[ByteVector]] =
    rpc("listfunds").map(res =>
      res("channels").arr
        .find(chan =>
          if chan.obj.contains("short_channel_id") then
            chan(
              "short_channel_id"
            ).str == scid.toString
          else false
        )
        .map(peer => ByteVector.fromValidHex(peer("peer_id").str))
    )

  def inspectOutgoingPayment(
      peerId: ByteVector,
      htlcId: ULong,
      paymentHash: ByteVector32
  ): Future[PaymentStatus] =
    rpc("listsendpays", ujson.Obj("payment_hash" -> paymentHash.toHex))
      .map(
        _("payments").arr
          .filter(_.obj.contains("label"))
          .find(p => {
            (peerId.toHex, htlcId.toLong) ==
              upickle.default.read[Tuple2[String, Long]](p("label").str)
          })
          .flatMap(toStatus(_))
      )

  private def toStatus(data: ujson.Value): PaymentStatus =
    data("status").str match {
      case "complete" =>
        Some(
          Right(
            ByteVector32(
              ByteVector.fromValidHex(data("payment_preimage").str)
            )
          )
        )
      case "failed" =>
        Some(
          Left(
            data.obj
              .pipe(o => o.get("onionreply").orElse(o.get("erroronion")))
              .map(_.str)
              .map(ByteVector.fromValidHex(_))
              .map(FailureOnion(_))
          )
        )
      case _ => None
    }

  def sendCustomMessage(
      peerId: ByteVector,
      message: HostedServerMessage | HostedClientMessage
  ): Future[ujson.Value] = {
    val (tag, encoded) = message match {
      case m: HostedServerMessage => encodeServerMessage(m)
      case m: HostedClientMessage => encodeClientMessage(m)
    }
    val tagHex = uint16.encode(tag).toOption.get.toByteVector.toHex
    val lengthHex = uint16
      .encode(encoded.size.toInt)
      .toOption
      .get
      .toByteVector
      .toHex
    val payload = tagHex + lengthHex + encoded.toHex

    Main.log(s"  ::> sending $message --> ${peerId.toHex}")

    rpc(
      "sendcustommsg",
      ujson.Obj(
        "node_id" -> peerId.toHex,
        "msg" -> payload
      )
    )
  }

  def sendOnion(
      hostedPeerId: ByteVector,
      htlcId: ULong,
      paymentHash: ByteVector32,
      firstHop: ByteVector,
      amount: MilliSatoshi,
      cltvExpiryDelta: CltvExpiryDelta,
      onion: ByteVector
  ): Future[ujson.Value] = {
    System.err.println(s"calling sendonion with ${ujson
        .Obj(
          "first_hop" -> ujson.Obj(
            "id" -> firstHop.toHex,
            "amount_msat" -> s"${amount.toLong}msat",
            "delay" -> cltvExpiryDelta.toInt
          ),
          "onion" -> onion.toHex,
          "payment_hash" -> paymentHash.toHex,
          "label" -> upickle.default.write((hostedPeerId.toHex, htlcId.toLong))
        )
        .toString}")

    rpc(
      "sendonion",
      ujson.Obj(
        "first_hop" -> ujson.Obj(
          "id" -> firstHop.toHex,
          "amount_msat" -> s"${amount.toLong}msat",
          "delay" -> cltvExpiryDelta.toInt
        ),
        "onion" -> onion.toHex,
        "payment_hash" -> paymentHash.toHex,
        "label" -> upickle.default.write((hostedPeerId.toHex, htlcId.toLong))
      )
    )
  }

  def handleRPC(line: String): Unit = {
    val req = ujson.read(line)
    val data = req("params")
    def reply(result: ujson.Value) = answer(req)(result)
    def replyError(err: String) = answer(req)(err)

    req("method").str match {
      case "getmanifest" =>
        reply(
          ujson.Obj(
            "dynamic" -> false,
            "options" -> ujson.Arr(),
            "subscriptions" -> ujson.Arr(
              "sendpay_success",
              "sendpay_failure",
              "connect",
              "disconnect"
            ),
            "hooks" -> ujson.Arr(
              ujson.Obj("name" -> "custommsg"),
              ujson.Obj("name" -> "htlc_accepted")
            ),
            "rpcmethods" -> ujson.Arr(
              ujson.Obj(
                "name" -> "hc-list",
                "usage" -> "",
                "description" -> "List all your hosted channels."
              ),
              ujson.Obj(
                "name" -> "hc-override",
                "usage" -> "peerid msatoshi",
                "description" -> "Propose overriding the state of the channel with {peerid} with the next local balance being equal to {msatoshi}."
              )
            ),
            "notifications" -> ujson.Arr(),
            "featurebits" -> ujson.Obj(
              // "init" -> 32972 /* hosted_channels */ .toHexString,
              // "node" -> 32972 /* hosted_channels */ .toHexString
            )
          )
        )
      case "init" => {
        reply(
          ujson.Obj(
            "jsonrpc" -> "2.0",
            "id" -> req("id").num,
            "result" -> ujson.Obj()
          )
        )

        val lightningDir = data("configuration")("lightning-dir").str
        rpcAddr = lightningDir + "/" + data("configuration")("rpc-file").str
        hsmSecret = Paths.get(lightningDir + "/hsm_secret")

        initCallback()
      }
      case "custommsg" => {
        reply(ujson.Obj("result" -> "continue"))

        val peerId = ByteVector.fromValidHex(data("peer_id").str)
        val body = data("payload").str
        val tag = ByteVector
          .fromValidHex(body.take(4))
          .toInt(signed = false)
        val payload =
          ByteVector.fromValidHex(
            body
              .drop(4 /* tag */ )
              .drop(4 /* length */ )
          )

        (
          decodeServerMessage(tag, payload).toEither,
          decodeClientMessage(tag, payload).toEither
        ) match {
          case (Left(err1), Left(err2)) =>
            Main.log(s"failed to parse client messages: $err1 | $err2")
          case (Right(msg), Left(_)) =>
            ChannelMaster.getChannelClient(peerId).send(msg)
          case (Left(_), Right(msg)) =>
            ChannelMaster.getChannelServer(peerId).send(msg)
          case (Right(msg1), Right(msg2)) =>
            ChannelMaster.getChannelClient(peerId).send(msg1)
            ChannelMaster.getChannelServer(peerId).send(msg2)
        }
      }
      case "htlc_accepted" => {
        // we wait here because on startup c-lightning will replay all pending htlcs
        // and at that point we won't have the hosted channels active with our clients yet
        Timer.timeout(
          FiniteDuration(
            if (onStartup) { 3 }
            else { 0 },
            "seconds"
          )
        )(() => {
          val htlc = data("htlc")
          val onion = data("onion")

          // if we're the final hop of an htlc this property won't exist
          if (!onion.obj.contains("short_channel_id")) {
            // just continue so our node will accept this payment
            reply(ujson.Obj("result" -> "continue"))
          } else {
            val scid = ShortChannelId(onion("short_channel_id").str)
            val hash = ByteVector32.fromValidHex(htlc("payment_hash").str)
            val incoming = MilliSatoshi(
              Integer.getInteger(htlc("amount").str.takeWhile(_.isDigit)).toLong
            )
            val amount = onion("forward_amount").str.dropRight(4).toInt
            val cltv = CltvExpiry(
              BlockHeight(onion("outgoing_cltv_value").num.toLong)
            )
            val nextOnion = ByteVector.fromValidHex(onion("next_onion").str)

            val channel = Database.data.channels.find((peerId, chandata) =>
              Utils.getShortChannelId(Main.node.ourPubKey, peerId) == scid
            )

            channel match {
              case Some((peerId, chandata)) if chandata.isActive => {
                val peer = ChannelMaster.getChannelServer(peerId)
                peer
                  .addHTLC(
                    incoming,
                    UpdateAddHtlc(
                      channelId =
                        Utils.getChannelId(Main.node.ourPubKey, peerId),
                      id = 0L.toULong, // will be replaced
                      amountMsat = MilliSatoshi(amount),
                      paymentHash = hash,
                      cltvExpiry = cltv,
                      onionRoutingPacket = nextOnion
                    )
                  )
                  .foreach { status =>
                    val response = status match {
                      case Some(Right(preimage)) =>
                        ujson.Obj(
                          "result" -> "resolve",
                          "payment_key" -> preimage.toHex
                        )
                      case Some(Left(Some(FailureOnion(onion)))) =>
                        ujson.Obj(
                          "result" -> "fail",
                          "failure_onion" -> onion.toString
                        )
                      case Some(Left(Some(NormalFailureMessage(message)))) =>
                        ujson.Obj(
                          "result" -> "fail",
                          "failure_message" -> message.codeHex
                        )
                      case Some(Left(None)) =>
                        ujson
                          .Obj("result" -> "fail", "failure_message" -> "1007")
                      case None =>
                        ujson.Obj("result" -> "continue")
                    }
                    reply(response)
                  }
              }
              case Some((_, chandata)) => {
                Main.log(
                  s"[htlc] can't assign $hash to $scid as that channel is inactive"
                )
                reply(ujson.Obj("result" -> "continue"))
              }
              case None => {
                Main.log(
                  s"[htlc] can't assign $hash to $scid as that channel doesn't exist"
                )
                reply(ujson.Obj("result" -> "continue"))
              }
            }
          }
        })
      }
      case "sendpay_success" => {
        val successdata = data("sendpay_success")
        val label = successdata("label").str
        val (peerIdHex, htlcId) =
          upickle.default.read[Tuple2[String, Long]](label)
        val peerId = ByteVector.fromValidHex(peerIdHex)
        val channel = Database.data.channels.get(peerId)
        channel match {
          case Some(chandata) if chandata.isActive => {
            val peer = ChannelMaster.getChannelServer(peerId)
            peer.gotPaymentResult(htlcId.toULong, toStatus(successdata))
          }
          case _ => {}
        }
      }
      case "sendpay_failure" => {
        val failuredata = data("sendpay_failure")("data")
        val label = failuredata("label").str
        val (peerIdHex, htlcId) =
          upickle.default.read[Tuple2[String, Long]](label)
        val peerId = ByteVector.fromValidHex(peerIdHex)
        val channel = Database.data.channels.get(peerId)
        channel match {
          case Some(chandata) if chandata.isActive => {
            val peer = ChannelMaster.getChannelServer(peerId)

            if (failuredata("status").str == "pending") {
              Timer.timeout(FiniteDuration(1, "seconds")) { () =>
                inspectOutgoingPayment(
                  peerId,
                  htlcId.toULong,
                  ByteVector32.fromValidHex(failuredata("payment_hash").str)
                ).foreach { result =>
                  peer.gotPaymentResult(htlcId.toULong, result)
                }
              }
            } else {
              peer.gotPaymentResult(htlcId.toULong, toStatus(failuredata))
            }
          }
          case _ => {
            Main.log("sendpay_failure but not for an active channel")
          }
        }
      }
      case "connect" => {
        val id = data("id").str
        val address = data("address")("address").str
        Main.log(s"$id connected: $address")
      }
      case "disconnect" => {
        val id = data("id").str
        Main.log(s"$id disconnected")
      }

      // custom rpc methods
      case "hc-list" =>
        reply(ChannelMaster.channelsJSON)

      case "hc-override" => {
        val params = data match {
          case _: ujson.Obj =>
            Some((data("peerid").strOpt, data("msatoshi").numOpt))
          case _: ujson.Arr =>
            Some((data(0).strOpt, data(0).numOpt))
          case _ => None
        } match {
          case Some(Some(peerId), Some(msatoshi)) => {
            ChannelMaster
              .getChannelServer(ByteVector.fromValidHex(peerId))
              .proposeOverride(MilliSatoshi(msatoshi.toLong))
              .onComplete {
                case Success(msg) => reply(msg)
                case Failure(err) => replyError(err.toString)
              }
          }
          case _ => {
            replyError("invalid parameters")
          }
        }
      }
    }
  }

  def main(onInit: () => Unit): Unit = {
    initCallback = onInit

    Poll(0).startRead { _ =>
      val line = scala.io.StdIn.readLine().trim
      if (line.size > 0) {
        System.err.println(Console.BOLD + s"line: ${line}" + Console.RESET)
        handleRPC(line)
      }
    }
  }
}
