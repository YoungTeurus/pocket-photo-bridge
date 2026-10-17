package dev.pocketphotobridge.bridge.linux

/** SDP is delegated to the independently installed BlueZ utility; no libbluetooth is linked. */
internal class LinuxSppDiscovery(private val runner: LinuxCommandRunner = BoundedLinuxCommandRunner(), private val report: (String) -> Unit = {}) {
    fun channel(address: String): Int {
        val validated = bluetoothAddress(address)
        reportRadio(report, "LINUX_SPP_DISCOVERY_START")
        val result = runner.run(listOf("/usr/bin/sdptool", "search", "--bdaddr", validated, "SP"), 15_000)
        if (result.exitCode != 0) radioFail(LinuxRadioFailureCode.SPP_UNAVAILABLE)
        return parse(result.output).also { reportRadio(report, "LINUX_SPP_DISCOVERED") }
    }

    companion object {
        fun parse(output: String): Int {
            if (output.toByteArray(Charsets.UTF_8).size > 65_536) radioFail(LinuxRadioFailureCode.SPP_UNAVAILABLE)
            // A record is delimited by its handle, never by its untrusted service name or blank lines.
            val lines = output.lines()
            val handles = lines.indices.filter { Regex("Service RecHandle: 0x[0-9a-fA-F]+").matches(lines[it].trim()) }
            val channels = mutableListOf<Int>()
            for ((position, begin) in handles.withIndex()) {
                val end = handles.getOrNull(position + 1) ?: lines.size
                val record = lines.subList(begin + 1, end)
                val classes = record.indexOfFirst { it.trim() == "Service Class ID List:" }
                val protocol = record.indexOfFirst { it.trim() == "Protocol Descriptor List:" }
                if (classes < 0 || protocol <= classes) continue
                val ids = record.subList(classes + 1, protocol)
                if (ids.none { Regex("\\s*\"Serial Port\" \\(0x(?:1101|00001101(?:-0000-1000-8000-00805f9b34fb)?)\\)\\s*", RegexOption.IGNORE_CASE).matches(it) }) continue
                val descriptorEnd = (protocol + 1 until record.size).firstOrNull { record[it].isNotEmpty() && !record[it].first().isWhitespace() } ?: record.size
                val descriptors = record.subList(protocol + 1, descriptorEnd)
                val rfcomm = descriptors.indices.filter { Regex("\\s*\"RFCOMM\" \\(0x(?:0003|00000003(?:-0000-1000-8000-00805f9b34fb)?)\\)\\s*", RegexOption.IGNORE_CASE).matches(descriptors[it]) }
                if (rfcomm.size != 1) radioFail(LinuxRadioFailureCode.SPP_UNAVAILABLE)
                val rfcommStart = rfcomm.single()
                val rfcommEnd = (rfcommStart + 1 until descriptors.size).firstOrNull { descriptors[it].trimStart().startsWith('"') } ?: descriptors.size
                val values = descriptors.subList(rfcommStart + 1, rfcommEnd).mapNotNull {
                    Regex("\\s*Channel: ([0-9]{1,3})\\s*").matchEntire(it)?.groupValues?.get(1)?.toIntOrNull()
                }
                if (values.size != 1 || values.single() !in 1..30) radioFail(LinuxRadioFailureCode.SPP_UNAVAILABLE)
                channels += values.single()
            }
            return channels.singleOrNull() ?: radioFail(LinuxRadioFailureCode.SPP_UNAVAILABLE)
        }
    }
}
