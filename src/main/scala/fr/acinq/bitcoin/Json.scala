package fr.acinq.bitcoin

import fr.acinq.bitcoin
import org.json4s.DefaultFormats
import org.json4s.jackson.Serialization

/**
 * implements the "standard" JSON representation of BTC transactions
 */
object Json {
  implicit val format = DefaultFormats

  case class Tx(txid: String, version: Long, locktime: Long, vin: Seq[TxIn], vout: Seq[TxOut])

  object ScriptSig {
    def apply(script: Array[Byte]) = new ScriptSig(asm = Script.parse(script).mkString(" "), hex = toHexString(script))
  }

  case class ScriptSig(asm: String, hex: String)

  case class TxIn(txid: String, vout: Long, scriptSig: ScriptSig, sequence: Long)

  case class ScriptPubKey(asm: String, hex: String, reqSigs: Long, `type`: String, addresses: Seq[String])

  object ScriptPubKey {
    def apply(input: Array[Byte], testnet: Boolean) = {
      val script = Script.parse(input)
      val asm = script.mkString(" ")
      val hex = toHexString(input)
      script match {
        case OP_DUP :: OP_HASH160 :: OP_PUSHDATA(hash) :: OP_EQUALVERIFY :: OP_CHECKSIG :: Nil =>
          new ScriptPubKey(asm, hex, 1, "pubkeyhash", List(Address.encode(if (testnet) Address.TestnetPubkeyVersion else Address.LivenetPubkeyVersion, hash)))
        case OP_HASH160 :: OP_PUSHDATA(hash) :: OP_EQUAL :: Nil if hash.size == 20 =>
          new ScriptPubKey(asm, hex, 1, "scripthash", List(Address.encode(if (testnet) Address.TestnetScriptVersion else Address.LivenetScriptVersion, hash)))
      }
    }
  }

  case class TxOut(value: Double, n: Long, scriptPubKey: ScriptPubKey)

  def convert(input: bitcoin.TxIn) = TxIn(
    txid = toHexString(input.outPoint.hash),
    vout = input.outPoint.index,
    scriptSig = ScriptSig(input.signatureScript),
    sequence = input.sequence)

  def convert(transaction: Transaction, testnet: Boolean): Tx = {
    val txid = Transaction.txid(transaction)
    val ins = transaction.txIn.map(convert)
    val outs = transaction.txOut.zipWithIndex.map { case (out, index) =>
      TxOut(out.amount, index, ScriptPubKey(out.publicKeyScript, testnet))
    }
    Tx(txid, transaction.version, transaction.lockTime, ins, outs)
  }

  def toJson(input: Transaction, testnet: Boolean) = Serialization.writePretty(convert(input, testnet))
}
