package io.muserver

import io.muserver.ParseUtils.A
import io.muserver.ParseUtils.A_LOWER
import io.muserver.ParseUtils.COLON
import io.muserver.ParseUtils.CR
import io.muserver.ParseUtils.F
import io.muserver.ParseUtils.F_LOWER
import io.muserver.ParseUtils.HTAB
import io.muserver.ParseUtils.LF
import io.muserver.ParseUtils.NINE
import io.muserver.ParseUtils.SEMICOLON
import io.muserver.ParseUtils.SP
import io.muserver.ParseUtils.Z
import io.muserver.ParseUtils.ZERO
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.text.ParseException
import java.util.*

internal interface Http1MessageReader {
    @Throws(IOException::class, ParseException::class)
    fun readNext() : Http1ConnectionMsg
}
internal class Http1MessageParser(
    type: HttpMessageType,
    private val requestQueue: Queue<HttpRequestTemp>,
    private val source: InputStream,
    private val maxHeadersLength: Int,
    private val maxUrlLength: Int,
) : Http1MessageReader {

    private val maxBufferSize = maxOf(maxHeadersLength, maxUrlLength)

    /**
     * The number of bytes remaining to be sent in a fixed body size, or in the current chunk of chunked data (or MAX_LENGTH for unspecified lengths)
     */
    private var remainingBytesToProxy: Long = 0L
    private var state : ParseState
    private val buffer = ByteArrayOutputStream()
    private var exchange : HttpMessageTemp
    private var headerName : String? = null
    private var curHeadersLen = 0
    init {
        if (type == HttpMessageType.REQUEST) {
            exchange = HttpRequestTemp.empty()
            state = ParseState.REQUEST_START
        } else {
            exchange = HttpResponseTemp.empty()
            state = ParseState.RESPONSE_START
        }
    }
    
    val readBuffer = ByteArray(8192)
    private var position = 0
    private var limit = 0
    private fun remaining() = limit - position
    private fun hasRemaining() = remaining() > 0
    override fun readNext() : Http1ConnectionMsg {
        if (limit == -1) return EOFMsg
        while (true) {
            if (!hasRemaining()) {
                position = 0
                limit = source.read(readBuffer)
                if (limit == -1) {
                    if (state == ParseState.UNSPECIFIED_BODY) {
                        return EndOfBodyBit
                    }
                    return EOFMsg
                }
            }
            while (hasRemaining()) {
                val b = readBuffer[position]
                when (state) {
                    ParseState.REQUEST_START -> {
                        if (b.isUpperCase()) {
                            requestQueue.offer(exchange as HttpRequestTemp)
                            state = ParseState.METHOD
                            buffer.append(b)
                        } else throw ParseException("state=$state b=$b", position)
                    }

                    ParseState.METHOD -> {
                        if (b.isUpperCase()) {
                            buffer.append(b)
                        } else if (b == SP) {
                            request().method = try {
                                Method.valueOf(buffer.consumeAscii())
                            } catch (e: IllegalArgumentException) {
                                request().rejectRequest = HttpException(HttpStatus.METHOD_NOT_ALLOWED_405)
                                Method.GET // bit weird - but we need some method
                            }
                            state = ParseState.REQUEST_TARGET
                        } else throw ParseException("state=$state b=$b", position)
                    }

                    ParseState.REQUEST_TARGET -> {
                        val req = request()
                        if (b.isVChar()) { // todo: only allow valid target chars
                            val bad = req.rejectRequest?.status() == HttpStatus.URI_TOO_LONG_414
                            if (!bad && buffer.size() < maxUrlLength) {
                                buffer.append(b)
                            } else if (!bad) {
                                buffer.reset()
                                buffer.append('/'.code.toByte()) // the request won't last long - give it a temp URL
                                request().rejectRequest = HttpException(HttpStatus.URI_TOO_LONG_414)
                            }
                        } else if (b == SP) {
                            req.url = buffer.consumeAscii()
                            state = ParseState.HTTP_VERSION
                        } else throw ParseException("state=$state b=$b", position)
                    }

                    ParseState.HTTP_VERSION -> {
                        if (b.isCR()) {
                            state = ParseState.REQUEST_LINE_ENDING
                        } else {
                            if (b.isVChar()) {
                                buffer.append(b)
                            } else throw ParseException("state=$state b=$b", position)
                        }
                    }

                    ParseState.REQUEST_LINE_ENDING -> {
                        if (b.isLF()) {
                            exchange.httpVersion = buffer.consumeHttpVersion()
                            state = ParseState.HEADER_START
                        } else throw ParseException("state=$state b=$b", position)
                    }

                    ParseState.RESPONSE_START -> {
                        if (b == SP) {
                            exchange.httpVersion = buffer.consumeHttpVersion()
                            state = ParseState.STATUS_CODE
                        } else {
                            if (b.isVChar()) {
                                buffer.append(b)
                            } else throw ParseException("state=$state b=$b", position)
                        }
                    }

                    ParseState.STATUS_CODE -> {
                        if (b.isDigit()) {
                            buffer.append(b)
                            if (buffer.size() > 3) throw ParseException("status code too long", position)
                        } else if (b == SP) {
                            val code = buffer.consumeAscii().toInt()
                            response().statusCode = code
                            if (code >= 200 || code == 101) {
                                response().request =
                                    requestQueue.poll() ?: throw ParseException("Got a response without a request", position)
                            }
                            state = ParseState.REASON_PHRASE
                        } else throw ParseException("state=$state b=$b", position)
                    }

                    ParseState.REASON_PHRASE -> {
                        if (b.isVChar() || b.isOWS()) {
                            buffer.append(b)
                        } else if (b == CR) {
                            state = ParseState.STATUS_LINE_ENDING
                        } else throw ParseException("state=$state b=$b", position)
                    }

                    ParseState.STATUS_LINE_ENDING -> {
                        if (b.isLF()) {
                            response().reason = buffer.consumeAscii()
                            state = ParseState.HEADER_START
                        } else throw ParseException("state=$state b=$b", position)
                    }

                    ParseState.HEADER_START -> {
                        curHeadersLen = 1
                        if (b.isTChar()) {
                            buffer.append(b.toLower())
                            state = ParseState.HEADER_NAME
                        } else if (b.isCR()) {
                            state = ParseState.HEADERS_ENDING
                        } else throw ParseException("state=$state b=$b", position)
                    }

                    ParseState.HEADER_NAME -> {
                        val isOkay = onHeaderChar()
                        if (b.isTChar()) {
                            if (isOkay) {
                                buffer.append(b.toLower())
                            }
                        } else if (b == COLON) {
                            headerName = buffer.consumeAscii()
                            if (headerName!!.isEmpty() && isOkay) throw ParseException("Empty header name", position)
                            state = ParseState.HEADER_NAME_ENDED
                        }
                    }

                    ParseState.HEADER_NAME_ENDED -> {
                        val isOkay = onHeaderChar()
                        if (b.isOWS()) {
                            // skip it
                        } else if (b.isVChar()) {
                            if (isOkay) {
                                buffer.append(b)
                            }
                            state = ParseState.HEADER_VALUE
                        } else if (b.isCR()) {
                            // an empty value - ignore it
                            state = ParseState.HEADER_VALUE_ENDING
                        } else {
                            throw ParseException("Invalid header value $b", position)
                        }
                    }

                    ParseState.HEADER_VALUE -> {
                        val isOkay = onHeaderChar()
                        if (b.isVChar() || b.isOWS()) {
                            if (isOkay) {
                                buffer.append(b)
                            }
                        } else if (b.isCR()) {
                            state = ParseState.HEADER_VALUE_ENDING
                        }
                    }

                    ParseState.HEADER_VALUE_ENDING -> {
                        val isOkay = onHeaderChar()
                        if (b.isLF()) {
                            if (isOkay) {
                                val value = buffer.consumeAscii().trimEnd()
                                if (value.isNotEmpty()) {
                                    exchange.headers().add(headerName!!, value)
                                }
                            }
                            state = ParseState.HEADER_START
                        } else throw ParseException("No LF after CR at $state", position)
                    }

                    ParseState.HEADERS_ENDING -> {
                        if (b.isLF()) {
                            val exc = exchange
                            val body = exc.bodyTransferSize()
                            exc.bodySize = body
                            when (body.type()) {
                                BodyType.FIXED_SIZE -> {
                                    val len = body.size()!!
                                    state = ParseState.FIXED_SIZE_BODY
                                    remainingBytesToProxy = len
                                }

                                BodyType.CHUNKED -> {
                                    state = ParseState.CHUNK_START
                                }

                                BodyType.UNSPECIFIED -> {
                                    state = ParseState.UNSPECIFIED_BODY
                                    remainingBytesToProxy = Long.MAX_VALUE
                                }

                                BodyType.NONE -> {
                                    onMessageEnded()
                                }
                            }

                            position++
                            return exc
                        } else throw ParseException("state=$state b=$b", position)
                    }

                    ParseState.FIXED_SIZE_BODY, ParseState.UNSPECIFIED_BODY -> {
                        return sendContent()
                    }

                    ParseState.CHUNK_START -> {
                        if (b.isHexDigit()) {
                            state = ParseState.CHUNK_SIZE
                            buffer.append(b)
                        } else throw ParseException("state=$state b=$b", position)
                    }

                    ParseState.CHUNK_SIZE -> {
                        if (b.isHexDigit()) {
                            buffer.append(b)
                        } else {
                            state = if (b == SEMICOLON) {
                                ParseState.CHUNK_EXTENSIONS
                            } else if (b.isCR()) {
                                ParseState.CHUNK_HEADER_ENDING
                            } else throw ParseException("state=$state b=$b", position)
                            remainingBytesToProxy = buffer.consumeAscii().toLong(16)
                        }
                    }

                    ParseState.CHUNK_EXTENSIONS -> {
                        if (b.isVChar() || b.isOWS()) {
                            // todo: only allow valid extension characters
                        } else if (b.isCR()) {
                            state = ParseState.CHUNK_HEADER_ENDING
                        } else throw ParseException("state=$state b=$b", position)
                    }

                    ParseState.CHUNK_HEADER_ENDING -> {
                        if (b.isLF()) {
                            // remainingBytesToProxy has the chunk size in it
                            state = if (remainingBytesToProxy == 0L) ParseState.LAST_CHUNK else ParseState.CHUNK_DATA
                        } else throw ParseException("state=$state b=$b", position)
                    }

                    ParseState.CHUNK_DATA -> {
                        return sendContent()
                    }

                    ParseState.CHUNK_DATA_READ -> {
                        if (b.isCR()) {
                            state = ParseState.CHUNK_DATA_ENDING
                        } else throw ParseException("state=$state b=$b", position)
                    }

                    ParseState.CHUNK_DATA_ENDING -> {
                        if (b.isLF()) {
                            state = ParseState.CHUNK_START
                        } else throw ParseException("state=$state b=$b", position)
                    }

                    ParseState.LAST_CHUNK -> {
                        if (b.isCR()) {
                            state = ParseState.CHUNKED_BODY_ENDING
                        } else if (b.isTChar()) {
                            buffer.append(b)
                            state = ParseState.TRAILERS
                        } else throw ParseException("state=$state b=$b", position)
                    }

                    ParseState.CHUNKED_BODY_ENDING -> {
                        if (b.isLF()) {
                            position++
                            onMessageEnded()
                            return EndOfBodyBit
                        } else throw ParseException("state=$state b=$b", position)
                    }

                    ParseState.TRAILERS -> {
                        if (b.isOWS() || b.isVChar() || b.isCR()) {
                            buffer.append(b)
                        } else if (b.isLF()) {
                            buffer.append(b)
                            val trailerPart = buffer.toString(StandardCharsets.US_ASCII)
                            if (trailerPart.endsWith("\r\n\r\n")) {
                                buffer.reset()
                                onMessageEnded()
                                // TODO: pass back the trailers
                                return EndOfBodyBit
                            }
                        } else throw ParseException("state=$state b=$b", position)
                    }

                    ParseState.WEBSOCKET -> {
                        throw NotImplementedError("No websockets yet")
                    }
                }
                position++
            }
        }
    }

    private fun onHeaderChar(): Boolean {
        curHeadersLen++
        if (curHeadersLen <= maxHeadersLength) return true
        val req = exchange
        if (req is HttpRequestTemp && req.rejectRequest == null) {
            req.rejectRequest = HttpException(HttpStatus.REQUEST_HEADER_FIELDS_TOO_LARGE_431)
        }
        return false
    }

    private fun request() = (exchange as HttpRequestTemp)
    private fun response() = (exchange as HttpResponseTemp)

    private fun sendContent(): MessageBodyBit {
        val remainingInBuffer = remaining()
        val numberToTransfer = minOf(remainingBytesToProxy, remainingInBuffer.toLong()).toInt()
        remainingBytesToProxy -= numberToTransfer
        val start = position
        position += numberToTransfer

        val isLast = if (state == ParseState.CHUNK_DATA) {
            if (remainingBytesToProxy == 0L) {
                state = ParseState.CHUNK_DATA_READ
            }
            false
        } else {
            remainingBytesToProxy == 0L
        }
        if (isLast) {
            onMessageEnded()
        }
        return MessageBodyBit(readBuffer, start, numberToTransfer, isLast)
    }

    private fun onMessageEnded() {
        val exc = exchange
        this.state = if (exc is HttpRequestTemp) {
            if (exc.isWebsocketUpgrade()) {
                ParseState.WEBSOCKET
            } else {
                this.exchange = HttpRequestTemp.empty()
                ParseState.REQUEST_START
            }
        } else {
            if (exc.headers().containsValue("upgrade", "websocket", false)) {
                ParseState.WEBSOCKET
            } else {
                this.exchange = HttpResponseTemp.empty()
                ParseState.RESPONSE_START
            }
        }
    }

    private enum class ParseState {
        REQUEST_START,
        RESPONSE_START,
        METHOD,
        REQUEST_TARGET,
        HTTP_VERSION,
        REQUEST_LINE_ENDING,
        STATUS_CODE,
        REASON_PHRASE,
        STATUS_LINE_ENDING,
        HEADER_START,
        HEADER_NAME,
        HEADER_NAME_ENDED,
        HEADER_VALUE,
        HEADER_VALUE_ENDING,
        HEADERS_ENDING,
        FIXED_SIZE_BODY,
        UNSPECIFIED_BODY,
        CHUNK_START,
        CHUNK_SIZE,
        CHUNK_EXTENSIONS,
        CHUNK_HEADER_ENDING,
        CHUNK_DATA,
        LAST_CHUNK,
        CHUNKED_BODY_ENDING,
        TRAILERS,
        WEBSOCKET,
        CHUNK_DATA_READ,
        CHUNK_DATA_ENDING,
    }


    override fun toString(): String {
        return javaClass.simpleName + " ${this.state}"
    }

    private fun ByteArrayOutputStream.append(b: Byte) {
        this.write(b.toInt())
        if (this.size() > maxBufferSize) throw IllegalStateException("Buffer is ${this.size()} bytes")
    }

    companion object {
        private fun Byte.isVChar() = this >= 0x21.toByte() && this <= 0x7E.toByte()
        internal fun Byte.isTChar(): Boolean {
            // tchar = '!' / '#' / '$' / '%' / '&' / ''' / '*' / '+' / '-' / '.' /
            //    '^' / '_' / '`' / '|' / '~' / DIGIT / ALPHA
            return this == 33.toByte()
                    || (this in 35.toByte()..39.toByte())
                    || this == 42.toByte() || this == 43.toByte() || this == 45.toByte() || this == 46.toByte()
                    || (this in ZERO..NINE) // 0-9
                    || (this in A..Z) // A-Z
                    || (this in 94.toByte()..122.toByte()) // ^, ), `, a-z
                    || this == 124.toByte() || this == 126.toByte()
        }

        private fun Byte.isUpperCase() = this in A..Z
        private fun Byte.isCR() = this == CR
        private fun Byte.isLF() = this == LF
        private fun Byte.isOWS() = this == SP || this == HTAB
        private fun Byte.toLower(): Byte = if (this < A || this > Z) this else (this + 32).toByte()
        private fun Byte.isDigit() = this in ZERO..NINE
        private fun Byte.isHexDigit() = this in A..F || this in ZERO..NINE || this in A_LOWER..F_LOWER


        private fun ByteArrayOutputStream.consumeAscii(): String {
            val v = this.toString(StandardCharsets.US_ASCII)
            this.reset()
            return v
        }

        private fun ByteArrayOutputStream.consumeHttpVersion(): HttpVersion {
            return HttpVersion.fromVersion(this.consumeAscii())
                ?: throw HttpException(HttpStatus.HTTP_VERSION_NOT_SUPPORTED_505)
        }
    }
}

internal enum class HttpMessageType { REQUEST, RESPONSE }

internal interface Http1ConnectionMsg
class MessageBodyBit(val bytes: ByteArray, val offset: Int, val length: Int, val isLast: Boolean) : Http1ConnectionMsg
object EndOfBodyBit : Http1ConnectionMsg
object EOFMsg : Http1ConnectionMsg
