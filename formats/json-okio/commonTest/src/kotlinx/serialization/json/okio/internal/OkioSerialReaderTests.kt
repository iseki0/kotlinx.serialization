/*
 * Copyright 2017-2024 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.serialization.json.okio.internal

import okio.*
import kotlin.test.*

class OkioSerialReaderTests {

    @Test
    fun testSurrogate() {
        val text = "\uD83D\uDE03"
        val originalChars = text.toCharArray()

        val buffer = Buffer()
        buffer.writeUtf8(text)
        val reader = OkioSerialReader(buffer)

        val readArray = CharArray(2)
        assertEquals(1, reader.read(readArray, 0, 1) )
        assertEquals(1, reader.read(readArray, 1, 1) )

        assertContentEquals(originalChars, readArray)
    }

}