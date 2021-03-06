package fr.acinq.bitcoin

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, InputStream, OutputStream}

import scala.annotation.tailrec
import scala.collection.mutable.ArrayBuffer
import scala.util.Try

/**
 * script execution flags
 */
object ScriptFlags {
  val SCRIPT_VERIFY_NONE = 0

  // Evaluate P2SH subscripts (softfork safe, BIP16).
  val SCRIPT_VERIFY_P2SH = (1 << 0)

  // Passing a non-strict-DER signature or one with undefined hashtype to a checksig operation causes script failure.
  // Evaluating a pubkey that is not (0x04 + 64 bytes) or (0x02 or 0x03 + 32 bytes) by checksig causes script failure.
  // (softfork safe, but not used or intended as a consensus rule).
  val SCRIPT_VERIFY_STRICTENC = (1 << 1)

  // Passing a non-strict-DER signature to a checksig operation causes script failure (softfork safe, BIP62 rule 1)
  val SCRIPT_VERIFY_DERSIG = (1 << 2)

  // Passing a non-strict-DER signature or one with S > order/2 to a checksig operation causes script failure
  // (softfork safe, BIP62 rule 5).
  val SCRIPT_VERIFY_LOW_S = (1 << 3)

  // verify dummy stack item consumed by CHECKMULTISIG is of zero-length (softfork safe, BIP62 rule 7).
  val SCRIPT_VERIFY_NULLDUMMY = (1 << 4)

  // Using a non-push operator in the scriptSig causes script failure (softfork safe, BIP62 rule 2).
  val SCRIPT_VERIFY_SIGPUSHONLY = (1 << 5)

  // Require minimal encodings for all push operations (OP_0... OP_16, OP_1NEGATE where possible, direct
  // pushes up to 75 bytes, OP_PUSHDATA up to 255 bytes, OP_PUSHDATA2 for anything larger). Evaluating
  // any other push causes the script to fail (BIP62 rule 3).
  // In addition, whenever a stack element is interpreted as a number, it must be of minimal length (BIP62 rule 4).
  // (softfork safe)
  val SCRIPT_VERIFY_MINIMALDATA = (1 << 6)

  // Discourage use of NOPs reserved for upgrades (NOP1-10)
  //
  // Provided so that nodes can avoid accepting or mining transactions
  // containing executed NOP's whose meaning may change after a soft-fork,
  // thus rendering the script invalid; with this flag set executing
  // discouraged NOPs fails the script. This verification flag will never be
  // a mandatory flag applied to scripts in a block. NOPs that are not
  // executed, e.g.  within an unexecuted IF ENDIF block, are *not* rejected.
  val SCRIPT_VERIFY_DISCOURAGE_UPGRADABLE_NOPS = (1 << 7)

  /**
   * Mandatory script verification flags that all new blocks must comply with for
   * them to be valid. (but old blocks may not comply with) Currently just P2SH,
   * but in the future other flags may be added, such as a soft-fork to enforce
   * strict DER encoding.
   *
   * Failing one of these tests may trigger a DoS ban - see CheckInputs() for
   * details.
   */
  val MANDATORY_SCRIPT_VERIFY_FLAGS = SCRIPT_VERIFY_P2SH

  /**
   * Standard script verification flags that standard transactions will comply
   * with. However scripts violating these flags may still be present in valid
   * blocks and we must accept those blocks.
   */
  val STANDARD_SCRIPT_VERIFY_FLAGS = MANDATORY_SCRIPT_VERIFY_FLAGS |
    SCRIPT_VERIFY_STRICTENC |
    SCRIPT_VERIFY_MINIMALDATA |
    SCRIPT_VERIFY_NULLDUMMY |
    SCRIPT_VERIFY_DISCOURAGE_UPGRADABLE_NOPS

  /** For convenience, standard but not mandatory verify flags. */
  val STANDARD_NOT_MANDATORY_VERIFY_FLAGS = STANDARD_SCRIPT_VERIFY_FLAGS & ~MANDATORY_SCRIPT_VERIFY_FLAGS
}

object Script {

  import fr.acinq.bitcoin.ScriptElt._
  import ScriptFlags._

  type Stack = List[Array[Byte]]

  /**
   * parse a script from a input stream of binary data
   * @param input input stream
   * @param stack initial command stack
   * @return an updated command stack
   */
  @tailrec
  def parse(input: InputStream, stack: collection.immutable.Vector[ScriptElt] = Vector.empty[ScriptElt]): List[ScriptElt] = {
    val code = input.read()
    code match {
      case -1 => stack.toList
      case 0 => parse(input, stack :+ OP_0)
      case opCode if opCode > 0 && opCode < 0x4c => parse(input, stack :+ OP_PUSHDATA(bytes(input, opCode)))
      case 0x4c => parse(input, stack :+ OP_PUSHDATA(bytes(input, uint8(input))))
      case 0x4d => parse(input, stack :+ OP_PUSHDATA(bytes(input, uint16(input))))
      case 0x4e => parse(input, stack :+ OP_PUSHDATA(bytes(input, uint32(input))))
      case opCode if code2elt.contains(opCode) => parse(input, stack :+ code2elt(opCode))
      case opCode => parse(input, stack :+ OP_INVALID(opCode)) // unknown/invalid ops can be parsed but not executed
    }
  }

  def parse(blob: Array[Byte]): List[ScriptElt] = if (blob.length > 10000) throw new RuntimeException("script is too large") else parse(new ByteArrayInputStream(blob))

  def write(script: List[ScriptElt], out: OutputStream): Unit = script match {
    case Nil => ()
    case OP_PUSHDATA(data) :: tail if data.length < 0x4c => out.write(data.length); out.write(data); write(tail, out)
    case OP_PUSHDATA(data) :: tail if data.length < 0xff => writeUInt8(0x4c, out); writeUInt8(data.length, out); out.write(data); write(tail, out)
    case OP_PUSHDATA(data) :: tail if data.length < 0xffff => writeUInt8(0x4d, out); writeUInt16(data.length, out); out.write(data); write(tail, out)
    case OP_PUSHDATA(data) :: tail if data.length < 0xffffffff => writeUInt8(0x4e, out); writeUInt32(data.length, out); out.write(data); write(tail, out)
    case head :: tail => out.write(elt2code(head)); write(tail, out)
  }

  def write(script: List[ScriptElt]): Array[Byte] = {
    val out = new ByteArrayOutputStream()
    write(script, out)
    out.toByteArray
  }

  def isNop(op: ScriptElt) = op match {
    case OP_NOP | OP_NOP1 | OP_NOP2 | OP_NOP3 | OP_NOP4 | OP_NOP5 | OP_NOP6 | OP_NOP7 | OP_NOP8 | OP_NOP9 | OP_NOP10 => true
    case _ => false
  }

  def isUpgradableNop(op: ScriptElt) = op match {
    case OP_NOP1 | OP_NOP2 | OP_NOP3 | OP_NOP4 | OP_NOP5 | OP_NOP6 | OP_NOP7 | OP_NOP8 | OP_NOP9 | OP_NOP10 => true
    case _ => false
  }

  def isSimpleValue(op: ScriptElt) = op match {
    case OP_1NEGATE | OP_0 | OP_1 | OP_2 | OP_3 | OP_4 | OP_5 | OP_6 | OP_7 | OP_8 | OP_9 | OP_10 | OP_11 | OP_12 | OP_13 | OP_14 | OP_15 | OP_16 => true
    case _ => false
  }

  def simpleValue(op: ScriptElt): Byte = {
    require(isSimpleValue(op))
    if (op == OP_0) 0 else (elt2code(op) - 0x50).toByte
  }

  def isDisabled(op: ScriptElt) = op match {
    case OP_CAT | OP_SUBSTR | OP_LEFT | OP_RIGHT | OP_INVERT | OP_AND | OP_OR | OP_XOR | OP_2MUL | OP_2DIV | OP_MUL | OP_DIV | OP_MOD | OP_LSHIFT | OP_RSHIFT => true
    case _ => false
  }

  def encodeNumber(value: Long): Array[Byte] = {
    if (value == 0) Array.empty[Byte]
    else {
      val result = ArrayBuffer.empty[Byte]
      val neg = value < 0
      var absvalue = if (neg) -value else value

      while (absvalue > 0) {
        result += (absvalue & 0xff).toByte
        absvalue >>= 8
      }

      //    - If the most significant byte is >= 0x80 and the value is positive, push a
      //    new zero-byte to make the significant byte < 0x80 again.

      //    - If the most significant byte is >= 0x80 and the value is negative, push a
      //    new 0x80 byte that will be popped off when converting to an integral.

      //    - If the most significant byte is < 0x80 and the value is negative, add
      //    0x80 to it, since it will be subtracted and interpreted as a negative when
      //    converting to an integral.

      if ((result.last & 0x80) != 0) {
        result += {
          if (neg) 0x80.toByte else 0
        }
      }
      else if (neg) {
        result(result.length - 1) = (result(result.length - 1) | 0x80).toByte
      }
      result.toArray
    }
  }

  def decodeNumber(input: Array[Byte]): Long = {
    if (input.isEmpty) 0
    else if (input.length > 4) throw new RuntimeException("number cannot be encoded on more than 4 bytes")
    else {
      var result = 0L
      for (i <- 0 until input.size) {
        result |= (input(i) & 0xffL) << (8 * i)
      }

      // If the input vector's most significant byte is 0x80, remove it from
      // the result's msb and return a negative.
      if ((input.last & 0x80) != 0)
        -(result & ~(0x80L << (8 * (input.size - 1))))
      else
        result
    }
  }

  def castToBoolean(input: Array[Byte]): Boolean = input.reverse.toList match {
    case head :: tail if head == 0x80.toByte && tail.find(_ != 0).isEmpty => false
    case something if something.exists(_ != 0) => true
    case _ => false
  }

  def isPushOnly(script: Seq[ScriptElt]) : Boolean = !script.exists {
    case op if isSimpleValue(op) => false
    case OP_PUSHDATA(_) => false
    case _ => true
  }

  def isPayToScript(script: Seq[ScriptElt]) : Boolean = script match {
    case OP_HASH160 :: OP_PUSHDATA(multisigAddress) :: OP_EQUAL :: Nil if multisigAddress.length == 20 => true
    case _ => false
  }

  /**
   * execution context of a tx script
   * @param tx current transaction
   * @param inputIndex 0-based index of the input that is being processed
   * @param previousOutputScript public key script of the output we are trying to claim
   */
  case class Context(tx: Transaction, inputIndex: Int, previousOutputScript: Array[Byte]) {
    require(inputIndex >= 0 && inputIndex < tx.txIn.length, "invalid input index")
  }

  /**
   * Bitcoin script runner
   * @param context script execution context
   */
  class Runner(context: Context, scriptFlag: Int = MANDATORY_SCRIPT_VERIFY_FLAGS) {

    def checkSignature(pubKey: Array[Byte], sigBytes: Array[Byte]): Boolean = {
      if (!Crypto.checkSignatureEncoding(sigBytes, scriptFlag) || !Crypto.checkPubKeyEncoding(pubKey, scriptFlag))
        false
      else {
        val (r, s) = Crypto.decodeSignature(sigBytes)
        val sigHashFlags = sigBytes.last
        val hash = Transaction.hashForSigning(context.tx, context.inputIndex, context.previousOutputScript, sigHashFlags)
        Crypto.verifySignature(hash, (r, s), pubKey)
      }
    }

    def checkSignatures(pubKeys: Seq[Array[Byte]], sigs: Seq[Array[Byte]]): Boolean = sigs match {
      case Nil => true
      case _ if sigs.length > pubKeys.length => false
      case sig :: _ if !Crypto.checkSignatureEncoding(sig, scriptFlag) => throw new RuntimeException("sig")
      case sig :: _ =>
        if (!Crypto.checkPubKeyEncoding(pubKeys.head, scriptFlag)) {
          throw new RuntimeException("pubKey")
        }
        val (r, s) = Crypto.decodeSignature(sig)
        val sigHashFlags = sig.last
        val hash = Transaction.hashForSigning(context.tx, context.inputIndex, context.previousOutputScript, sigHashFlags)
        if (Crypto.verifySignature(hash, (r, s), pubKeys.head))
          checkSignatures(pubKeys.tail, sigs.tail)
        else
          checkSignatures(pubKeys.tail, sigs)
    }

    def run(script: Array[Byte]): Stack = run(parse(script))

    def run(script: List[ScriptElt]): Stack = run(script, List.empty[Array[Byte]])

    def run(script: Array[Byte], stack: Stack): Stack = run(parse(script), stack)

    def run(script: List[ScriptElt], stack: Stack): Stack = run(script, stack, List(), List())

    /**
     * run a bitcoin script
     * @param script command stack
     * @param stack data stack
     * @return a updated data stack
     */
    @tailrec
    final def run(script: List[ScriptElt], stack: Stack, conditions: List[Boolean], altstack: Stack): Stack = {
      if ((stack.length + altstack.length) > 1000) throw new RuntimeException(s"stack is too large: stack size = ${stack.length} alt stack size = ${altstack.length}")
      script match {
        // first, things that are always checked even in non-executed IF branches
        case Nil if !conditions.isEmpty => throw new RuntimeException("IF/ENDIF imbalance")
        case Nil => stack
        case op :: _ if isDisabled(op) => throw new RuntimeException(s"$op isdisabled")
        case OP_VERIF :: tail => throw new RuntimeException("OP_VERIF is always invalid")
        case OP_VERNOTIF :: tail => throw new RuntimeException("OP_VERNOTIF is always invalid")
        case OP_PUSHDATA(data) :: _ if data.size > MaxScriptElementSize => throw new RuntimeException("Push value size limit exceeded")
        // check whether we are in a non-executed IF branch
        case OP_IF :: tail if conditions.exists(_ == false) => run(tail, stack, false :: conditions, altstack)
        case OP_IF :: tail => stack match {
          case head :: stacktail if castToBoolean(head) => run(tail, stacktail, true :: conditions, altstack)
          case head :: stacktail => run(tail, stacktail, false :: conditions, altstack)
        }
        case OP_NOTIF :: tail if conditions.exists(_ == false) => run(tail, stack, true :: conditions, altstack)
        case OP_NOTIF :: tail => stack match {
          case head :: stacktail if castToBoolean(head) => run(tail, stacktail, false :: conditions, altstack)
          case head :: stacktail => run(tail, stacktail, true :: conditions, altstack)
        }
        case OP_ELSE :: tail => run(tail, stack, !conditions.head :: conditions.tail, altstack)
        case OP_ENDIF :: tail => run(tail, stack, conditions.tail, altstack)
        case head :: tail if conditions.exists(_ == false) => run(tail, stack, conditions, altstack)
        // and now, things that are checked only in an executed IF branch
        case OP_0 :: tail => run(tail, Array.empty[Byte] :: stack, conditions, altstack)
        case op :: tail if isSimpleValue(op) => run(tail, encodeNumber(simpleValue(op)) :: stack, conditions, altstack)
        case op :: tail if isUpgradableNop(op) && (scriptFlag & SCRIPT_VERIFY_DISCOURAGE_UPGRADABLE_NOPS) != 0 => throw new RuntimeException("use of upgradable NOP is discouraged")
        case op :: tail if isNop(op) => run(tail, stack, conditions, altstack)
        case OP_1ADD :: tail if stack.isEmpty => throw new RuntimeException("cannot run OP_1ADD on am empty stack")
        case OP_1ADD :: tail => run(tail, encodeNumber(decodeNumber(stack.head) + 1) :: stack.tail, conditions, altstack)
        case OP_1SUB :: tail if stack.isEmpty => throw new RuntimeException("cannot run OP_1SUB on am empty stack")
        case OP_1SUB :: tail => run(tail, encodeNumber(decodeNumber(stack.head) - 1) :: stack.tail, conditions, altstack)
        case OP_ABS :: tail if stack.isEmpty => throw new RuntimeException("cannot run OP_ABS on am empty stack")
        case OP_ABS :: tail => run(tail, encodeNumber(Math.abs(decodeNumber(stack.head))) :: stack.tail, conditions, altstack)
        case OP_ADD :: tail => stack match {
          case a :: b :: stacktail =>
            val x = decodeNumber(a)
            val y = decodeNumber(b)
            val result = x + y
            run(tail, encodeNumber(result) :: stacktail, conditions, altstack)
          case _ => throw new RuntimeException("cannot run OP_ADD on a stack with less then 2 elements")
        }
        case OP_BOOLAND :: tail => stack match {
          case x1 :: x2 :: stacktail =>
            val n1 = decodeNumber(x1)
            val n2 = decodeNumber(x2)
            val result = if (n1 != 0 && n2 != 0) 1 else 0
            run(tail, encodeNumber(result) :: stacktail, conditions, altstack)
          case _ => throw new RuntimeException("cannot run OP_BOOLAND on a stack with less then 2 elements")
        }
        case OP_BOOLOR :: tail => stack match {
          case x1 :: x2 :: stacktail =>
            val n1 = decodeNumber(x1)
            val n2 = decodeNumber(x2)
            val result = if (n1 != 0 || n2 != 0) 1 else 0
            run(tail, encodeNumber(result) :: stacktail, conditions, altstack)
          case _ => throw new RuntimeException("cannot run OP_BOOLOR on a stack with less then 2 elements")
        }
        case OP_CHECKSIG :: tail => stack match {
          case pubKey :: sigBytes :: stacktail => {
            val result = if (!Crypto.checkSignatureEncoding(sigBytes, scriptFlag) || !Crypto.checkPubKeyEncoding(pubKey, scriptFlag))
              false
            else {
              val (r, s) = Crypto.decodeSignature(sigBytes)
              val sigHashFlags = sigBytes.last & 0xff
              val hash = Transaction.hashForSigning(context.tx, context.inputIndex, context.previousOutputScript, sigHashFlags)
              Crypto.verifySignature(hash, (r, s), pubKey)
            }
            run(tail, (if (result) Array(1: Byte) else Array(0: Byte)) :: stacktail, conditions, altstack)
          }
          case _ => throw new RuntimeException("Cannot perform OP_CHECKSIG on a stack with less than 2 elements")
        }
        case OP_CHECKSIGVERIFY :: tail => run(OP_CHECKSIG :: OP_VERIFY :: tail, stack, conditions, altstack)
        case OP_CHECKMULTISIG :: tail => {
          // pop public keys
          val m = decodeNumber(stack.head).toInt
          if (m < 0 || m > 20) throw new RuntimeException("OP_CHECKMULTISIG: invalid number of public keys")
          val stack1 = stack.tail
          val pubKeys = stack1.take(m)
          val stack2 = stack1.drop(m)

          // pop signatures
          val n = decodeNumber(stack2.head).toInt
          if (n < 0 || n > m) throw new RuntimeException("OP_CHECKMULTISIG: invalid number of signatures")
          val stack3 = stack2.tail
          val sigs = stack3.take(n)
          val stack4 = stack3.drop(n + 1) // +1 because of a bug in the official client

          val success = checkSignatures(pubKeys, sigs)
          val result = if (success) Array(1: Byte) else Array(0: Byte)
          run(tail, result :: stack4, conditions, altstack)
        }
        case OP_CHECKMULTISIGVERIFY :: tail => run(OP_CHECKMULTISIG :: OP_VERIFY :: tail, stack, conditions, altstack)
        case OP_CODESEPARATOR :: tail => run(tail, stack, conditions, altstack)
        case OP_DEPTH :: tail => run(tail, encodeNumber(stack.length) :: stack, conditions, altstack)
        case OP_SIZE :: tail if stack.isEmpty => throw new RuntimeException("Cannot run OP_SIZE on an empty stack")
        case OP_SIZE :: tail => run(tail, encodeNumber(stack.head.length) :: stack, conditions, altstack)
        case OP_DROP :: tail => run(tail, stack.tail, conditions, altstack)
        case OP_2DROP :: tail => run(tail, stack.tail.tail, conditions, altstack)
        case OP_DUP :: tail => run(tail, stack.head :: stack, conditions, altstack)
        case OP_2DUP :: tail => stack match {
          case x1 :: x2 :: stacktail => run(tail, x1 :: x2 :: x1 :: x2 :: stacktail, conditions, altstack)
          case _ => throw new RuntimeException("Cannot perform OP_2DUP on a stack with less than 2 elements")
        }
        case OP_3DUP :: tail => stack match {
          case x1 :: x2 :: x3 :: stacktail => run(tail, x1 :: x2 :: x3 :: x1 :: x2 :: x3 :: stacktail, conditions, altstack)
          case _ => throw new RuntimeException("Cannot perform OP_3DUP on a stack with less than 3 elements")
        }
        case OP_EQUAL :: tail => stack match {
          case a :: b :: stacktail if !java.util.Arrays.equals(a, b) => run(tail, Array(0: Byte) :: stacktail, conditions, altstack)
          case a :: b :: stacktail => run(tail, Array(1: Byte) :: stacktail, conditions, altstack)
          case _ => throw new RuntimeException("Cannot perform OP_EQUAL on a stack with less than 2 elements")
        }
        case OP_EQUALVERIFY :: tail => stack match {
          case a :: b :: _ if !java.util.Arrays.equals(a, b) => throw new RuntimeException("OP_EQUALVERIFY failed: elements are different")
          case a :: b :: stacktail => run(tail, stacktail, conditions, altstack)
          case _ => throw new RuntimeException("Cannot perform OP_EQUALVERIFY on a stack with less than 2 elements")
        }
        case OP_FROMALTSTACK :: tail => run(tail, altstack.head :: stack, conditions, altstack.tail)
        case OP_HASH160 :: tail => run(tail, Crypto.hash160(stack.head) :: stack.tail, conditions, altstack)
        case OP_HASH256 :: tail => run(tail, Crypto.hash256(stack.head) :: stack.tail, conditions, altstack)
        case OP_IFDUP :: tail => stack match {
          case Nil => throw new RuntimeException("Cannot perform OP_IFDUP on an empty stack")
          case head :: _ if castToBoolean(head) => run(tail, head :: stack, conditions, altstack)
          case _ => run(tail, stack, conditions, altstack)
        }
        case OP_LESSTHAN :: tail => stack match {
          case x1 :: x2 :: stacktail =>
            val result = if (decodeNumber(x2) < decodeNumber(x1)) 1 else 0
            run(tail, encodeNumber(result) :: stacktail, conditions, altstack)
          case _ => throw new RuntimeException("Cannot perform OP_LESSTHAN on a stack with less than 2 elements")
        }
        case OP_LESSTHANOREQUAL :: tail => stack match {
          case x1 :: x2 :: stacktail =>
            val result = if (decodeNumber(x2) <= decodeNumber(x1)) 1 else 0
            run(tail, encodeNumber(result) :: stacktail, conditions, altstack)
          case _ => throw new RuntimeException("Cannot perform OP_LESSTHANOREQUAL on a stack with less than 2 elements")
        }
        case OP_GREATERTHAN :: tail => stack match {
          case x1 :: x2 :: stacktail =>
            val result = if (decodeNumber(x2) > decodeNumber(x1)) 1 else 0
            run(tail, encodeNumber(result) :: stacktail, conditions, altstack)
          case _ => throw new RuntimeException("Cannot perform OP_GREATERTHAN on a stack with less than 2 elements")
        }
        case OP_GREATERTHANOREQUAL :: tail => stack match {
          case x1 :: x2 :: stacktail =>
            val result = if (decodeNumber(x2) >= decodeNumber(x1)) 1 else 0
            run(tail, encodeNumber(result) :: stacktail, conditions, altstack)
          case _ => throw new RuntimeException("Cannot perform OP_GREATERTHANOREQUAL on a stack with less than 2 elements")
        }
        case OP_MAX :: tail => stack match {
          case x1 :: x2 :: stacktail =>
            val n1 = decodeNumber(x1)
            val n2 = decodeNumber(x2)
            val result = if (n1 > n2) n1 else n2
            run(tail, encodeNumber(result) :: stacktail, conditions, altstack)
          case _ => throw new RuntimeException("Cannot perform OP_MAX on a stack with less than 2 elements")
        }
        case OP_MIN :: tail => stack match {
          case x1 :: x2 :: stacktail =>
            val n1 = decodeNumber(x1)
            val n2 = decodeNumber(x2)
            val result = if (n1 < n2) n1 else n2
            run(tail, encodeNumber(result) :: stacktail, conditions, altstack)
          case _ => throw new RuntimeException("Cannot perform OP_MIN on a stack with less than 2 elements")
        }
        case OP_NEGATE :: tail if stack.isEmpty => throw new RuntimeException("cannot run OP_NEGATE on am empty stack")
        case OP_NEGATE :: tail => run(tail, encodeNumber(-decodeNumber(stack.head)) :: stack.tail, conditions, altstack)
        case OP_NIP :: tail => stack match {
          case x1 :: x2 :: stacktail => run(tail, x1 :: stacktail, conditions, altstack)
          case _ => throw new RuntimeException("Cannot perform OP_NIP on a stack with less than 2 elements")
        }
        case OP_NOT :: tail if stack.isEmpty => throw new RuntimeException("cannot run OP_NOT on am empty stack")
        case OP_NOT :: tail => run(tail, encodeNumber(if (decodeNumber(stack.head) == 0) 1 else 0) :: stack.tail, conditions, altstack)
        case OP_0NOTEQUAL :: tail if stack.isEmpty => throw new RuntimeException("cannot run OP_0NOTEQUAL on am empty stack")
        case OP_0NOTEQUAL :: tail => run(tail, encodeNumber(if (decodeNumber(stack.head) == 0) 0 else 1) :: stack.tail, conditions, altstack)
        case OP_NUMEQUAL :: tail => stack match {
          case x1 :: x2 :: stacktail =>
            val result = if (decodeNumber(x1) == decodeNumber(x2)) 1 else 0
            run(tail, encodeNumber(result) :: stacktail, conditions, altstack)
          case _ => throw new RuntimeException("Cannot perform OP_NUMEQUAL on a stack with less than 2 elements")
        }
        case OP_NUMEQUALVERIFY :: tail => stack match {
          case x1 :: x2 :: stacktail =>
            if (decodeNumber(x1) != decodeNumber(x2)) throw new RuntimeException("OP_NUMEQUALVERIFY failed")
            run(tail, stacktail, conditions, altstack)
          case _ => throw new RuntimeException("Cannot perform OP_NUMEQUALVERIFY on a stack with less than 2 elements")
        }
        case OP_NUMNOTEQUAL :: tail => stack match {
          case x1 :: x2 :: stacktail =>
            val result = if (decodeNumber(x1) != decodeNumber(x2)) 1 else 0
            run(tail, encodeNumber(result) :: stacktail, conditions, altstack)
          case _ => throw new RuntimeException("Cannot perform OP_NUMNOTEQUAL on a stack with less than 2 elements")
        }
        case OP_OVER :: tail => stack match {
          case x1 :: x2 :: _ => run(tail, x2 :: stack, conditions, altstack)
          case _ => throw new RuntimeException("Cannot perform OP_OVER on a stack with less than 2 elements")
        }
        case OP_2OVER :: tail => stack match {
          case x1 :: x2 :: x3 :: x4 :: _ => run(tail, x3 :: x4 :: stack, conditions, altstack)
          case _ => throw new RuntimeException("Cannot perform OP_2OVER on a stack with less than 4 elements")
        }
        case OP_PICK :: tail => stack match {
          case head :: stacktail =>
            val n = decodeNumber(head).toInt
            run(tail, stacktail(n) :: stacktail, conditions, altstack)
          case _ => throw new RuntimeException("Cannot perform OP_PICK on a stack with less than 1 elements")
        }
        case OP_PUSHDATA(data) :: tail => run(tail, data :: stack, conditions, altstack)
        case OP_ROLL :: tail => stack match {
          case head :: stacktail =>
            val n = decodeNumber(head).toInt
            run(tail, stacktail(n) :: stacktail.take(n) ::: stacktail.takeRight(stacktail.length - 1 - n), conditions, altstack)
          case _ => throw new RuntimeException("Cannot perform OP_ROLL on a stack with less than 1 elements")
        }
        case OP_ROT :: tail => stack match {
          case x1 :: x2 :: x3 :: stacktail => run(tail, x3 :: x1 :: x2 :: stacktail, conditions, altstack)
          case _ => throw new RuntimeException("Cannot perform OP_ROT on a stack with less than 3 elements")
        }
        case OP_2ROT :: tail => stack match {
          case x1 :: x2 :: x3 :: x4 :: x5 :: x6 :: stacktail => run(tail, x5 :: x6 :: x1 :: x2 :: x3 :: x4 :: stacktail, conditions, altstack)
          case _ => throw new RuntimeException("Cannot perform OP_2ROT on a stack with less than 6 elements")
        }
        case OP_RIPEMD160 :: tail => run(tail, Crypto.ripemd160(stack.head) :: stack.tail, conditions, altstack)
        case OP_SHA1 :: tail => run(tail, Crypto.sha1(stack.head) :: stack.tail, conditions, altstack)
        case OP_SHA256 :: tail => run(tail, Crypto.sha256(stack.head) :: stack.tail, conditions, altstack)
        case OP_SUB :: tail => stack match {
          case x1 :: x2 :: stacktail =>
            val result = decodeNumber(x2) - decodeNumber(x1)
            run(tail, encodeNumber(result) :: stacktail, conditions, altstack)
          case _ => throw new RuntimeException("cannot run OP_SUB on a stack of less than 2 elements")
        }

        case OP_SWAP :: tail => stack match {
          case x1 :: x2 :: stacktail => run(tail, x2 :: x1 :: stacktail, conditions, altstack)
          case _ => throw new RuntimeException("Cannot perform OP_SWAP on a stack with less than 2 elements")
        }
        case OP_2SWAP :: tail => stack match {
          case x1 :: x2 :: x3 :: x4 :: stacktail => run(tail, x3 :: x4 :: x1 :: x2 :: stacktail, conditions, altstack)
          case _ => throw new RuntimeException("Cannot perform OP_2SWAP on a stack with less than 4 elements")
        }
        case OP_TOALTSTACK :: tail => run(tail, stack.tail, conditions, stack.head :: altstack)
        case OP_TUCK :: tail => stack match {
          case x1 :: x2 :: stacktail => run(tail, x1 :: x2 :: x1 :: stacktail, conditions, altstack)
          case _ => throw new RuntimeException("Cannot perform OP_TUCK on a stack with less than 2 elements")
        }
        case OP_VERIFY :: tail => stack match {
          case Nil => throw new RuntimeException("cannot run OP_VERIFY on an empty stack")
          case head :: stacktail if !castToBoolean(head) => throw new RuntimeException("OP_VERIFY failed")
          case head :: stacktail => run(tail, stacktail, conditions, altstack)
        }
        case OP_WITHIN :: tail => stack match {
          case encMax :: encMin :: encN :: stacktail =>
            val max = decodeNumber(encMax)
            val min = decodeNumber(encMin)
            val n = decodeNumber(encN)
            val result = if (n >= min && n < max) 1 else 0
            run(tail, encodeNumber(result) :: stacktail, conditions, altstack)
          case _ => throw new RuntimeException("Cannot perform OP_WITHIN on a stack with less than 3 elements")
        }
      }
    }

    /**
     * verify a script sig/script pubkey pair:
     * <ul>
     *   <li>parse and run script sig</li>
     *   <li>parse and run script pubkey using the stack generated by the previous step</li>
     *   <li>check the final stack</li>
     *   <li>extract and run embedded pay2sh scripts if any</li>
     * </ul>
     * @param scriptSig signature script
     * @param scriptPubKey public key script
     * @return true if the scripts were successfully verified
     */
    def verifyScripts(scriptSig: Array[Byte], scriptPubKey: Array[Byte]) : Boolean = {
      val ssig = Script.parse(scriptSig)
      if (((scriptFlag & SCRIPT_VERIFY_SIGPUSHONLY) != 0) && !Script.isPushOnly(ssig)) throw new RuntimeException("signature script is not PUSH-only")
      val stack = run(ssig)
      val spub = Script.parse(scriptPubKey)
      val stack1 = run(spub, stack)
      if (stack1.isEmpty) false
      else if (!Script.castToBoolean(stack1.head)) false
      else if (((scriptFlag & SCRIPT_VERIFY_P2SH) != 0) && Script.isPayToScript(spub)) {
          // scriptSig must be literals-only or validation fails
          if (!Script.isPushOnly(ssig)) throw new RuntimeException("signature script is not PUSH-only")

          // pay to script:
          // script sig is built as sig1 :: ... :: sigN :: serialized_script :: Nil
          // and script pubkey is HASH160 :: hash :: EQUAL :: Nil
          // if we got here after running script pubkey, it means that hash == HASH160(serialized script)
          // and stack would be serialized_script :: sigN :: ... :: sig1 :: Nil
          // we pop the first element of the stack, deserialize it and run it against the rest of the stack
          val context1 = context.copy(previousOutputScript = stack.head)
          val runner1 = new Runner(context1, scriptFlag)
          val stack2 = runner1.run(stack.head, stack.tail)
          if (stack2.isEmpty) false else Script.castToBoolean(stack2.head)
        } else true
    }
  }

  /**
   * extract a public key hash from a public key script
   * @param script public key script
   * @return the public key hash wrapped in the script
   */
  def publicKeyHash(script: List[ScriptElt]): Array[Byte] = script match {
    case OP_DUP :: OP_HASH160 :: OP_PUSHDATA(data) :: OP_EQUALVERIFY :: OP_CHECKSIG :: OP_NOP :: Nil => data // non standard pay to pubkey...
    case OP_DUP :: OP_HASH160 :: OP_PUSHDATA(data) :: OP_EQUALVERIFY :: OP_CHECKSIG :: Nil => data // standard pay to pubkey
    case OP_HASH160 :: OP_PUSHDATA(data) :: OP_EQUAL :: Nil if data.size == 20 => data // standard pay to script
  }

  def publicKeyHash(script: Array[Byte]): Array[Byte] = publicKeyHash(parse(script))

  /**
   * extract a public key from a signature script
   * @param script signature script
   * @return the public key wrapped in the script
   */
  def publicKey(script: List[ScriptElt]): Array[Byte] = script match {
    case OP_PUSHDATA(data1) :: OP_PUSHDATA(data2) :: Nil if data1.length > 2 && data2.length > 2 => data2
    case OP_PUSHDATA(data) :: OP_CHECKSIG :: Nil => data
  }

  /**
   * Creates a m-of-n multisig script.
   * @param m is the number of signatures needed
   * @param addresses are the public addresses which associated signatures will be used (m = addresses.size)
   * @return redeem script
   */
  def createMultiSigMofN(m: Int, addresses: List[Array[Byte]]): Array[Byte] = {
    require(m > 0 && m <= 16, s"number of required signatures is $m, should be between 1 and 16")
    require(addresses.size > 0 && addresses.size <= 16, s"number of public keys is ${addresses.size}, should be between 1 and 16")
    require(m <= addresses.size, "The required number of signatures shouldn't be greater than the number of public keys")
    val op_m = ScriptElt.code2elt(m + 0x50) // 1 -> OP_1, 2 -> OP_2, ... 16 -> OP_16
    val op_n = ScriptElt.code2elt(addresses.size + 0x50)
    Script.write(op_m :: addresses.map(OP_PUSHDATA(_)) ::: op_n :: OP_CHECKMULTISIG :: Nil)
  }
}

object CoinbaseScript {
  def parse(blob: Array[Byte]): List[ScriptElt] = List(OP_COINBASE_SCRIPT(blob))
}