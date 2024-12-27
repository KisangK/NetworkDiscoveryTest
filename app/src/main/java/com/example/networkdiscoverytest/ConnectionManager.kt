package com.example.networkdiscoverytest

import android.os.Build
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.*

class ConnectionManager(private val connectionCallback: ConnectionCallback) {
    // Socket-related properties for network communication
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var printWriter: PrintWriter? = null
    private var bufferedReader: BufferedReader? = null

    // Coroutine-related properties for asynchronous operations
    private var connectionJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    // Properties to track connection state and device information
    private var currentConnectionCode: String? = null
    private var isServer = false
    private var localDeviceInfo: NetworkDiscoveryManager.DeviceInfo? = null
    private var remoteDeviceInfo: NetworkDiscoveryManager.DeviceInfo? = null

    // Data class to hold complete connection information
    data class ConnectionInfo(
        val localDevice: NetworkDiscoveryManager.DeviceInfo,
        val remoteDevice: NetworkDiscoveryManager.DeviceInfo
    )

    // Interface for communicating connection events back to the UI
    interface ConnectionCallback {
        fun onConnectionEstablished()
        fun onConnectionFailed(error: String)
        fun onMessageReceived(message: String)
        fun onConnectionInfoUpdated(connectionInfo: ConnectionInfo)
    }

    // Start server mode (device accepting connections)
    fun startServer(port: Int): String {
        isServer = true
        currentConnectionCode = generateConnectionCode()

        connectionJob = scope.launch {
            try {
                serverSocket = ServerSocket(port)
                val socket = serverSocket?.accept() // Wait for client connection

                socket?.let {
                    // Create local device info for server
                    localDeviceInfo = NetworkDiscoveryManager.DeviceInfo(
                        deviceName = Build.MODEL,
                        ipAddress = socket.localAddress.hostAddress ?: "unknown",
                        port = socket.localPort
                    )
                    establishConnection(it)
                }
            } catch (e: Exception) {
                connectionCallback.onConnectionFailed("Server start failed: ${e.message}")
            }
        }

        return currentConnectionCode ?: "Error"
    }

    // Start client mode (device initiating connection)
    fun connectToServer(ip: String, port: Int) {
        isServer = false
        connectionJob = scope.launch {
            try {
                withTimeout(10000) { // 10-second connection timeout
                    val socket = Socket(ip, port)
                    localDeviceInfo = NetworkDiscoveryManager.DeviceInfo(
                        deviceName = Build.MODEL,
                        ipAddress = socket.localAddress.hostAddress ?: "unknown",
                        port = socket.localPort
                    )
                    establishConnection(socket)
                }
            } catch (e: TimeoutCancellationException) {
                connectionCallback.onConnectionFailed("Connection timed out")
            } catch (e: Exception) {
                connectionCallback.onConnectionFailed("Connection failed: ${e.message}")
            }
        }
    }

    // Set up connection streams and start message handling
    private fun establishConnection(socket: Socket) {
        try {
            clientSocket = socket
            printWriter = PrintWriter(socket.getOutputStream(), true)
            bufferedReader = BufferedReader(InputStreamReader(socket.getInputStream()))

            println("Connection established with ${socket.inetAddress.hostAddress}:${socket.port}")

            // Send our device info immediately
            localDeviceInfo?.let { sendDeviceInfo(it) }

            connectionCallback.onConnectionEstablished()

            // Start message receiving loop
            scope.launch {
                try {
                    while (isActive) {
                        val message = bufferedReader?.readLine() ?: throw Exception("Connection closed by peer")
                        println("Received message: $message")
                        handleMessage(message)
                    }
                } catch (e: Exception) {
                    connectionCallback.onConnectionFailed("Communication error: ${e.message}")
                }
            }
        } catch (e: Exception) {
            connectionCallback.onConnectionFailed("Failed to establish connection: ${e.message}")
        }
    }

    // Handle incoming messages based on their type
    private fun handleMessage(message: String) {
        when {
            message.startsWith("DEVICE_INFO:") -> {
                try {
                    val parts = message.substringAfter("DEVICE_INFO:").split("|")
                    remoteDeviceInfo = NetworkDiscoveryManager.DeviceInfo(
                        deviceName = parts[0],
                        ipAddress = parts[1],
                        port = parts[2].toInt()
                    )

                    // Notify UI of complete connection info when both devices are known
                    if (localDeviceInfo != null && remoteDeviceInfo != null) {
                        connectionCallback.onConnectionInfoUpdated(
                            ConnectionInfo(localDeviceInfo!!, remoteDeviceInfo!!)
                        )
                    }
                } catch (e: Exception) {
                    println("Error parsing device info: ${e.message}")
                }
            }
            else -> connectionCallback.onMessageReceived(message)
        }
    }

    // Format and send device information message
    private fun sendDeviceInfo(deviceInfo: NetworkDiscoveryManager.DeviceInfo) {
        val message = "DEVICE_INFO:${deviceInfo.deviceName}|${deviceInfo.ipAddress}|${deviceInfo.port}"
        sendMessage(message)
    }

    // Send a message to the connected device
    fun sendMessage(message: String) {
        scope.launch {
            try {
                printWriter?.println(message)
                printWriter?.flush() // Ensure message is sent immediately
            } catch (e: Exception) {
                connectionCallback.onConnectionFailed("Failed to send message: ${e.message}")
            }
        }
    }

    // Verify the connection code entered by the client
    fun verifyConnectionCode(inputCode: String): Boolean {
        return currentConnectionCode == inputCode
    }

    // Generate a random 6-digit connection code
    private fun generateConnectionCode(): String {
        return (100000..999999).random().toString()
    }

    // Get the current connection code (used by UI)
    fun getCurrentConnectionCode(): String? = currentConnectionCode

    // Clean up all connections and resources
    fun disconnect() {
        connectionJob?.cancel()
        serverSocket?.close()
        clientSocket?.close()
        printWriter?.close()
        bufferedReader?.close()
        currentConnectionCode = null
        localDeviceInfo = null
        remoteDeviceInfo = null
    }
}