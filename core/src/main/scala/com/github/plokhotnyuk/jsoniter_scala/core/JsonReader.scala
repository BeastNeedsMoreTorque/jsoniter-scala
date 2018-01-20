package com.github.plokhotnyuk.jsoniter_scala.core

import java.io.InputStream
import java.math.BigInteger

import com.github.plokhotnyuk.jsoniter_scala.core.JsonReader._

import scala.annotation.{switch, tailrec}
import scala.util.control.NonFatal

class JsonParseException(msg: String, cause: Throwable, withStackTrace: Boolean)
  extends RuntimeException(msg, cause, true, withStackTrace)

/**
  * Configuration for [[com.github.plokhotnyuk.jsoniter_scala.core.JsonReader]] that contains flags for tuning of
  * parsing exceptions and preferred sizes for internal buffers that created on the reader instantiation and reused in
  * runtime for parsing of messages.
  * <br/>
  * All configuration params already initialized by recommended default values, but in some cases they should be altered
  * for performance reasons:
  * <ul>
  * <li>turn off stack traces for parsing exceptions to greatly reduce impact on performance for cases when exceptions
  * can be not exceptional, see more details here: [[https://shipilev.net/blog/2014/exceptional-performance/]]</li>
  * <li>turn off appending of hex dump to minimize length of exception message</li>
  * <li>increase preferred size of an internal char buffer to reduce allocation rate of grown and then reduced
  * buffers when lot of large strings with length greater than 2K need to be parsed</li>
  * <li>increase preferred size of an internal byte buffer for parsing from [[java.io.InputStream]] to reduce allocation
  * rate of grown and then reduced buffers when during parsing of large ADT instances (>16Kb) the discriminator field does
  * not appear in the beginning of the JSON object</li>
  * </ul>
  * @param throwParseExceptionWithStackTrace a flag that allows to turn off a stack trace for parsing exceptions
  * @param appendHexDumpToParseException a flag that allows to turn off hex dumping of affected by error part of
  *                                      an internal byte buffer
  * @param preferredBufSize a preferred size (in bytes) of an internal byte buffer when parsing from
  *                         [[java.io.InputStream]]
  * @param preferredCharBufSize a preferred size (in chars) of an internal char buffer for parsing of string values
  */
case class ReaderConfig(
    throwParseExceptionWithStackTrace: Boolean = true,
    appendHexDumpToParseException: Boolean = true,
    preferredBufSize: Int = 16384,
    preferredCharBufSize: Int = 2048) {
  if (preferredBufSize < 12) throw new IllegalArgumentException("`preferredBufSize` should be not less than 12")
}

final class JsonReader private[jsoniter_scala](
    private[this] var buf: Array[Byte] = new Array[Byte](1024),
    private[this] var head: Int = 0,
    private[this] var tail: Int = 0,
    private[this] var mark: Int = 2147483647,
    private[this] var charBuf: Array[Char] = new Array[Char](128),
    private[this] var in: InputStream = null,
    private[this] var totalRead: Int = 0,
    private[this] var config: ReaderConfig = null) {
  def requiredKeyError(reqFields: Array[String], reqBits: Array[Int]): Nothing = {
    val len = Math.min(reqFields.length, reqBits.length << 5)
    var i = 0
    var j = 0
    var reqBitBlock = 0
    while (j < len) {
      if ((j & 31) == 0) reqBitBlock = reqBits(j >> 5)
      if ((reqBitBlock & (1 << j)) != 0) {
        i = appendString(if (i == 0) "missing required field(s) \"" else "\", \"", i)
        i = appendString(reqFields(j), i)
      }
      j += 1
    }
    if (i == 0) throw new IllegalArgumentException("missing required field(s) cannot be reported for arguments: " +
      s"reqFields = ${reqFields.mkString("Array(", ", ", ")")}, reqBits = ${reqBits.mkString("Array(", ", ", ")")}")
    i = appendString("\"", i)
    decodeError(i, head - 1, null)
  }

  def unexpectedKeyError(len: Int): Nothing = {
    var i = prependString("unexpected field: \"", len)
    i = appendString("\"", i)
    decodeError(i, head - 1, null)
  }

  def discriminatorValueError(discriminatorFieldName: String): Nothing = {
    var i = appendString("illegal value of discriminator field \"", 0)
    i = appendString(discriminatorFieldName, i)
    i = appendString("\"", i)
    decodeError(i, head - 1, null)
  }

  def enumValueError(value: String): Nothing = {
    var i = appendString("illegal enum value: \"", 0)
    i = appendString(value, i)
    i = appendString("\"", i)
    decodeError(i, head - 1, null)
  }

  def setMark(): Unit = mark = head

  @tailrec
  def scanToKey(s: String): Unit = if (!isCharBufEqualsTo(readKeyAsCharBuf(), s)) {
    skip()
    if (isNextToken(',', head)) scanToKey(s)
    else reqFieldError(s)
  }

  def rollbackToMark(): Unit = {
    if (mark == 2147483647) throw new ArrayIndexOutOfBoundsException("expected preceding call of 'setMark()'")
    head = mark
    mark = 2147483647
  }

  def readKeyAsCharBuf(): Int = {
    readParenthesesToken()
    val len = parseString()
    readColonToken()
    len
  }

  def readKeyAsString(): String = {
    readParenthesesToken()
    val len = parseString()
    readColonToken()
    new String(charBuf, 0, len)
  }

  def readKeyAsBoolean(): Boolean = {
    readParenthesesToken()
    val x = parseBoolean(isToken = false)
    readParenthesesByteWithColonToken()
    x
  }

  def readKeyAsByte(): Byte = {
    readParenthesesToken()
    val x = parseByte(isToken = false)
    readParenthesesByteWithColonToken()
    x
  }

  def readKeyAsChar(): Char = {
    readParenthesesToken()
    val x = parseChar(head)
    readParenthesesByteWithColonToken()
    x
  }

  def readKeyAsShort(): Short = {
    readParenthesesToken()
    val x = parseShort(isToken = false)
    readParenthesesByteWithColonToken()
    x
  }

  def readKeyAsInt(): Int = {
    readParenthesesToken()
    val x = parseInt(isToken = false)
    readParenthesesByteWithColonToken()
    x
  }

  def readKeyAsLong(): Long = {
    readParenthesesToken()
    val x = parseLong(isToken = false)
    readParenthesesByteWithColonToken()
    x
  }

  def readKeyAsFloat(): Float = {
    readParenthesesToken()
    val x = parseDouble(isToken = false).toFloat
    readParenthesesByteWithColonToken()
    x
  }

  def readKeyAsDouble(): Double = {
    readParenthesesToken()
    val x = parseDouble(isToken = false)
    readParenthesesByteWithColonToken()
    x
  }

  def readKeyAsBigInt(): BigInt = {
    readParenthesesToken()
    val x = parseBigInt(isToken = false)
    readParenthesesByteWithColonToken()
    x
  }

  def readKeyAsBigDecimal(): BigDecimal = {
    readParenthesesToken()
    val x = parseBigDecimal(isToken = false, null)
    readParenthesesByteWithColonToken()
    x
  }

  def readByte(): Byte = parseByte(isToken = true)

  def readChar(): Char = {
    readParenthesesToken()
    val x = parseChar(head)
    readParenthesesByte()
    x
  }

  def readShort(): Short = parseShort(isToken = true)

  def readInt(): Int = parseInt(isToken = true)

  def readLong(): Long = parseLong(isToken = true)

  def readDouble(): Double = parseDouble(isToken = true)

  def readFloat(): Float = parseDouble(isToken = true).toFloat

  def readBigInt(default: BigInt): BigInt = parseBigInt(isToken = true, default)

  def readBigDecimal(default: BigDecimal): BigDecimal = parseBigDecimal(isToken = true, default)

  def readString(default: String = null): String =
    if (isNextToken('"', head)) {
      val len = parseString()
      new String(charBuf, 0, len)
    } else readNullOrTokenError(default, '"')

  def readBoolean(): Boolean = parseBoolean(isToken = true)

  def readStringAsCharBuf(): Int =
    if (isNextToken('"', head)) parseString()
    else decodeError("expected '\"'")

  def readStringAsByte(): Byte = {
    readParenthesesToken()
    val x = parseByte(isToken = false)
    readParenthesesByte()
    x
  }

  def readStringAsShort(): Short = {
    readParenthesesToken()
    val x = parseShort(isToken = false)
    readParenthesesByte()
    x
  }

  def readStringAsInt(): Int = {
    readParenthesesToken()
    val x = parseInt(isToken = false)
    readParenthesesByte()
    x
  }

  def readStringAsLong(): Long = {
    readParenthesesToken()
    val x = parseLong(isToken = false)
    readParenthesesByte()
    x
  }

  def readStringAsDouble(): Double = {
    readParenthesesToken()
    val x = parseDouble(isToken = false)
    readParenthesesByte()
    x
  }

  def readStringAsFloat(): Float = {
    readParenthesesToken()
    val x = parseDouble(isToken = false).toFloat
    readParenthesesByte()
    x
  }

  def readStringAsBigInt(default: BigInt): BigInt =
    if (isNextToken('"', head)) {
      val x = parseBigInt(isToken = false, default)
      readParenthesesByte()
      x
    } else readNullOrTokenError(default, '"')

  def readStringAsBigDecimal(default: BigDecimal): BigDecimal =
    if (isNextToken('"', head)) {
      val x = parseBigDecimal(isToken = false, default)
      readParenthesesByte()
      x
    } else readNullOrTokenError(default, '"')

  def readStringAsBoolean(): Boolean = {
    readParenthesesToken()
    val x = parseBoolean(isToken = false)
    readParenthesesByte()
    x
  }

  def readNullOrError[A](default: A, error: String): A =
    if (isNextToken('n', head)) parseNullOrError(default, error, head)
    else decodeError(error)

  def readNullOrTokenError[A](default: A, b: Byte): A =
    if (isCurrentToken('n', head)) parseNullOrTokenError(default, b, head)
    else tokenOrNullError(b)

  def nextToken(): Byte = nextToken(head)

  def isNextToken(b: Byte): Boolean = isNextToken(b, head)

  def isCurrentToken(b: Byte): Boolean = isCurrentToken(b, head)

  def rollbackToken(): Unit = {
    val pos = head
    if (pos == 0) throw new ArrayIndexOutOfBoundsException("expected preceding call of 'nextToken()' or 'isNextToken()'")
    head = pos - 1
  }

  def charBufToHashCode(len: Int): Int = toHashCode(charBuf, len)

  def isCharBufEqualsTo(len: Int, s: String): Boolean = len == s.length && isCharBufEqualsTo(len, s, 0)

  def skip(): Unit = head = {
    val b = nextToken(head)
    if (b == '"') skipString(evenBackSlashes = true, head)
    else if ((b >= '0' && b <= '9') || b == '-') skipNumber(head)
    else if (b == 'n' || b == 't') skipFixedBytes(3, head)
    else if (b == 'f') skipFixedBytes(4, head)
    else if (b == '{') skipNested('{', '}', 0, head)
    else if (b == '[') skipNested('[', ']', 0, head)
    else decodeError("expected value", head - 1)
  }

  def commaError(): Nothing = decodeError("expected ','")

  def arrayStartOrNullError(): Nothing = decodeError("expected '[' or null")

  def arrayEndError(): Nothing = decodeError("expected ']'")

  def arrayEndOrCommaError(): Nothing = decodeError("expected ']' or ','")

  def objectStartOrNullError(): Nothing = decodeError("expected '{' or null")

  def objectEndOrCommaError(): Nothing = decodeError("expected '}' or ','")

  def decodeError(msg: String): Nothing = decodeError(msg, head - 1)

  private def read[A](codec: JsonCodec[A], buf: Array[Byte], from: Int, to: Int, config: ReaderConfig): A = {
    if (config eq null) throw new NullPointerException
    if (to > buf.length || to < 0) // also checks that `buf` is not null before any parsing
      throw new ArrayIndexOutOfBoundsException("`to` should be positive and not greater than `buf` length")
    if (from > to || from < 0)
      throw new ArrayIndexOutOfBoundsException("`from` should be positive and not greater than `to`")
    val currBuf = this.buf
    this.config = config
    this.buf = buf
    head = from
    tail = to
    mark = 2147483647
    totalRead = 0
    try codec.decode(this, codec.nullValue) // also checks that `codec` is not null before any parsing
    finally {
      this.buf = currBuf
      freeTooLongCharBuf()
    }
  }

  private def read[A](codec: JsonCodec[A], in: InputStream, config: ReaderConfig): A = {
    if ((in eq null) || (config eq null)) throw new NullPointerException
    this.config = config
    this.in = in
    head = 0
    tail = 0
    mark = 2147483647
    totalRead = 0
    try codec.decode(this, codec.nullValue) // also checks that `codec` is not null before any parsing
    finally {
      this.in = null  // to help GC, and to avoid modifying of supplied for parsing Array[Byte]
      freeTooLongBuf()
      freeTooLongCharBuf()
    }
  }

  private def tokenOrNullError(b: Byte, pos: Int = head - 1): Nothing = {
    var i = appendString("expected '", 0)
    i = appendChar(b.toChar, i)
    i = appendString("' or null", i)
    decodeError(i, pos, null)
  }

  private def reqFieldError(s: String): Nothing = {
    var i = appendString("missing required field \"", 0)
    i = appendString(s, i)
    i = appendString("\"", i)
    decodeError(i, head - 1, null)
  }

  private def decodeError(msg: String, pos: Int, cause: Throwable = null): Nothing =
    decodeError(appendString(msg, 0), pos, cause)

  private def decodeError(from: Int, pos: Int, cause: Throwable) = {
    var i = appendString(", offset: 0x", from)
    val offset = if (in eq null) 0 else totalRead - tail
    i = appendHex(offset + pos, i) // TODO: consider support of offset values beyond 2Gb
    if (config.appendHexDumpToParseException) {
      i = appendString(", buf:", i)
      i = appendHexDump(Math.max((pos - 32) & -16, 0), Math.min((pos + 48) & -16, tail), offset, i)
    }
    throw new JsonParseException(new String(charBuf, 0, i), cause, config.throwParseExceptionWithStackTrace)
  }

  @tailrec
  private def nextByte(pos: Int): Byte =
    if (pos < tail) {
      head = pos + 1
      buf(pos)
    } else nextByte(loadMoreOrError(pos))

  @tailrec
  private def nextToken(pos: Int): Byte =
    if (pos < tail) {
      val b = buf(pos)
      if (b == ' ' || b == '\n' || b == '\t' || b == '\r') nextToken(pos + 1)
      else {
        head = pos + 1
        b
      }
    } else nextToken(loadMoreOrError(pos))

  @tailrec
  private def isNextToken(t: Byte, pos: Int): Boolean =
    if (pos < tail) {
      val b = buf(pos)
      if (b == t) {
        head = pos + 1
        true
      } else if (b == ' ' || b == '\n' || b == '\t' || b == '\r') isNextToken(t, pos + 1)
      else {
        head = pos + 1
        false
      }
    } else isNextToken(t, loadMoreOrError(pos))

  private def isCurrentToken(b: Byte, pos: Int): Boolean =
    if (pos == 0) throw new ArrayIndexOutOfBoundsException("expected preceding call of 'nextToken()' or 'isNextToken()'")
    else buf(pos - 1) == b

  @tailrec
  private def parseNullOrError[A](default: A, error: String, pos: Int): A =
    if (pos + 2 < tail) {
      if (buf(pos) != 'u') decodeError(error, pos)
      else if (buf(pos + 1) != 'l') decodeError(error, pos + 1)
      else if (buf(pos + 2) != 'l') decodeError(error, pos + 2)
      else {
        head = pos + 3
        default
      }
    } else parseNullOrError(default, error, loadMoreOrError(pos))

  @tailrec
  private def parseNullOrTokenError[A](default: A, b: Byte, pos: Int): A =
    if (pos + 2 < tail) {
      if (buf(pos) != 'u') tokenOrNullError(b, pos)
      else if (buf(pos + 1) != 'l') tokenOrNullError(b, pos + 1)
      else if (buf(pos + 2) != 'l') tokenOrNullError(b, pos + 2)
      else {
        head = pos + 3
        default
      }
    } else parseNullOrTokenError(default, b, loadMoreOrError(pos))

  @tailrec
  private def isCharBufEqualsTo(len: Int, s: String, i: Int): Boolean =
    if (i == len) true
    else if (charBuf(i) != s.charAt(i)) false
    else isCharBufEqualsTo(len, s, i + 1)

  private def appendChar(ch: Char, from: Int): Int = {
    if (from >= charBuf.length) growCharBuf(from + 1)
    charBuf(from) = ch
    from + 1
  }

  private def appendString(s: String, from: Int): Int = {
    val len = s.length
    val required = from + len
    if (required > charBuf.length) growCharBuf(required)
    s.getChars(0, len, charBuf, from)
    required
  }

  private def prependString(s: String, from: Int): Int = {
    val len = s.length
    val required = from + len
    if (required > charBuf.length) growCharBuf(required)
    var i = required - 1
    while (i >= len) {
      charBuf(i) = charBuf(i - len)
      i -= 1
    }
    i = 0
    s.getChars(0, len, charBuf, 0)
    required
  }

  private def readParenthesesToken(): Unit = if (!isNextToken('"', head)) decodeError("expected '\"'")

  private def readParenthesesByteWithColonToken(): Unit = {
    readParenthesesByte()
    readColonToken()
  }

  private def readParenthesesByte(): Unit = if (nextByte(head) != '"') decodeError("expected '\"'")

  private def readColonToken(): Unit = if (!isNextToken(':', head)) decodeError("expected ':'")

  private def parseBoolean(isToken: Boolean): Boolean =
    (if (isToken) nextToken(head) else nextByte(head): @switch) match {
      case 't' => parseTrue(head)
      case 'f' => parseFalse(head)
      case _ => booleanError(head - 1)
    }

  @tailrec
  private def parseTrue(pos: Int): Boolean =
    if (pos + 2 < tail) {
      if (buf(pos) != 'r') booleanError(pos)
      else if (buf(pos + 1) != 'u') booleanError(pos + 1)
      else if (buf(pos + 2) != 'e') booleanError(pos + 2)
      else {
        head = pos + 3
        true
      }
    } else parseTrue(loadMoreOrError(pos))

  @tailrec
  private def parseFalse(pos: Int): Boolean =
    if (pos + 3 < tail) {
      if (buf(pos) != 'a') booleanError(pos)
      else if (buf(pos + 1) != 'l') booleanError(pos + 1)
      else if (buf(pos + 2) != 's') booleanError(pos + 2)
      else if (buf(pos + 3) != 'e') booleanError(pos + 3)
      else {
        head = pos + 4
        false
      }
    } else parseFalse(loadMoreOrError(pos))

  private def booleanError(pos: Int): Nothing = decodeError("illegal boolean", pos)

  private def parseByte(isToken: Boolean): Byte = {
    var b = if (isToken) nextToken(head) else nextByte(head)
    val negative = b == '-'
    if (negative) b = nextByte(head)
    var pos = head
    if (b >= '0' && b <= '9') {
      var x = '0' - b
      while ((pos < tail || {
        pos = loadMore(pos)
        pos < tail
      }) && {
        b = buf(pos)
        b >= '0' && b <= '9'
      }) pos = {
        if (x == 0) leadingZeroError(pos - 1)
        x = x * 10 + ('0' - b)
        if (x < -128) byteOverflowError(pos)
        pos + 1
      }
      head = pos
      if (b == '.' || (b | 0x20) == 'e') numberError(pos)
      else if (negative) x.toByte
      else if (x == -128) byteOverflowError(pos - 1)
      else (-x).toByte
    } else numberError(pos - 1)
  }

  private def parseShort(isToken: Boolean): Short = {
    var b = if (isToken) nextToken(head) else nextByte(head)
    val negative = b == '-'
    if (negative) b = nextByte(head)
    var pos = head
    if (b >= '0' && b <= '9') {
      var x = '0' - b
      while ((pos < tail || {
        pos = loadMore(pos)
        pos < tail
      }) && {
        b = buf(pos)
        b >= '0' && b <= '9'
      }) pos = {
        if (x == 0) leadingZeroError(pos - 1)
        x = x * 10 + ('0' - b)
        if (x < -32768) shortOverflowError(pos)
        pos + 1
      }
      head = pos
      if (b == '.' || (b | 0x20) == 'e') numberError(pos)
      else if (negative) x.toShort
      else if (x == -32768) shortOverflowError(pos - 1)
      else (-x).toShort
    } else numberError(pos - 1)
  }

  private def parseInt(isToken: Boolean): Int = {
    var b = if (isToken) nextToken(head) else nextByte(head)
    val negative = b == '-'
    if (negative) b = nextByte(head)
    var pos = head
    if (b >= '0' && b <= '9') {
      var x = '0' - b
      while ((pos < tail || {
        pos = loadMore(pos)
        pos < tail
      }) && {
        b = buf(pos)
        b >= '0' && b <= '9'
      }) pos = {
        if (x == 0) leadingZeroError(pos - 1)
        if (x < -214748364) intOverflowError(pos)
        x = x * 10 + ('0' - b)
        if (x >= 0) intOverflowError(pos)
        pos + 1
      }
      head = pos
      if (b == '.' || (b | 0x20) == 'e') numberError(pos)
      else if (negative) x
      else if (x == -2147483648) intOverflowError(pos - 1)
      else -x
    } else numberError(pos - 1)
  }

  private def parseLong(isToken: Boolean): Long = {
    var b = if (isToken) nextToken(head) else nextByte(head)
    val negative = b == '-'
    if (negative) b = nextByte(head)
    var pos = head
    if (b >= '0' && b <= '9') {
      var x: Long = '0' - b
      while ((pos < tail || {
        pos = loadMore(pos)
        pos < tail
      }) && {
        b = buf(pos)
        b >= '0' && b <= '9'
      }) pos = {
        if (x == 0) leadingZeroError(pos - 1)
        if (x < -922337203685477580L) longOverflowError(pos)
        x = x * 10 + ('0' - b)
        if (x >= 0) longOverflowError(pos)
        pos + 1
      }
      head = pos
      if (b == '.' || (b | 0x20) == 'e') numberError(pos)
      else if (negative) x
      else if (x == -9223372036854775808L) longOverflowError(pos - 1)
      else -x
    } else numberError(pos - 1)
  }

  private def parseDouble(isToken: Boolean): Double = {
    var posMan = 0L
    var manExp = 0
    var posExp = 0
    var isNeg = false
    var isExpNeg = false
    var isZeroFirst = false
    var state = 0
    var pos = head
    val mark = this.mark
    this.mark = Math.min(mark, pos)
    try {
      while (pos < tail || {
        pos = loadMore(pos)
        pos < tail
      }) {
        val b = buf(pos)
        (state: @switch) match {
          case 0 => // start
            if (b >= '0' && b <= '9') {
              posMan = b - '0'
              isZeroFirst = posMan == 0
              state = 3
            } else if (b == '-') {
              isNeg = true
              state = 2
            } else if (b == ' ' || b == '\n' || b == '\t' || b == '\r') {
              if (!isToken) numberError(pos)
              state = 1
            } else numberError(pos)
          case 1 => // whitespaces
            if (b == ' ' || b == '\n' || b == '\t' || b == '\r') {
              // state = 1
            } else if (b >= '0' && b <= '9') {
              posMan = b - '0'
              isZeroFirst = posMan == 0
              state = 3
            } else if (b == '-') {
              isNeg = true
              state = 2
            } else numberError(pos)
          case 2 => // signum
            if (b >= '0' && b <= '9') {
              posMan = b - '0'
              isZeroFirst = posMan == 0
              state = 3
            } else numberError(pos)
          case 3 => // first int digit
            if (b >= '0' && b <= '9') {
              posMan = posMan * 10 + (b - '0')
              if (isZeroFirst) leadingZeroError(pos - 1)
              state = 4
            } else if (b == '.') {
              state = 5
            } else if ((b | 0x20) == 'e') {
              state = 7
            } else {
              head = pos
              return toDouble(isNeg, posMan, manExp, isExpNeg, posExp)
            }
          case 4 => // int digit
            if (b >= '0' && b <= '9') {
              if (posMan < 4503599627370496L) posMan = posMan * 10 + (b - '0')
              else manExp += 1
              // state = 4
            } else if (b == '.') {
              state = 5
            } else if ((b | 0x20) == 'e') {
              state = 7
            } else {
              head = pos
              return toDouble(isNeg, posMan, manExp, isExpNeg, posExp)
            }
          case 5 => // dot
            if (b >= '0' && b <= '9') {
              if (posMan < 4503599627370496L) {
                posMan = posMan * 10 + (b - '0')
                manExp -= 1
              }
              state = 6
            } else numberError(pos)
          case 6 => // frac digit
            if (b >= '0' && b <= '9') {
              if (posMan < 4503599627370496L) {
                posMan = posMan * 10 + (b - '0')
                manExp -= 1
              }
              // state = 6
            } else if ((b | 0x20) == 'e') {
              state = 7
            } else {
              head = pos
              return toDouble(isNeg, posMan, manExp, isExpNeg, posExp)
            }
          case 7 => // e char
            if (b >= '0' && b <= '9') {
              posExp = b - '0'
              state = 9
            } else if (b == '-') {
              isExpNeg = true
              state = 8
            } else if (b == '+') {
              state = 8
            } else numberError(pos)
          case 8 => // exp. sign
            if (b >= '0' && b <= '9') {
              posExp = b - '0'
              state = 9
            } else numberError(pos)
          case 9 => // exp. digit
            if (b >= '0' && b <= '9') {
              posExp = posExp * 10 + (b - '0')
              if (Math.abs(toExp(manExp, isExpNeg, posExp)) > 350) state = 10 // else state = 9
            } else {
              head = pos
              return toDouble(isNeg, posMan, manExp, isExpNeg, posExp)
            }
          case 10 => // exp. digit overflow
            if (b >= '0' && b <= '9') {
              // state = 10
            } else {
              head = pos
              return toExpOverflowDouble(isNeg, posMan, manExp, isExpNeg, posExp)
            }
        }
        pos += 1
      }
      head = pos
      if (state == 3 || state == 4 || state == 6 || state == 9) toDouble(isNeg, posMan, manExp, isExpNeg, posExp)
      else if (state == 10) toExpOverflowDouble(isNeg, posMan, manExp, isExpNeg, posExp)
      else numberError(pos)
    } finally this.mark = mark
  }

  private def toDouble(isNeg: Boolean, posMan: Long, manExp: Int, isExpNeg: Boolean, posExp: Int): Double =
    if (posMan < 4503599627370496L) { // 4503599627370496L == 1L < 52, max mantissa that can be converted w/o rounding error by double mul or div
      val man = if (isNeg) -posMan else posMan
      val exp = toExp(manExp, isExpNeg, posExp)
      if (exp == 0) man
      else if (exp < 0 && exp > -22) man / pow10(-exp) // 22 == pow10.length
      else if (exp > 0 && exp < 22) man * pow10(exp)
      else toDouble
    } else toDouble

  private def toDouble: Double = java.lang.Double.parseDouble(new String(buf, 0, mark, head - mark))

  private def toExpOverflowDouble(isNeg: Boolean, posMan: Long, manExp: Int, isExpNeg: Boolean, posExp: Int): Double =
    if (toExp(manExp, isExpNeg, posExp) > 0 && posMan != 0) {
      if (isNeg) Double.NegativeInfinity else Double.PositiveInfinity
    } else {
      if (isNeg) -0.0 else 0.0
    }

  private def toExp(manExp: Int, isExpNeg: Boolean, exp: Int): Int = manExp + (if (isExpNeg) -exp else exp)

  private def parseBigInt(isToken: Boolean, default: BigInt = null): BigInt = {
    var b = if (isToken) nextToken(head) else nextByte(head)
    if (b == 'n') {
      if (isToken) parseNullOrError(default, "expected number value or null", head)
      else numberError(head)
    } else {
      val mark = this.mark
      this.mark = Math.min(mark, head - 1)
      try {
        val negative = b == '-'
        if (negative) b = nextByte(head)
        var pos = head
        if (b >= '0' && b <= '9') {
          val isZeroFirst = b == '0'
          while ((pos < tail || {
            pos = loadMore(pos)
            pos < tail
          }) && {
            b = buf(pos)
            b >= '0' && b <= '9'
          }) pos = {
            if (isZeroFirst) leadingZeroError(pos - 1)
            pos + 1
          }
          head = pos
          if (b == '.' || (b | 0x20) == 'e') numberError(pos)
          toBigInt(negative, pos)
        } else numberError(pos - 1)
      } finally this.mark = mark
    }
  }

  private def toBigInt(negative: Boolean, pos: Int): BigInt = {
    var signPos = this.mark
    var numPos = signPos + (if (negative) 1 else 0)
    if (pos < 19 + numPos) { // 19 == Long.MaxValue.toString.length
      var x = 0L
      while (numPos < pos) {
        x = x * 10 + (buf(numPos) - '0')
        numPos += 1
      }
      new BigInt(BigInteger.valueOf(if (negative) -x else x))
    } else {
      val required = pos - signPos
      var charBuf = this.charBuf
      if (required > charBuf.length) {
        growCharBuf(required)
        charBuf = this.charBuf
      }
      var i = 0
      while (signPos < pos) signPos = {
        charBuf(i) = buf(signPos).toChar
        i += 1
        signPos + 1
      }
      new BigInt(new java.math.BigDecimal(charBuf, 0, i).toBigIntegerExact)
    }
  }

  private def parseBigDecimal(isToken: Boolean, default: BigDecimal): BigDecimal = {
    var isZeroFirst = false
    var charBuf = this.charBuf
    var lim = charBuf.length
    var i = 0
    var state = 0
    var pos = head
    while (pos < tail || {
      pos = loadMore(pos)
      pos < tail
    }) {
      if (i >= lim) {
        lim = growCharBuf(i + 1)
        charBuf = this.charBuf
      }
      val b = buf(pos)
      (state: @switch) match {
        case 0 => // start
          if (b >= '0' && b <= '9') {
            charBuf(i) = b.toChar
            i += 1
            isZeroFirst = b == '0'
            state = 3
          } else if (b == '-') {
            charBuf(i) = b.toChar
            i += 1
            state = 2
          } else if (b == 'n' && isToken) {
            state = 10
          } else if (b == ' ' || b == '\n' || b == '\t' || b == '\r') {
            if (!isToken) numberError(pos)
            state = 1
          } else numberError(pos)
        case 1 => // whitespaces
          if (b == ' ' || b == '\n' || b == '\t' || b == '\r') {
            // state = 1
          } else if (b >= '0' && b <= '9') {
            charBuf(i) = b.toChar
            i += 1
            isZeroFirst = b == '0'
            state = 3
          } else if (b == '-') {
            charBuf(i) = b.toChar
            i += 1
            state = 2
          } else if (b == 'n' && isToken) {
            state = 10
          } else numberError(pos)
        case 2 => // signum
          if (b >= '0' && b <= '9') {
            charBuf(i) = b.toChar
            i += 1
            isZeroFirst = b == '0'
            state = 3
          } else numberError(pos)
        case 3 => // first int digit
          if (b >= '0' && b <= '9') {
            if (isZeroFirst) leadingZeroError(pos - 1)
            charBuf(i) = b.toChar
            i += 1
            state = 4
          } else if (b == '.') {
            charBuf(i) = b.toChar
            i += 1
            state = 5
          } else if ((b | 0x20) == 'e') {
            charBuf(i) = b.toChar
            i += 1
            state = 7
          } else {
            head = pos
            return toBigDecimal(i)
          }
        case 4 => // int digit
          if (b >= '0' && b <= '9') {
            charBuf(i) = b.toChar
            i += 1
            // state = 4
          } else if (b == '.') {
            charBuf(i) = b.toChar
            i += 1
            state = 5
          } else if ((b | 0x20) == 'e') {
            charBuf(i) = b.toChar
            i += 1
            state = 7
          } else {
            head = pos
            return toBigDecimal(i)
          }
        case 5 => // dot
          if (b >= '0' && b <= '9') {
            charBuf(i) = b.toChar
            i += 1
            state = 6
          } else numberError(pos)
        case 6 => // frac digit
          if (b >= '0' && b <= '9') {
            charBuf(i) = b.toChar
            i += 1
            // state = 6
          } else if ((b | 0x20) == 'e') {
            charBuf(i) = b.toChar
            i += 1
            state = 7
          } else {
            head = pos
            return toBigDecimal(i)
          }
        case 7 => // e char
          if (b >= '0' && b <= '9') {
            charBuf(i) = b.toChar
            i += 1
            state = 9
          } else if (b == '-' || b == '+') {
            charBuf(i) = b.toChar
            i += 1
            state = 8
          } else numberError(pos)
        case 8 => // exp. sign
          if (b >= '0' && b <= '9') {
            charBuf(i) = b.toChar
            i += 1
            state = 9
          } else numberError(pos)
        case 9 => // exp. digit
          if (b >= '0' && b <= '9') {
            charBuf(i) = b.toChar
            i += 1
            // state = 9
          } else {
            head = pos
            return toBigDecimal(i)
          }
        case 10 => // n'u'll
          if (b == 'u') state = 11
          else numberError(pos)
        case 11 => // nu'l'l
          if (b == 'l') state = 12
          else numberError(pos)
        case 12 => // nul'l'
          if (b == 'l') state = 13
          else numberError(pos)
        case 13 => // null
          head = pos
          return default
      }
      pos += 1
    }
    head = pos
    if (state == 3 || state == 4 || state == 6 || state == 9) toBigDecimal(i)
    else if (state == 13) default
    else numberError(pos)
  }

  private def toBigDecimal(len: Int): BigDecimal =
    try new BigDecimal(new java.math.BigDecimal(charBuf, 0, len)) catch {
      case ex: NumberFormatException => decodeError("illegal number", head - 1, ex)
    }

  private def numberError(pos: Int): Nothing = decodeError("illegal number", pos)

  private def leadingZeroError(pos: Int): Nothing = decodeError("illegal number with leading zero", pos)

  private def byteOverflowError(pos: Int): Nothing = decodeError("value is too large for byte", pos)

  private def shortOverflowError(pos: Int): Nothing = decodeError("value is too large for short", pos)

  private def intOverflowError(pos: Int): Nothing = decodeError("value is too large for int", pos)

  private def longOverflowError(pos: Int): Nothing = decodeError("value is too large for long", pos)

  private def parseString(): Int = {
    val pos = head
    val charBuf = this.charBuf
    parseString(0, Math.min(charBuf.length, tail - pos), charBuf, pos)
  }

  @tailrec
  private def parseString(i: Int, minLim: Int, charBuf: Array[Char], pos: Int): Int =
    if (i < minLim) {
      val b = buf(pos)
      if (b == '"') {
        head = pos + 1
        i
      } else if ((b ^ '\\') > 0) {
        charBuf(i) = b.toChar
        parseString(i + 1, minLim, charBuf, pos + 1)
      } else parseEncodedString(i, charBuf.length, charBuf, pos)
    } else if (pos >= tail) {
      val newPos = loadMoreOrError(pos)
      parseString(i, i + Math.min(minLim - i, tail - newPos), charBuf, newPos)
    } else parseString(i, i + Math.min(growCharBuf(i + 1) - i, tail - pos), this.charBuf, pos)

  @tailrec
  private def parseEncodedString(i: Int, lim: Int, charBuf: Array[Char], pos: Int): Int =
    if (i >= lim) parseEncodedString(i, growCharBuf(i + 2), this.charBuf, pos) // 2 is length of surrogate pair
    else {
      val remaining = tail - pos
      if (remaining > 0) {
        val b1 = buf(pos)
        if (b1 >= 0) { // 1 byte, 7 bits: 0xxxxxxx
          if (b1 == '"') {
            head = pos + 1
            i
          } else if (b1 != '\\') {
            charBuf(i) = b1.toChar
            parseEncodedString(i + 1, lim, charBuf, pos + 1)
          } else if (remaining > 1) {
            val b2 = buf(pos + 1)
            if (b2 == 'u') {
              if (remaining > 5) {
                val ch1 = readEscapedUnicode(pos + 2)
                if (ch1 < 0xD800 || ch1 > 0xDFFF) {
                  charBuf(i) = ch1
                  parseEncodedString(i + 1, lim, charBuf, pos + 6)
                } else if (remaining > 11) {
                  if (buf(pos + 6) == '\\') {
                    if (buf(pos + 7) == 'u') {
                      val ch2 = readEscapedUnicode(pos + 8)
                      if (ch1 >= 0xDC00 || ch2 < 0xDC00 || ch2 > 0xDFFF) decodeError("illegal surrogate character pair", pos + 11)
                      charBuf(i) = ch1
                      charBuf(i + 1) = ch2
                      parseEncodedString(i + 2, lim, charBuf, pos + 12)
                    } else illegalEscapeSequenceError(pos + 7)
                  } else illegalEscapeSequenceError(pos + 6)
                } else parseEncodedString(i, lim, charBuf, loadMoreOrError(pos))
              } else parseEncodedString(i, lim, charBuf, loadMoreOrError(pos))
            } else {
              charBuf(i) = (b2: @switch) match {
                case '"' => '"'
                case 'n' => '\n'
                case 'r' => '\r'
                case 't' => '\t'
                case 'b' => '\b'
                case 'f' => '\f'
                case '\\' => '\\'
                case '/' => '/'
                case _ => illegalEscapeSequenceError(pos + 1)
              }
              parseEncodedString(i + 1, lim, charBuf, pos + 2)
            }
          } else parseEncodedString(i, lim, charBuf, loadMoreOrError(pos))
        } else if ((b1 >> 5) == -2) { // 2 bytes, 11 bits: 110xxxxx 10xxxxxx
          if (remaining > 1) {
            val b2 = buf(pos + 1)
            if (isMalformedBytes2(b1, b2)) malformedBytesError(b1, b2, pos)
            charBuf(i) = ((b1 << 6) ^ b2 ^ 0xF80).toChar // 0xF80 == ((0xC0.toByte << 6) ^ 0x80.toByte)
            parseEncodedString(i + 1, lim, charBuf, pos + 2)
          } else parseEncodedString(i, lim, charBuf, loadMoreOrError(pos))
        } else if ((b1 >> 4) == -2) { // 3 bytes, 16 bits: 1110xxxx 10xxxxxx 10xxxxxx
          if (remaining > 2) {
            val b2 = buf(pos + 1)
            val b3 = buf(pos + 2)
            val ch = ((b1 << 12) ^ (b2 << 6) ^ b3 ^ 0xFFFE1F80).toChar // 0xFFFE1F80 == ((0xE0.toByte << 12) ^ (0x80.toByte << 6) ^ 0x80.toByte)
            if (isMalformedBytes3(b1, b2, b3) || (ch >= 0xD800 && ch <= 0xDFFF)) malformedBytesError(b1, b2, b3, pos)
            charBuf(i) = ch
            parseEncodedString(i + 1, lim, charBuf, pos + 3)
          } else parseEncodedString(i, lim, charBuf, loadMoreOrError(pos))
        } else if ((b1 >> 3) == -2) { // 4 bytes, 21 bits: 11110xxx 10xxxxxx 10xxxxxx 10xxxxxx
          if (remaining > 3) {
            val b2 = buf(pos + 1)
            val b3 = buf(pos + 2)
            val b4 = buf(pos + 3)
            val cp = (b1 << 18) ^ (b2 << 12) ^ (b3 << 6) ^ b4 ^ 0x381F80 // 0x381F80 == ((0xF0.toByte << 18) ^ (0x80.toByte << 12) ^ (0x80.toByte << 6) ^ 0x80.toByte)
            if (isMalformedBytes4(b2, b3, b4) || cp < 0x010000 || cp >= 0x10FFFF) malformedBytesError(b1, b2, b3, b4, pos)
            charBuf(i) = ((cp >>> 10) + 0xD7C0).toChar // 0xD7C0 == 0xD800 - (0x010000 >>> 10)
            charBuf(i + 1) = ((cp & 0x3FF) + 0xDC00).toChar
            parseEncodedString(i + 2, lim, charBuf, pos + 4)
          } else parseEncodedString(i, lim, charBuf, loadMoreOrError(pos))
        } else malformedBytes(b1, pos)
      } else parseEncodedString(i, lim, charBuf, loadMoreOrError(pos))
    }

  @tailrec
  private def parseChar(pos: Int): Char = {
    val remaining = tail - pos
    if (remaining > 0) {
      val b1 = buf(pos)
      if (b1 >= 0) { // 1 byte, 7 bits: 0xxxxxxx
        if (b1 == '"') decodeError("illegal value for char")
        else if (b1 != '\\') {
          head = pos + 1
          b1.toChar
        } else if (remaining > 1) {
          val b2 = buf(pos + 1)
          if (b2 == 'u') {
            if (remaining > 5) {
              val ch = readEscapedUnicode(pos + 2)
              if (ch >= 0xD800 && ch <= 0xDFFF) decodeError("illegal surrogate character", pos + 5)
              head = pos + 6
              ch
            } else parseChar(loadMoreOrError(pos))
          } else {
            head = pos + 2
            (b2: @switch) match {
              case 'b' => '\b'
              case 'f' => '\f'
              case 'n' => '\n'
              case 'r' => '\r'
              case 't' => '\t'
              case '"' => '"'
              case '/' => '/'
              case '\\' => '\\'
              case _ => illegalEscapeSequenceError(pos + 1)
            }
          }
        } else parseChar(loadMoreOrError(pos))
      } else if ((b1 >> 5) == -2) { // 2 bytes, 11 bits: 110xxxxx 10xxxxxx
        if (remaining > 1) {
          val b2 = buf(pos + 1)
          if (isMalformedBytes2(b1, b2)) malformedBytesError(b1, b2, pos)
          head = pos + 2
          ((b1 << 6) ^ b2 ^ 0xF80).toChar // 0xF80 == ((0xC0.toByte << 6) ^ 0x80.toByte)
        } else parseChar(loadMoreOrError(pos))
      } else if ((b1 >> 4) == -2) { // 3 bytes, 16 bits: 1110xxxx 10xxxxxx 10xxxxxx
        if (remaining > 2) {
          val b2 = buf(pos + 1)
          val b3 = buf(pos + 2)
          val ch = ((b1 << 12) ^ (b2 << 6) ^ b3 ^ 0xFFFE1F80).toChar // 0xFFFE1F80 == ((0xE0.toByte << 12) ^ (0x80.toByte << 6) ^ 0x80.toByte)
          if (isMalformedBytes3(b1, b2, b3) || (ch >= 0xD800 && ch <= 0xDFFF)) malformedBytesError(b1, b2, b3, pos)
          head = pos + 3
          ch
        } else parseChar(loadMoreOrError(pos))
      } else if ((b1 >> 3) == -2) decodeError("illegal surrogate character", pos + 3)
      else malformedBytes(b1, pos)
    } else parseChar(loadMoreOrError(pos))
  }

  private def readEscapedUnicode(pos: Int): Char =
    ((fromHexDigit(pos) << 12) + (fromHexDigit(pos + 1) << 8) + (fromHexDigit(pos + 2) << 4) + fromHexDigit(pos + 3)).toChar

  private def fromHexDigit(pos: Int): Int = {
    val b = buf(pos)
    if (b >= '0' && b <= '9') b - '0'
    else {
      val b1 = b | 0x20 // 0x20 == 'A' ^ 'a' or 'B' ^ 'b', etc.
      if (b1 >= 'a' && b1 <= 'f') b1 - 0x57 // 0x57 == 'a' - 10
      else decodeError("expected hex digit", pos)
    }
  }

  private def isMalformedBytes2(b1: Byte, b2: Byte): Boolean =
    (b1 & 0x1E) == 0 || (b2 & 0xC0) != 0x80

  private def isMalformedBytes3(b1: Byte, b2: Byte, b3: Byte): Boolean =
    (b1 == 0xE0.toByte && (b2 & 0xE0) == 0x80) || (b2 & 0xC0) != 0x80 || (b3 & 0xC0) != 0x80

  private def isMalformedBytes4(b2: Byte, b3: Byte, b4: Byte): Boolean =
    (b2 & 0xC0) != 0x80 || (b3 & 0xC0) != 0x80 || (b4 & 0xC0) != 0x80

  private def illegalEscapeSequenceError(pos: Int): Nothing = decodeError("illegal escape sequence", pos)

  private def malformedBytes(b1: Byte, pos: Int): Nothing = {
    var i = appendString("malformed byte(s): 0x", 0)
    i = appendHex(b1, i)
    decodeError(i, pos, null)
  }

  private def malformedBytesError(b1: Byte, b2: Byte, pos: Int): Nothing = {
    var i = appendString("malformed byte(s): 0x", 0)
    i = appendHex(b1, i)
    i = appendString(", 0x", i)
    i = appendHex(b2, i)
    decodeError(i, pos + 1, null)
  }

  private def malformedBytesError(b1: Byte, b2: Byte, b3: Byte, pos: Int): Nothing = {
    var i = appendString("malformed byte(s): 0x", 0)
    i = appendHex(b1, i)
    i = appendString(", 0x", i)
    i = appendHex(b2, i)
    i = appendString(", 0x", i)
    i = appendHex(b3, i)
    decodeError(i, pos + 2, null)
  }

  private def malformedBytesError(b1: Byte, b2: Byte, b3: Byte, b4: Byte, pos: Int): Nothing = {
    var i = appendString("malformed byte(s): 0x", 0)
    i = appendHex(b1, i)
    i = appendString(", 0x", i)
    i = appendHex(b2, i)
    i = appendString(", 0x", i)
    i = appendHex(b3, i)
    i = appendString(", 0x", i)
    i = appendHex(b4, i)
    decodeError(i, pos + 3, null)
  }

  private def appendHexDump(start: Int, end: Int, offset: Int, from: Int): Int = {
    val alignedAbsFrom = (start + offset) & -16
    val alignedAbsTo = (end + offset + 15) & -16
    val len = alignedAbsTo - alignedAbsFrom
    val bufOffset = alignedAbsFrom - offset
    var i = appendString(dumpHeader, from)
    i = appendString(dumpBorder, i)
    var charBuf = this.charBuf
    var lim = charBuf.length
    var j = 0
    while (j < len) {
      val linePos = j & 15
      if (linePos == 0) {
        if (i + 81 >= lim) { // 81 == dumpBorder.length
          lim = growCharBuf(i + 81)
          charBuf = this.charBuf
        }
        charBuf(i) = '\n'
        charBuf(i + 1) = '|'
        charBuf(i + 2) = ' '
        putHex(alignedAbsFrom + j, i + 3, charBuf)
        charBuf(i + 11) = ' '
        charBuf(i + 12) = '|'
        charBuf(i + 13) = ' '
        i += 14
      }
      val pos = bufOffset + j
      charBuf(i + 50 - (linePos << 1)) =
        if (pos >= start && pos < end) {
          val b = buf(pos)
          putHex(b, i, charBuf)
          charBuf(i + 2) = ' '
          if (b <= 31 || b >= 127) '.' else b.toChar
        } else {
          charBuf(i) = ' '
          charBuf(i + 1) = ' '
          charBuf(i + 2) = ' '
          ' '
        }
      i += 3
      if (linePos == 15) {
        charBuf(i) = '|'
        charBuf(i + 1) = ' '
        charBuf(i + 18) = ' '
        charBuf(i + 19) = '|'
        i += 20
      }
      j += 1
    }
    appendString(dumpBorder, i)
  }

  private def appendHex(d: Int, i: Int): Int = {
    if (i + 8 >= charBuf.length) growCharBuf(i + 8)
    putHex(d, i, charBuf)
    i + 8
  }

  private def putHex(d: Int, i: Int, charBuf: Array[Char]): Unit = {
    charBuf(i) = toHexDigit(d >>> 28)
    charBuf(i + 1) = toHexDigit(d >>> 24)
    charBuf(i + 2) = toHexDigit(d >>> 20)
    charBuf(i + 3) = toHexDigit(d >>> 16)
    charBuf(i + 4) = toHexDigit(d >>> 12)
    charBuf(i + 5) = toHexDigit(d >>> 8)
    charBuf(i + 6) = toHexDigit(d >>> 4)
    charBuf(i + 7) = toHexDigit(d)
  }

  private def appendHex(b: Byte, i: Int): Int = {
    if (i + 2 >= charBuf.length) growCharBuf(i + 2)
    putHex(b, i, charBuf)
    i + 2
  }

  private def putHex(b: Byte, i: Int, charBuf: Array[Char]): Unit = {
    charBuf(i) = toHexDigit(b >>> 4)
    charBuf(i + 1) = toHexDigit(b)
  }

  private def toHexDigit(n: Int): Char = {
    val nibble = n & 15
    (((9 - nibble) >> 31) & 39) + nibble + 48 // branchless conversion of nibble to hex digit
  }.toChar

  private def growCharBuf(required: Int): Int = {
    val lim = charBuf.length
    val newLim = Math.max(lim << 1, required)
    val cs = new Array[Char](newLim)
    System.arraycopy(charBuf, 0, cs, 0, lim)
    charBuf = cs
    newLim
  }

  @tailrec
  private def skipString(evenBackSlashes: Boolean, pos: Int): Int =
    if (pos < tail) {
      val b = buf(pos)
      if (b == '"' && evenBackSlashes) pos + 1
      else skipString(b != '\\' || !evenBackSlashes, pos + 1)
    } else skipString(evenBackSlashes, loadMoreOrError(pos))

  @tailrec
  private def skipNumber(pos: Int): Int =
    if (pos < tail) {
      val b = buf(pos)
      if ((b >= '0' && b <= '9') || b == '.' || (b | 0x20) == 'e' || b == '-' || b == '+') skipNumber(pos + 1)
      else pos
    } else skipNumber(loadMoreOrError(pos))

  @tailrec
  private def skipNested(opening: Byte, closing: Byte, level: Int, pos: Int): Int =
    if (pos < tail) {
      val b = buf(pos)
      if (b == '"') skipNested(opening, closing, level, skipString(evenBackSlashes = true, pos + 1))
      else if (b == closing) {
        if (level == 0) pos + 1
        else skipNested(opening, closing, level - 1, pos + 1)
      } else if (b == opening) skipNested(opening, closing, level + 1, pos + 1)
      else skipNested(opening, closing, level, pos + 1)
    } else skipNested(opening, closing, level, loadMoreOrError(pos))

  @tailrec
  private def skipFixedBytes(n: Int, pos: Int): Int = {
    val newPos = pos + n
    val diff = newPos - tail
    if (diff <= 0) newPos
    else skipFixedBytes(diff, loadMoreOrError(pos))
  }

  private def loadMoreOrError(pos: Int): Int =
    if (in eq null) endOfInputError()
    else {
      val minPos = ensureBufCapacity(pos)
      val n = externalRead(tail, buf.length - tail)
      if (n > 0) {
        tail += n
        totalRead += n
        pos - minPos
      } else endOfInputError()
    }

  private def loadMore(pos: Int): Int =
    if (in eq null) pos
    else {
      val minPos = ensureBufCapacity(pos)
      val n = externalRead(tail, buf.length - tail)
      if (n > 0) {
        tail += n
        totalRead += n
      }
      pos - minPos
    }

  private def ensureBufCapacity(pos: Int): Int = {
    val minPos = Math.min(mark, pos)
    if (minPos > 0) {
      val remaining = tail - minPos
      if (remaining > 0) {
        System.arraycopy(buf, minPos, buf, 0, remaining)
        if (mark != 2147483647) mark -= minPos
      }
      tail = remaining
    } else if (tail > 0) {
      val bs = new Array[Byte](buf.length << 1)
      System.arraycopy(buf, 0, bs, 0, buf.length)
      buf = bs
    }
    minPos
  }

  private def externalRead(from: Int, len: Int): Int = try in.read(buf, from, len) catch {
    case NonFatal(ex) => endOfInputError(ex)
  }

  private def endOfInputError(cause: Throwable = null): Nothing = decodeError("unexpected end of input", tail, cause)

  private def freeTooLongBuf(): Unit =
    if (buf.length > config.preferredBufSize) buf = new Array[Byte](config.preferredBufSize)

  private def freeTooLongCharBuf(): Unit =
    if (charBuf.length > config.preferredCharBufSize) charBuf = new Array[Char](config.preferredCharBufSize)
}

object JsonReader {
  private val pool: ThreadLocal[JsonReader] = new ThreadLocal[JsonReader] {
    override def initialValue(): JsonReader = new JsonReader
  }
  private val defaultConfig = new ReaderConfig
  private val pow10: Array[Double] = // all powers of 10 that can be represented exactly in double
    Array(1, 1e+1, 1e+2, 1e+3, 1e+4, 1e+5, 1e+6, 1e+7, 1e+8, 1e+9, 1e+10, 1e+11,
      1e+12, 1e+13, 1e+14, 1e+15, 1e+16, 1e+17, 1e+18, 1e+19, 1e+20, 1e+21, 1e+22)
  private val dumpHeader =
    "\n           +-------------------------------------------------+" +
    "\n           |  0  1  2  3  4  5  6  7  8  9  a  b  c  d  e  f |"
  private val dumpBorder =
    "\n+----------+-------------------------------------------------+------------------+"

  /**
    * Deserialize JSON content encoded in UTF-8 from an input stream into a value of given `A` type 
    * with default parsing options that maximize description of error. 
    * 
    * Use custom configuration to turn on racing of stack less exceptions and/or turn off a hex dump printing 
    * to the error message. 
    *
    * @param codec a codec for the given `A` type
    * @param in the input stream to parse from
    * @tparam A type of the value to parse
    * @return a successfully parsed value
    * @throws JsonParseException if underlying input contains malformed UTF-8 bytes, invalid JSON content or
    *                            the input JSON structure does not match structure that expected for result type,
    *                            also if a low-level I/O problem (unexpected end of input, network error) occurs
    *                            while some input bytes are expected
    * @throws NullPointerException If the `codec` or `in` is null.
    */
  final def read[A](codec: JsonCodec[A], in: InputStream): A = pool.get.read(codec, in, defaultConfig)

  /**
    * Deserialize JSON content encoded in UTF-8 from an input stream into a value of given `A` type.
    *
    * @param codec a codec for the given `A` type
    * @param in the input stream to parse from
    * @param config a parsing configuration
    * @tparam A type of the value to parse
    * @return a successfully parsed value
    * @throws JsonParseException if underlying input contains malformed UTF-8 bytes, invalid JSON content or
    *                            the input JSON structure does not match structure that expected for result type,
    *                            also if a low-level I/O problem (unexpected end-of-input, network error) occurs
    *                            while some input bytes are expected
    * @throws NullPointerException if the `codec`, `in` or `config` is null
    */
  final def read[A](codec: JsonCodec[A], in: InputStream, config: ReaderConfig): A = pool.get.read(codec, in, config)

  /**
    * Deserialize JSON content encoded in UTF-8 from a byte array into a value of given `A` type
    * with default parsing options that maximize description of error. 
    *
    * Use custom configuration to turn on racing of stack less exceptions and/or turn off a hex dump printing 
    * to the error message. 
    *
    * @param codec a codec for the given `A` type
    * @param buf the byte array to parse from
    * @tparam A type of the value to parse
    * @return a successfully parsed value
    * @throws JsonParseException if underlying input contains malformed UTF-8 bytes, invalid JSON content or
    *                            the input JSON structure does not match structure that expected for result type,
    *                            also in case if end of input is detected while some input bytes are expected
    * @throws NullPointerException If the `codec` or `buf` is null.
    */
  final def read[A](codec: JsonCodec[A], buf: Array[Byte]): A =
    pool.get.read(codec, buf, 0, buf.length, defaultConfig)

  /**
    * Deserialize JSON content encoded in UTF-8 from a byte array into a value of given `A` type
    * with specified parsing options.
    *
    * @param codec a codec for the given `A` type
    * @param buf the byte array to parse from
    * @param config a parsing configuration
    * @tparam A type of the value to parse
    * @return a successfully parsed value
    * @throws JsonParseException if underlying input contains malformed UTF-8 bytes, invalid JSON content or
    *                            the input JSON structure does not match structure that expected for result type,
    *                            also in case if end of input is detected while some input bytes are expected
    * @throws NullPointerException if the `codec`, `buf` or `config` is null
    */
  final def read[A](codec: JsonCodec[A], buf: Array[Byte], config: ReaderConfig): A =
    pool.get.read(codec, buf, 0, buf.length, config)

  /**
    * Deserialize JSON content encoded in UTF-8 from a byte array into a value of given `A` type with
    * specified parsing options or with defaults that maximize description of error. 
    *
    * @param codec a codec for the given `A` type
    * @param buf the byte array to parse from
    * @param from the start position of the provided byte array
    * @param to the position of end of input in the provided byte array
    * @param config a parsing configuration
    * @tparam A type of the value to parse
    * @return a successfully parsed value
    * @throws JsonParseException if underlying input contains malformed UTF-8 bytes, invalid JSON content or
    *                            the input JSON structure does not match structure that expected for result type,
    *                            also in case if end of input is detected while some input bytes are expected
    * @throws NullPointerException if the `codec`, `buf` or `config` is null
    * @throws ArrayIndexOutOfBoundsException if the `to` is greater than `buf` length or negative,
    *                                        or `from` is greater than `to` or negative
    */
  final def read[A](codec: JsonCodec[A], buf: Array[Byte], from: Int, to: Int, config: ReaderConfig = defaultConfig): A =
    pool.get.read(codec, buf, from, to, config)

  final def toHashCode(cs: Array[Char], len: Int): Int = {
    var h = 0
    var i = 0
    while (i < len) i = {
      h = (h << 5) - h + cs(i)
      i + 1
    }
    h
  }
}