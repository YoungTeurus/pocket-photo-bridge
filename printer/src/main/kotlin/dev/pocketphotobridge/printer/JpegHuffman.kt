package dev.pocketphotobridge.printer

import java.io.ByteArrayOutputStream

/**
 * Losslessly replaces optimized baseline Huffman coding with the JPEG Annex K tables.
 * Quantizers, sampling, DC differences and AC amplitudes are preserved. Only single-scan,
 * 8-bit baseline grayscale/three-component JPEGs without restart intervals are supported.
 */
object JpegHuffman {
    private const val MAX_BYTES = 4 * 1024 * 1024
    private const val MAX_PIXELS = 16_777_216L

    fun standardize(jpeg: ByteArray): ByteArray {
        checkActive()
        if (jpeg.size > MAX_BYTES) throw PrinterFailure(PrinterFailureCode.LIMIT_EXCEEDED)
        if (jpeg.size < 16 || unsigned(jpeg[0]) != 255 || unsigned(jpeg[1]) != 0xd8) invalid()
        val output = BoundedOutput()
        output.write(0xff); output.write(0xd8)
        val tables = mutableMapOf<Int, Huffman>()
        val quantizers = mutableSetOf<Int>()
        var frame: Frame? = null
        var position = 2
        while (position + 4 <= jpeg.size) {
            checkActive()
            if (unsigned(jpeg[position]) != 255) invalid()
            val marker = unsigned(jpeg[position + 1])
            val length = word(jpeg, position + 2)
            val end = position + 2 + length
            if (length < 2 || end > jpeg.size) invalid()
            val payload = jpeg.copyOfRange(position + 4, end)
            when (marker) {
                0xc0 -> { if (frame != null) invalid(); frame = parseFrame(payload) }
                0xc4 -> readTables(payload, tables)
                0xdb -> readQuantizers(payload, quantizers)
                0xdd -> if (payload.size != 2 || word(payload, 0) != 0) invalid()
                0xda -> {
                    val current = frame ?: invalid()
                    if (current.components.any { it.quantizer !in quantizers }) invalid()
                    val scan = parseScan(payload, current, tables)
                    // Match the reference encoder's separate DC/AC DHT segments as well as its codes.
                    standardTableData.filterIndexed { index, _ -> current.components.size == 3 || index < 2 }
                        .forEach { output.segment(0xc4, it) }
                    val rewritten = payload.copyOf()
                    scan.forEachIndexed { index, component ->
                        rewritten[2 + index * 2] = if (component.outputTable == 0) 0 else 0x11
                    }
                    output.segment(0xda, rewritten)
                    transcode(jpeg, end, current, scan, output)
                    output.write(0xff); output.write(0xd9)
                    return output.bytes()
                }
                in 0xe0..0xef, 0xfe -> Unit
                else -> invalid()
            }
            if (marker != 0xc4) output.write(jpeg.copyOfRange(position, end))
            position = end
        }
        invalid()
    }

    private data class Component(val id: Int, val horizontal: Int, val vertical: Int, val quantizer: Int)
    private data class Frame(val width: Int, val height: Int, val components: List<Component>)
    private data class ScanComponent(val component: Component, val dc: Huffman, val ac: Huffman, val outputTable: Int)

    private fun parseFrame(data: ByteArray): Frame {
        if (data.size < 6 || unsigned(data[0]) != 8) invalid()
        val height = word(data, 1); val width = word(data, 3)
        val count = unsigned(data[5])
        if (count !in listOf(1, 3) || data.size != 6 + count * 3 || width !in 1..8192 || height !in 1..8192 ||
            width.toLong() * height > MAX_PIXELS) invalid()
        val components = (0 until count).map { index ->
            val offset = 6 + index * 3
            val sampling = unsigned(data[offset + 1])
            Component(unsigned(data[offset]), sampling ushr 4, sampling and 15, unsigned(data[offset + 2]))
        }
        if (components.map { it.id }.distinct().size != count || components.any {
            it.horizontal !in 1..4 || it.vertical !in 1..4 || it.quantizer !in 0..3
        } || components.sumOf { it.horizontal * it.vertical } > 10) invalid()
        val maximumH = components.maxOf { it.horizontal }; val maximumV = components.maxOf { it.vertical }
        if (components.any { maximumH % it.horizontal != 0 || maximumV % it.vertical != 0 } ||
            count == 1 && (maximumH != 1 || maximumV != 1)) invalid()
        return Frame(width, height, components)
    }

    private fun readQuantizers(data: ByteArray, known: MutableSet<Int>) {
        var position = 0
        while (position < data.size) {
            val selector = unsigned(data[position++])
            // Baseline 8-bit quantization only; zero quantizers cannot define a JPEG.
            if (selector !in 0..3 || position + 64 > data.size ||
                (position until position + 64).any { data[it] == 0.toByte() }) invalid()
            known += selector
            position += 64
        }
        if (position == 0) invalid()
    }

    private fun readTables(data: ByteArray, tables: MutableMap<Int, Huffman>) {
        var position = 0
        while (position < data.size) {
            if (position + 17 > data.size) invalid()
            val selector = unsigned(data[position++])
            if (selector ushr 4 !in 0..1 || selector and 15 !in 0..3) invalid()
            val counts = IntArray(16) { unsigned(data[position + it]) }
            position += 16
            val size = counts.sum()
            if (size !in 1..256 || position + size > data.size) invalid()
            val values = IntArray(size) { unsigned(data[position + it]) }
            tables[selector] = Huffman(counts, values, selector ushr 4)
            position += size
        }
        if (position == 0) invalid()
    }

    private fun parseScan(data: ByteArray, frame: Frame, tables: Map<Int, Huffman>): List<ScanComponent> {
        val count = frame.components.size
        if (data.size != 4 + count * 2 || unsigned(data[0]) != count ||
            unsigned(data[data.size - 3]) != 0 || unsigned(data[data.size - 2]) != 63 || data.last() != 0.toByte()) invalid()
        val scan = (0 until count).map { index ->
            val component = frame.components.firstOrNull { it.id == unsigned(data[1 + index * 2]) } ?: invalid()
            val selector = unsigned(data[2 + index * 2])
            val dc = tables[selector ushr 4] ?: invalid()
            val ac = tables[0x10 or (selector and 15)] ?: invalid()
            if (selector ushr 4 !in 0..3 || selector and 15 !in 0..3) invalid()
            ScanComponent(component, dc, ac, if (component == frame.components.first()) 0 else 1)
        }
        if (scan.map { it.component.id }.distinct().size != count) invalid()
        return scan
    }

    private fun transcode(jpeg: ByteArray, start: Int, frame: Frame, scan: List<ScanComponent>, output: BoundedOutput) {
        val input = EntropyInput(jpeg, start)
        val writer = EntropyOutput(output)
        val mcuWidth = 8 * frame.components.maxOf { it.horizontal }
        val mcuHeight = 8 * frame.components.maxOf { it.vertical }
        val mcus = ((frame.width + mcuWidth - 1) / mcuWidth) * ((frame.height + mcuHeight - 1) / mcuHeight)
        repeat(mcus) {
            checkActive()
            for (component in scan) {
                val dc = standardTables.getValue(component.outputTable)
                val ac = standardTables.getValue(0x10 or component.outputTable)
                repeat(component.component.horizontal * component.component.vertical) {
                    val category = component.dc.decode(input)
                    dc.emit(category, writer)
                    writer.write(input.read(category), category)
                    var coefficient = 1
                    while (coefficient < 64) {
                        val symbol = component.ac.decode(input)
                        ac.emit(symbol, writer)
                        if (symbol == 0) break
                        if (symbol == 0xf0) {
                            coefficient += 16
                            if (coefficient > 64) invalid()
                        } else {
                            val size = symbol and 15
                            coefficient += symbol ushr 4
                            if (coefficient >= 64) invalid()
                            writer.write(input.read(size), size)
                            coefficient++
                        }
                    }
                }
            }
        }
        input.finish()
        writer.finish()
    }

    private class Huffman(counts: IntArray, values: IntArray, kind: Int) {
        private val minimum = IntArray(17)
        private val maximum = IntArray(17) { -1 }
        private val offsets = IntArray(17)
        private val symbols = values
        private val codes = IntArray(256) { -1 }
        private val lengths = IntArray(256)

        init {
            var code = 0
            var index = 0
            for (length in 1..16) {
                val count = counts[length - 1]
                if (code + count > 1 shl length) invalid()
                if (count > 0) {
                    minimum[length] = code; maximum[length] = code + count - 1; offsets[length] = index
                    repeat(count) {
                        val symbol = values[index++]
                        if (codes[symbol] >= 0 || code == (1 shl length) - 1 ||
                            kind == 0 && symbol !in 0..11 ||
                            kind == 1 && symbol != 0 && symbol != 0xf0 && symbol and 15 !in 1..10) invalid()
                        codes[symbol] = code++; lengths[symbol] = length
                    }
                }
                code = code shl 1
            }
        }

        fun decode(input: EntropyInput): Int {
            var code = 0
            for (length in 1..16) {
                code = (code shl 1) or input.read(1)
                if (maximum[length] >= 0 && code in minimum[length]..maximum[length])
                    return symbols[offsets[length] + code - minimum[length]]
            }
            invalid()
        }

        fun emit(symbol: Int, output: EntropyOutput) {
            if (codes[symbol] < 0) invalid()
            output.write(codes[symbol], lengths[symbol])
        }
    }

    private class EntropyInput(private val bytes: ByteArray, private var position: Int) {
        private var current = 0
        private var remaining = 0

        fun read(size: Int): Int {
            var result = 0
            repeat(size) {
                if (remaining == 0) {
                    if (position >= bytes.size) invalid()
                    current = unsigned(bytes[position++])
                    if (current == 255) {
                        if (position >= bytes.size || unsigned(bytes[position++]) != 0) invalid()
                    }
                    remaining = 8
                }
                remaining--
                result = (result shl 1) or ((current ushr remaining) and 1)
            }
            return result
        }

        fun finish() {
            val padding = (1 shl remaining) - 1
            if (current and padding != padding || position + 2 != bytes.size ||
                unsigned(bytes[position]) != 255 || unsigned(bytes[position + 1]) != 0xd9) invalid()
        }
    }

    private class EntropyOutput(private val output: BoundedOutput) {
        private var current = 0
        private var used = 0
        fun write(value: Int, size: Int) {
            for (shift in size - 1 downTo 0) {
                current = (current shl 1) or ((value ushr shift) and 1)
                if (++used == 8) {
                    output.write(current)
                    if (current == 255) output.write(0)
                    current = 0; used = 0
                }
            }
        }
        fun finish() { while (used != 0) write(1, 1) }
    }

    private class BoundedOutput {
        private val buffer = ByteArrayOutputStream()
        fun write(value: Int) { bound(1); buffer.write(value) }
        fun write(bytes: ByteArray) { bound(bytes.size); buffer.write(bytes) }
        fun segment(marker: Int, payload: ByteArray) {
            val size = payload.size + 2
            write(255); write(marker); write(size ushr 8); write(size and 255); write(payload)
        }
        private fun bound(extra: Int) {
            if (buffer.size() + extra > MAX_BYTES) throw PrinterFailure(PrinterFailureCode.LIMIT_EXCEEDED)
        }
        fun bytes(): ByteArray = buffer.toByteArray()
    }

    private fun word(bytes: ByteArray, offset: Int) = (unsigned(bytes[offset]) shl 8) or unsigned(bytes[offset + 1])
    private fun unsigned(value: Byte) = value.toInt() and 255
    private fun invalid(): Nothing = throw PrinterFailure(PrinterFailureCode.INVALID_IMAGE)
    private fun checkActive() {
        if (Thread.currentThread().isInterrupted) throw PrinterFailure(PrinterFailureCode.CANCELLED)
    }
    private fun hex(value: String) = ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    // ISO/IEC 10918-1 Annex K default DC/AC luminance and chrominance tables.
    private val standardTableData = listOf(
        hex("0000010501010101010100000000000000000102030405060708090a0b"),
        hex("100002010303020403050504040000017d01020300041105122131410613516107227114328191a1082342b1c11552d1f02433627282090a161718191a25262728292a3435363738393a434445464748494a535455565758595a636465666768696a737475767778797a838485868788898a92939495969798999aa2a3a4a5a6a7a8a9aab2b3b4b5b6b7b8b9bac2c3c4c5c6c7c8c9cad2d3d4d5d6d7d8d9dae1e2e3e4e5e6e7e8e9eaf1f2f3f4f5f6f7f8f9fa"),
        hex("0100030101010101010101010000000000000102030405060708090a0b"),
        hex("1100020102040403040705040400010277000102031104052131061241510761711322328108144291a1b1c109233352f0156272d10a162434e125f11718191a262728292a35363738393a434445464748494a535455565758595a636465666768696a737475767778797a82838485868788898a92939495969798999aa2a3a4a5a6a7a8a9aab2b3b4b5b6b7b8b9bac2c3c4c5c6c7c8c9cad2d3d4d5d6d7d8d9dae2e3e4e5e6e7e8e9eaf2f3f4f5f6f7f8f9fa")
    )
    private val standardTables = mutableMapOf<Int, Huffman>().apply {
        standardTableData.forEach { readTables(it, this) }
    }
}
