/*
 * Copyright 2017-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.serialization.cbor

import kotlinx.serialization.*
import kotlinx.serialization.encoding.CompositeDecoder.Companion.DECODE_DONE
import kotlinx.serialization.cbor.internal.ByteArrayInput
import kotlinx.serialization.cbor.internal.ByteArrayOutput
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.*
import kotlinx.serialization.modules.*
import kotlin.experimental.*

/**
 * Implements [encoding][encodeToByteArray] and [decoding][decodeFromByteArray] classes to/from bytes
 * using [CBOR](https://tools.ietf.org/html/rfc7049) specification.
 * It is typically used by constructing an application-specific instance, with configured behaviour, and,
 * if necessary, registered custom serializers (in [SerializersModule] provided by [serializersModule] constructor parameter).
 *
 * ### Known caveats and limitations:
 * Supports reading collections of both definite and indefinite lengths; however,
 * serialization always writes maps and lists as [indefinite-length](https://tools.ietf.org/html/rfc7049#section-2.2.1) ones.
 * Does not support [optional tags](https://tools.ietf.org/html/rfc7049#section-2.4) representing datetime, bignums, etc.
 * Fully support CBOR maps, which, unlike JSON ones, may contain keys of non-primitive types, and may produce such maps
 * from corresponding Kotlin objects. However, other 3rd-party parsers (e.g. `jackson-dataformat-cbor`) may not accept such maps.
 *
 * @param encodeDefaults specifies whether default values of Kotlin properties are encoded.
 */
public class Cbor(
    public val encodeDefaults: Boolean = true,
    override val serializersModule: SerializersModule = EmptySerializersModule
) : BinaryFormat {
    // Differs from List only in start byte
    private inner class CborMapWriter(encoder: CborEncoder) : CborListWriter(encoder) {
        override fun writeBeginToken() = encoder.startMap()
    }

    // Writes all elements consequently, except size - CBOR supports maps and arrays of indefinite length
    private open inner class CborListWriter(encoder: CborEncoder) : CborWriter(encoder) {
        override fun writeBeginToken() = encoder.startArray()

        override fun encodeElement(descriptor: SerialDescriptor, index: Int): Boolean = true
    }

    // Writes class as map [fieldName, fieldValue]
    private open inner class CborWriter(val encoder: CborEncoder) : AbstractEncoder() {
        override val serializersModule: SerializersModule
            get() = this@Cbor.serializersModule

        override fun shouldEncodeElementDefault(descriptor: SerialDescriptor, index: Int): Boolean = encodeDefaults

        protected open fun writeBeginToken() = encoder.startMap()

        //todo: Write size of map or array if known
        override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder {
            val writer = when (descriptor.kind) {
                StructureKind.LIST, is PolymorphicKind -> CborListWriter(encoder)
                StructureKind.MAP -> CborMapWriter(encoder)
                else -> CborWriter(encoder)
            }
            writer.writeBeginToken()
            return writer
        }

        override fun endStructure(descriptor: SerialDescriptor) = encoder.end()

        override fun encodeElement(descriptor: SerialDescriptor, index: Int): Boolean {
            val name = descriptor.getElementName(index)
            encoder.encodeString(name)
            return true
        }

        override fun encodeString(value: String) = encoder.encodeString(value)

        override fun encodeFloat(value: Float) = encoder.encodeFloat(value)
        override fun encodeDouble(value: Double) = encoder.encodeDouble(value)

        override fun encodeChar(value: Char) = encoder.encodeNumber(value.toLong())
        override fun encodeByte(value: Byte) = encoder.encodeNumber(value.toLong())
        override fun encodeShort(value: Short) = encoder.encodeNumber(value.toLong())
        override fun encodeInt(value: Int) = encoder.encodeNumber(value.toLong())
        override fun encodeLong(value: Long) = encoder.encodeNumber(value)

        override fun encodeBoolean(value: Boolean) = encoder.encodeBoolean(value)

        override fun encodeNull() = encoder.encodeNull()

        override fun encodeEnum(
            enumDescriptor: SerialDescriptor,
            index: Int
        ) =
            encoder.encodeString(enumDescriptor.getElementName(index))
    }

    // For details of representation, see https://tools.ietf.org/html/rfc7049#section-2.1
    internal class CborEncoder(private val output: ByteArrayOutput) {

        fun startArray() = output.write(BEGIN_ARRAY)
        fun startMap() = output.write(BEGIN_MAP)
        fun end() = output.write(BREAK)

        fun encodeNull() = output.write(NULL)

        fun encodeBoolean(value: Boolean) = output.write(if (value) TRUE else FALSE)

        fun encodeNumber(value: Long) = output.write(composeNumber(value))

        fun encodeString(value: String) {
            val data = value.encodeToByteArray()
            val header = composeNumber(data.size.toLong())
            header[0] = header[0] or HEADER_STRING
            output.write(header)
            output.write(data)
        }

        fun encodeFloat(value: Float) {
            output.write(NEXT_FLOAT)
            val bits = value.toRawBits()
            for (i in 0..3) {
                output.write((bits shr (24 - 8 * i)) and 0xFF)
            }
        }

        fun encodeDouble(value: Double) {
            output.write(NEXT_DOUBLE)
            val bits = value.toRawBits()
            for (i in 0..7) {
                output.write(((bits shr (56 - 8 * i)) and 0xFF).toInt())
            }
        }

        private fun composeNumber(value: Long): ByteArray =
            if (value >= 0) composePositive(value) else composeNegative(value)

        private fun composePositive(value: Long): ByteArray = when (value) {
            in 0..23 -> byteArrayOf(value.toByte())
            in 24..Byte.MAX_VALUE -> byteArrayOf(24, value.toByte())
            in Byte.MAX_VALUE + 1..Short.MAX_VALUE -> encodeToByteArray(value, 2, 25)
            in Short.MAX_VALUE + 1..Int.MAX_VALUE -> encodeToByteArray(value, 4, 26)
            in (Int.MAX_VALUE.toLong() + 1..Long.MAX_VALUE) -> encodeToByteArray(value, 8, 27)
            else -> throw AssertionError("$value should be positive")
        }

        private fun encodeToByteArray(value: Long, bytes: Int, tag: Byte): ByteArray {
            val result = ByteArray(bytes + 1)
            val limit = bytes * 8 - 8
            result[0] = tag
            for (i in 0 until bytes) {
                result[i + 1] = ((value shr (limit - 8 * i)) and 0xFF).toByte()
            }
            return result
        }

        private fun composeNegative(value: Long): ByteArray {
            val aVal = if (value == Long.MIN_VALUE) Long.MAX_VALUE else -1 - value
            val data = composePositive(aVal)
            data[0] = data[0] or HEADER_NEGATIVE
            return data
        }
    }

    private inner class CborMapReader(decoder: CborDecoder) : CborListReader(decoder) {
        override fun skipBeginToken() = setSize(decoder.startMap() * 2)
    }

    private open inner class CborListReader(decoder: CborDecoder) : CborReader(decoder) {
        private var ind = 0

        override fun skipBeginToken() = setSize(decoder.startArray())

        override fun decodeElementIndex(descriptor: SerialDescriptor) = if (!finiteMode && decoder.isEnd() || (finiteMode && ind >= size)) DECODE_DONE else ind++
    }

    private open inner class CborReader(val decoder: CborDecoder) : AbstractDecoder() {

        protected var size = -1
            private set
        protected var finiteMode = false
            private set
        private var readProperties: Int = 0

        protected fun setSize(size: Int) {
            if (size >= 0) {
                finiteMode = true
                this.size = size
            }
        }

        override val serializersModule: SerializersModule
            get() = this@Cbor.serializersModule

        protected open fun skipBeginToken() = setSize(decoder.startMap())

        override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder {
            val re = when (descriptor.kind) {
                StructureKind.LIST, is PolymorphicKind -> CborListReader(decoder)
                StructureKind.MAP -> CborMapReader(decoder)
                else -> CborReader(decoder)
            }
            re.skipBeginToken()
            return re
        }

        override fun endStructure(descriptor: SerialDescriptor) {
            if (!finiteMode) decoder.end()
        }

        override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
            if (!finiteMode && decoder.isEnd() || (finiteMode && readProperties >= size)) return DECODE_DONE
            val elemName = decoder.nextString()
            readProperties++
            return descriptor.getElementIndexOrThrow(elemName)
        }

        override fun decodeString() = decoder.nextString()

        override fun decodeNotNullMark(): Boolean = !decoder.isNull()

        override fun decodeDouble() = decoder.nextDouble()
        override fun decodeFloat() = decoder.nextFloat()

        override fun decodeBoolean() = decoder.nextBoolean()

        override fun decodeByte() = decoder.nextNumber().toByte()
        override fun decodeShort() = decoder.nextNumber().toShort()
        override fun decodeChar() = decoder.nextNumber().toChar()
        override fun decodeInt() = decoder.nextNumber().toInt()
        override fun decodeLong() = decoder.nextNumber()

        override fun decodeNull() = decoder.nextNull()

        override fun decodeEnum(enumDescriptor: SerialDescriptor): Int =
            enumDescriptor.getElementIndexOrThrow(decoder.nextString())

    }

    internal class CborDecoder(private val input: ByteArrayInput) {
        private var curByte: Int = -1

        init {
            readByte()
        }

        private fun readByte(): Int {
            curByte = input.read()
            return curByte
        }

        private fun skipByte(expected: Int) {
            if (curByte != expected) throw CborDecodingException("byte ${printByte(expected)}", curByte)
            readByte()
        }

        fun isNull() = curByte == NULL

        fun nextNull(): Nothing? {
            skipByte(NULL)
            return null
        }

        fun nextBoolean(): Boolean {
            val ans = when (curByte) {
                TRUE -> true
                FALSE -> false
                else -> throw CborDecodingException("boolean value", curByte)
            }
            readByte()
            return ans
        }

        fun startArray() = startSized(BEGIN_ARRAY, HEADER_ARRAY, "array")

        fun startMap() = startSized(BEGIN_MAP, HEADER_MAP, "map")

        private fun startSized(unboundedHeader: Int, boundedHeaderMask: Int, collectionType: String): Int {
            if (curByte == unboundedHeader) {
                skipByte(unboundedHeader)
                return -1
            }
            if ((curByte and 0b111_00000) != boundedHeaderMask)
                throw CborDecodingException("start of $collectionType", curByte)
            val size = readNumber().toInt()
            readByte()
            return size
        }

        fun isEnd() = curByte == BREAK

        fun end() = skipByte(BREAK)

        fun nextString(): String {
            if ((curByte and 0b111_00000) != HEADER_STRING.toInt())
                throw CborDecodingException("start of string", curByte)
            val strLen = readNumber().toInt()
            val arr = input.readExactNBytes(strLen)
            val ans = arr.decodeToString()
            readByte()
            return ans
        }

        fun nextNumber(): Long {
            val res = readNumber()
            readByte()
            return res
        }

        private fun readNumber(): Long {
            val value = curByte and 0b000_11111
            val negative = (curByte and 0b111_00000) == HEADER_NEGATIVE.toInt()
            val bytesToRead = when (value) {
                24 -> 1
                25 -> 2
                26 -> 4
                27 -> 8
                else -> 0
            }
            if (bytesToRead == 0) {
                return if (negative) -(value + 1).toLong()
                else value.toLong()
            }
            val res = input.readExact(bytesToRead)
            return if (negative) -(res + 1)
            else res
        }

        private fun ByteArrayInput.readExact(bytes: Int): Long {
            val arr = readExactNBytes(bytes)
            var result = 0L
            for (i in 0 until bytes) {
                result = (result shl 8) or (arr[i].toInt() and 0xFF).toLong()
            }
            return result
        }

        private fun ByteArrayInput.readExactNBytes(bytesCount: Int): ByteArray {
            if (bytesCount > availableBytes) {
                error("Unexpected EOF, available $availableBytes bytes, requested: $bytesCount")
            }
            val array = ByteArray(bytesCount)
            read(array, 0, bytesCount)
            return array
        }

        fun nextFloat(): Float {
            if (curByte != NEXT_FLOAT) throw CborDecodingException("float header", curByte)
            val res = Float.fromBits(readInt())
            readByte()
            return res
        }

        fun nextDouble(): Double {
            if (curByte != NEXT_DOUBLE) throw CborDecodingException("double header", curByte)
            val res = Double.fromBits(readLong())
            readByte()
            return res
        }

        private fun readLong(): Long {
            var result = 0L
            for (i in 0..7) {
                val byte = input.read()
                result = (result shl 8) or byte.toLong()
            }
            return result
        }

        private fun readInt(): Int {
            var result = 0
            for (i in 0..3) {
                val byte = input.read()
                result = (result shl 8) or byte
            }
            return result
        }
    }

    public companion object Default : BinaryFormat by Cbor() {
        private const val FALSE = 0xf4
        private const val TRUE = 0xf5
        private const val NULL = 0xf6

        private const val NEXT_FLOAT = 0xfa
        private const val NEXT_DOUBLE = 0xfb

        private const val BEGIN_ARRAY = 0x9f
        private const val BEGIN_MAP = 0xbf
        private const val BREAK = 0xff

        private const val HEADER_STRING: Byte = 0b011_00000
        private const val HEADER_NEGATIVE: Byte = 0b001_00000
        private const val HEADER_ARRAY: Int = 0b100_00000
        private const val HEADER_MAP: Int = 0b101_00000
    }

    override fun <T> encodeToByteArray(serializer: SerializationStrategy<T>, value: T): ByteArray {
        val output = ByteArrayOutput()
        val dumper = CborWriter(CborEncoder(output))
        dumper.encodeSerializableValue(serializer, value)
        return output.toByteArray()
    }

    override fun <T> decodeFromByteArray(deserializer: DeserializationStrategy<T>, bytes: ByteArray): T {
        val stream = ByteArrayInput(bytes)
        val reader = CborReader(CborDecoder(stream))
        return reader.decodeSerializableValue(deserializer)
    }

    internal fun SerialDescriptor.getElementIndexOrThrow(name: String): Int {
        val index = getElementIndex(name)
        if (index == CompositeDecoder.UNKNOWN_NAME)
            throw SerializationException("$serialName does not contain element with name '$name'")
        return index
    }
}
