package dev.pocketphotobridge.bridge.windows

import dev.pocketphotobridge.bridge.*
import java.io.Console
import java.io.PrintStream
import java.nio.file.Path

object WindowsLive {
    private fun supported() = System.getProperty("os.name").startsWith("Windows") && System.getProperty("os.arch") in setOf("amd64","x86_64")
    fun run(crop: Boolean, state: Path?, output: PrintStream = System.out): Int {
        if (!supported()) { output.println("WINDOWS_REQUIRED"); return 64 }
        val console=System.console() ?: run { output.println("WINDOWS_LIVE_CONSOLE_REQUIRED"); return 64 }
        val ui=ConsoleUi(console,output)
        ui.line("Real Windows devices; physical validation pending. Pair the printer and join camera Wi-Fi in Windows Settings.")
        ui.line("1 Receive selected Sony JPEG (no print)\n2 Query printer status (no print)\n3 Receive selected JPEG and confirm one print\n4 Read-only native diagnostics\n5 Acknowledge after checking actual printer\nAnything else: exit")
        val action=when(ui.ask("Choose one operation:")) {
            "1" -> LiveAction.RECEIVE; "2" -> LiveAction.STATUS; "3" -> LiveAction.PRINT
            "4" -> LiveAction.NATIVE; "5" -> LiveAction.ACKNOWLEDGE; else -> return 0
        }
        val directory=state ?: defaultState()
        val code=WindowsLiveWorkflow(NativeDevices,ui).run(action,directory,crop)
        ui.line("Exit code: $code. Safe report: ${directory.resolve("diagnostics.txt")}")
        ui.ask("Press Enter to close.")
        return code
    }
    fun nativeCheck(output: PrintStream = System.out): Int {
        if (!supported()) { output.println("WINDOWS_REQUIRED"); return 64 }
        output.println("NO_PHYSICAL_PRINT")
        return try { NativeDevices.nativeCheck(output::println); 0 }
        catch(e:WindowsBluetoothFailure) { output.println("WINDOWS_NATIVE_FAILED category=${e.code.name}"); 2 }
        catch(_:Exception) { output.println("WINDOWS_NATIVE_FAILED category=IO_ERROR"); 2 }
        catch(_:LinkageError) { output.println("WINDOWS_NATIVE_FAILED category=NATIVE_UNAVAILABLE"); 2 }
    }
    private fun defaultState():Path {
        val local=System.getenv("LOCALAPPDATA") ?: throw BridgeFailure(BridgeFailureCode.PERSISTENCE_ERROR)
        return Path.of(local,"PocketPhotoBridge","windows-live")
    }
    private class ConsoleUi(val console:Console,val output:PrintStream):LiveUi {
        override fun line(message:String) { output.println(message) }
        override fun ask(message:String):String? = console.readLine("%s ",message)
        override fun password(message:String):CharArray? = console.readPassword("%s ",message)
        override fun preview(path:Path) { ProcessBuilder("explorer.exe",path.toAbsolutePath().toString()).start() }
    }
    private object NativeDevices:LiveDeviceAccess {
        override fun camera(ui:LiveUi):CameraWifiConnector {
            val choices=WindowsCameraNetworking.candidates()
            if (choices.isEmpty()) { ui.line("No usable camera Wi-Fi link. Join the Sony AP in Settings and keep camera sharing active."); throw BridgeFailure(BridgeFailureCode.CAMERA_OFFLINE) }
            choices.forEachIndexed { i,choice -> ui.line("${i+1}: ${localName(choice.displayName)}") }
            val selected=select(ui,choices,"Choose the Wi-Fi interface connected to the Sony camera:")
            return CameraWifiConnector { WindowsCameraNetworking.connect(selected) }
        }
        override fun printer(ui:LiveUi):PrinterConnector = printer(ui, {})
        override fun printer(ui:LiveUi,report:(String)->Unit):PrinterConnector {
            val choices=WindowsBluetooth.pairedDevices()
            if (choices.isEmpty()) { ui.line("No paired printer found. Pair Xiaomi in Windows Bluetooth Settings first; close Xiaomi Home."); throw BridgeFailure(BridgeFailureCode.PRINTER_OFFLINE) }
            choices.forEachIndexed { i,choice -> ui.line("${i+1}: ${localName(choice.name)}") }
            val selected=select(ui,choices,"Choose your Xiaomi photo printer:")
            return PrinterConnector { WindowsBluetooth.connect(selected, report=report) }
        }
        override fun nativeCheck(report:(String)->Unit) {
            WindowsBluetooth.nativeSelfTest()
            report("WINDOWS_WINSOCK_BINDINGS_READY")
            try { report("WINDOWS_PAIRED_DEVICES count=${WindowsBluetooth.pairedDevices().size}") }
            catch(e:WindowsBluetoothFailure) { report("WINDOWS_BLUETOOTH_UNAVAILABLE category=${e.code.name}") }
            catch(_:LinkageError) { report("WINDOWS_BLUETOOTH_UNAVAILABLE category=NATIVE_UNAVAILABLE") }
            try { report("WINDOWS_CAMERA_LINK_CANDIDATES count=${WindowsCameraNetworking.candidates(report).size}") }
            catch(_:Exception) { report("WINDOWS_CAMERA_DISCOVERY_UNAVAILABLE category=HTTP_ERROR") }
        }
        private fun <T> select(ui:LiveUi,choices:List<T>,prompt:String):T {
            val index=ui.ask(prompt)?.toIntOrNull()?.minus(1)
            return choices.getOrNull(index ?: -1) ?: throw BridgeFailure(BridgeFailureCode.CANCELLED)
        }
        private fun localName(value:String)=value.filter { it.isLetterOrDigit() || it in " ._-()" }.take(80).ifBlank { "Paired device" }
    }
}
