package dev.pocketphotobridge.bridge

import java.nio.file.Path

enum class CliMode { HELP, SIMULATION, SUITE, ACKNOWLEDGE, HARDWARE, WINDOWS_LIVE, WINDOWS_NATIVE,
    SERVICE, SERVICE_SIMULATION, BOOTSTRAP, LINUX_NATIVE, LINUX_STATUS, LINUX_RECEIVE }

enum class SimulationScenario(val option: String) {
    NORMAL("normal"), MISSING_SSID("missing-ssid"), CAMERA_OFFLINE("camera-offline"),
    CAMERA_TIMEOUT("camera-timeout"), NO_SELECTED_JPEG("no-selected-jpeg"),
    INTERRUPTED_TRANSFER("interrupted-transfer"), PRINTER_OFFLINE("printer-offline"),
    PRINTER_BUSY("printer-busy"), DROPPED_AFTER_SUBMIT("dropped-after-submit"),
    UNCERTAIN_COMPLETION("uncertain-completion"), DUPLICATE_TRIGGER("duplicate-trigger"),
    DUPLICATE_PHOTO("duplicate-photo"), RESTART("restart"), MANUAL_RECOVERY("manual-recovery"),
    POWER_CUT("power-cut");

    companion object {
        fun fromOption(value: String): SimulationScenario = entries.singleOrNull { it.option == value }
            ?: throw BridgeFailure(BridgeFailureCode.ARGUMENT_ERROR)
    }
}

data class CliOptions(val mode: CliMode, val scenario: SimulationScenario = SimulationScenario.NORMAL,
    val stateDir: Path? = null, val crop: Boolean = false, val interactive: Boolean = false,
    val hardwareConfig: Path? = null) {

    companion object {
        fun parse(arguments: Array<String>): CliOptions {
            if (arguments.size > 32 || arguments.any { it.length > 4096 }) badArguments()
            var mode: CliMode? = null
            var scenario = SimulationScenario.NORMAL
            var state: Path? = null
            var config: Path? = null
            var crop = false
            var confirmed = false
            var index = 0
            fun setMode(value: CliMode) { if (mode != null) badArguments(); mode = value }
            fun path(value: String): Path {
                if (value.isEmpty() || value.startsWith("--")) badArguments()
                return try { Path.of(value) } catch (_: Exception) { badArguments() }
            }
            fun followingPath(): Path {
                if (++index >= arguments.size) badArguments()
                return path(arguments[index])
            }
            while (index < arguments.size) {
                val argument = arguments[index]
                when {
                    argument == "--help" -> setMode(CliMode.HELP)
                    argument == "--simulate" -> setMode(CliMode.SIMULATION)
                    argument == "--simulate-all" -> setMode(CliMode.SUITE)
                    argument.startsWith("--simulate-failure=") -> {
                        setMode(CliMode.SIMULATION)
                        scenario = SimulationScenario.fromOption(argument.substringAfter('='))
                    }
                    argument == "--hardware" -> setMode(CliMode.HARDWARE)
                    argument == "--service" -> setMode(CliMode.SERVICE)
                    argument == "--simulate-service" -> setMode(CliMode.SERVICE_SIMULATION)
                    argument == "--make-bootstrap" -> setMode(CliMode.BOOTSTRAP)
                    argument == "--linux-native-check" -> setMode(CliMode.LINUX_NATIVE)
                    argument == "--linux-status-only" -> setMode(CliMode.LINUX_STATUS)
                    argument == "--linux-receive-only" -> setMode(CliMode.LINUX_RECEIVE)
                    argument == "--windows-live" -> setMode(CliMode.WINDOWS_LIVE)
                    argument == "--windows-native-check" -> setMode(CliMode.WINDOWS_NATIVE)
                    argument == "--acknowledge-checked-printer" -> setMode(CliMode.ACKNOWLEDGE)
                    argument == "--confirm" -> { if (confirmed) badArguments(); confirmed = true }
                    argument == "--crop" -> { if (crop) badArguments(); crop = true }
                    argument == "--state-dir" -> { if (state != null) badArguments(); state = followingPath() }
                    argument.startsWith("--state-dir=") -> {
                        if (state != null) badArguments(); state = path(argument.substringAfter('='))
                    }
                    argument == "--config" -> { if (config != null) badArguments(); config = followingPath() }
                    argument.startsWith("--config=") -> {
                        if (config != null) badArguments(); config = path(argument.substringAfter('='))
                    }
                    else -> badArguments()
                }
                index++
            }
            val selected = mode ?: CliMode.SUITE
            if ((selected == CliMode.ACKNOWLEDGE) != confirmed || config != null && selected != CliMode.HARDWARE) badArguments()
            return CliOptions(selected, scenario, state, crop, arguments.isEmpty(), config)
        }

        private fun badArguments(): Nothing = throw BridgeFailure(BridgeFailureCode.ARGUMENT_ERROR)
    }
}
