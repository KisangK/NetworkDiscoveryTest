package com.example.networkdiscoverytest

import android.os.Build
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.*

class ConnectionManager(private val connectionCallback: ConnectionCallback) {
    // Socket-related properties for network communication
    private var serverSocket: ServerSocket? = null // It listens for connection attempts on a specific port. Only one device (the server) in a connection needs this
    private var clientSocket: Socket? = null // The actual phone call connection between two devices. Both the server and client have one
    private var printWriter: PrintWriter? = null // It writes text data to the socket connection
    private var bufferedReader: BufferedReader? = null // It reads text data from the socket connection

    // Coroutine-related properties for asynchronous operations
    private var connectionJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    // Properties to track connection state and device information
    private var currentConnectionCode: String? = null
    private var isServer = false
    private var localDeviceInfo: NetworkDiscoveryManager.DeviceInfo? = null // Represents this device
    private var remoteDeviceInfo: NetworkDiscoveryManager.DeviceInfo? = null // Represents the device we're connecting to
    private var connectionState = ConnectionState.DISCONNECTED // Represents the connection state of the device


    // Data class to hold complete connection information
    data class ConnectionInfo(
        val localDevice: NetworkDiscoveryManager.DeviceInfo,
        val remoteDevice: NetworkDiscoveryManager.DeviceInfo
    )

    sealed class SyncMessage {
        // When a device connects to another device, it sends a SyncRequest containing its current list of items
        data class SyncRequest(val items: List<SyncItem>) : SyncMessage()
        // When a device receives a sync request, it responds with its complete, up-to-date list of items
        data class SyncResponse(val items: List<SyncItem>) : SyncMessage()
        // Represents a real-time update when a new item is created
        data class ItemAdded(val item: SyncItem) : SyncMessage()
    }

    // List of available connection states
    private enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED
    }

    // Interface for communicating connection events back to the UI
    interface ConnectionCallback {
        fun onConnectionEstablished()
        fun onConnectionFailed(error: String)
        fun onMessageReceived(message: String)
        fun onConnectionInfoUpdated(connectionInfo: ConnectionInfo?) // The app uses this to display details about both connected devices.
        fun onSyncRequestReceived(items: List<SyncItem>)
        fun onSyncResponseReceived(items: List<SyncItem>)
        fun onItemAdded(item: SyncItem)
    }

    // Start server mode (device accepting connections)
    fun startServer(port: Int): String {
        isServer = true
        currentConnectionCode = generateConnectionCode()

        connectionJob = scope.launch {
            try {
                /* Create a new ServerSocket with reuse address option: If the app tries to create a
                new ServerSocket on the same port before the operating system has fully released it
                from the previous connection, it leads to an error */
                serverSocket = ServerSocket(port).apply {
                    // This allows the socket to be bound even if it's in TIME_WAIT state
                    reuseAddress = true
                    // Set a reasonable timeout for accepting connections
                    soTimeout = 30000 // 30 seconds
                }
                val socket = serverSocket?.accept() // It pauses execution until someone tries to connect

                // When someone does connect, we set up our identity
                socket?.let {
                    // Configure the client socket for proper cleanup
                    it.apply {
                        keepAlive = true
                        soTimeout = 30000 // 30 seconds timeout for operations
                    }

                    // Create local device info for server
                    localDeviceInfo = NetworkDiscoveryManager.DeviceInfo(
                        deviceName = Build.MODEL,
                        ipAddress = socket.localAddress.hostAddress ?: "unknown",
                        port = socket.localPort
                    )
                    establishConnection(it)
                }
            } catch (e: Exception) {
                when {
                    e.message?.contains("Address already in use") == true -> {
                        connectionCallback.onConnectionFailed("Port is still in use. Please wait a moment and try again.")
                    }
                    e.message?.contains("Socket closed") == true -> {
                        // Normal disconnection, no need to report as error
                        println("Socket was closed normally")
                    }
                    else -> {
                        connectionCallback.onConnectionFailed("Server start failed: ${e.message}")
                    }
                }
            }
        }

        return currentConnectionCode ?: "Error"
    }

    // Start client mode (device initiating connection)
    fun connectToServer(ip: String, port: Int) {
        when (connectionState) {
            ConnectionState.CONNECTED -> {
                connectionCallback.onConnectionFailed("Already connected to another device. Please disconnect first.")
                return
            }
            ConnectionState.CONNECTING -> {
                connectionCallback.onConnectionFailed("Connection attempt already in progress.")
                return
            }
            ConnectionState.DISCONNECTED -> {
                isServer = false
                connectionState = ConnectionState.CONNECTING

                connectionJob = scope.launch {
                    try {
                        withTimeout(10000) {
                            val socket = Socket(ip, port)
                            localDeviceInfo = NetworkDiscoveryManager.DeviceInfo(
                                deviceName = Build.MODEL,
                                ipAddress = socket.localAddress.hostAddress ?: "unknown",
                                port = socket.localPort
                            )
                            establishConnection(socket)
                        }
                    } catch (e: Exception) {
                        handleConnectionError(e)
                    }
                }
            }
        }
    }

    // Set up connection streams and start message handling
    private fun establishConnection(socket: Socket) {
        try {
            clientSocket = socket
            // Auto-flushing: Messages are sent immediately rather than being buffered.
            printWriter = PrintWriter(socket.getOutputStream(), true)
            bufferedReader = BufferedReader(InputStreamReader(socket.getInputStream()))

            // The Announcement Phase:
            println("Connection established with ${socket.inetAddress.hostAddress}:${socket.port}")
            localDeviceInfo?.let { sendDeviceInfo(it) }
            connectionState = ConnectionState.CONNECTED
            connectionCallback.onConnectionEstablished()

            // The Listening Phase: It starts a continuous listening loop in a coroutine (background task)
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
            handleConnectionError(e)
        }
    }

    // Handle incoming messages based on their type
    private fun handleMessage(message: String) {
        when {
            /* Requests to synchronize data between devices
            When one device wants to ensure it has the same data as another, it sends this type of message */
            message.startsWith("SYNC_REQUEST:") -> {
                val items = Gson().fromJson<List<SyncItem>>(
                    message.substringAfter("SYNC_REQUEST:"),
                    object : TypeToken<List<SyncItem>>() {}.type
                )
                connectionCallback.onSyncRequestReceived(items)
            }
            // Handles the reply to a sync request
            message.startsWith("SYNC_RESPONSE:") -> {
                val items = Gson().fromJson<List<SyncItem>>(
                    message.substringAfter("SYNC_RESPONSE:"),
                    object : TypeToken<List<SyncItem>>() {}.type
                )
                connectionCallback.onSyncResponseReceived(items)
            }
            // For Individual Item Updates
            message.startsWith("ITEM_ADDED:") -> {
                val item = Gson().fromJson(
                    message.substringAfter("ITEM_ADDED:"),
                    SyncItem::class.java
                )
                connectionCallback.onItemAdded(item)
            }
            message.startsWith("DEVICE_INFO:") -> {
                try {
                    val parts = message.substringAfter("DEVICE_INFO:").split("|")
                    remoteDeviceInfo = NetworkDiscoveryManager.DeviceInfo(
                        deviceName = parts[0],
                        ipAddress = parts[1],
                        port = parts[2].toInt()
                    )

                    // Notify UI of complete connection info when both devices are known
                    if (localDeviceInfo != null) {
                        connectionCallback.onConnectionInfoUpdated(
                            ConnectionInfo(localDeviceInfo!!, remoteDeviceInfo!!)
                        )
                    }
                } catch (e: Exception) {
                    println("Error parsing device info: ${e.message}")
                }
            }
            message == "DISCONNECT_REQUEST" -> {
                // Other side wants to disconnect
                println("Received disconnect request")
                // Send acknowledgment
                sendMessage("DISCONNECT_ACKNOWLEDGE")
                // Perform local cleanup
                performLocalDisconnect()
            }
            message == "DISCONNECT_ACKNOWLEDGE" -> {
                // Other side has acknowledged our disconnect request
                println("Received disconnect acknowledgment")
                performLocalDisconnect()
            }
            else -> connectionCallback.onMessageReceived(message)
        }
    }

    // Format and send device information message
    private fun sendDeviceInfo(deviceInfo: NetworkDiscoveryManager.DeviceInfo) {
        val message = "DEVICE_INFO:${deviceInfo.deviceName}|${deviceInfo.ipAddress}|${deviceInfo.port}"
        sendMessage(message)
    }

    private fun handleConnectionError(e: Exception) {
        connectionState = ConnectionState.DISCONNECTED
        when (e) {
            is TimeoutCancellationException ->
                connectionCallback.onConnectionFailed("Connection timed out")
            else ->
                connectionCallback.onConnectionFailed("Connection failed: ${e.message}")
        }
        performLocalDisconnect()
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

    fun sendSyncMessage(message: SyncMessage) {
        val json = when (message) {
            is SyncMessage.SyncRequest -> "SYNC_REQUEST:${Gson().toJson(message.items)}"
            is SyncMessage.SyncResponse -> "SYNC_RESPONSE:${Gson().toJson(message.items)}"
            is SyncMessage.ItemAdded -> "ITEM_ADDED:${Gson().toJson(message.item)}"
        }
        sendMessage(json)
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

    // Initiate a graceful disconnect
    fun disconnect() {
        if (connectionState != ConnectionState.DISCONNECTED) {
            try {
                // Send disconnect request and wait briefly for acknowledgment
                sendMessage("DISCONNECT_REQUEST")
                // Give the other side a moment to respond
                scope.launch {
                    delay(1000) // Wait 1 second for acknowledgment
                    performLocalDisconnect()
                }
            } catch (e: Exception) {
                // If sending fails, just disconnect locally
                performLocalDisconnect()
            }
        } else {
            performLocalDisconnect()
        }
    }

    // Check if we have an active connection
    private fun isConnected(): Boolean {
        return clientSocket?.isConnected == true && !clientSocket?.isClosed!!
    }

    // Perform the actual disconnect operations
    private fun performLocalDisconnect() {
        try {
            // Cancel any ongoing coroutines first
            connectionJob?.cancel()

            // Close streams first
            printWriter?.close()
            bufferedReader?.close()

            // Then close sockets
            clientSocket?.let { socket ->
                try {
                    if (!socket.isInputShutdown) socket.shutdownInput()
                    if (!socket.isOutputShutdown) socket.shutdownOutput()
                    socket.close()
                } catch (e: Exception) {
                    println("Error closing client socket: ${e.message}")
                }
            }

            serverSocket?.let { server ->
                try {
                    if (!server.isClosed) {
                        server.close()
                    }
                } catch (e: Exception) {
                    println("Error closing server socket: ${e.message}")
                }
            }
        } catch (e: Exception) {
            println("Error during disconnect: ${e.message}")
        } finally {
            // Reset all state
            connectionJob = null
            serverSocket = null
            clientSocket = null
            printWriter = null
            bufferedReader = null
            currentConnectionCode = null
            localDeviceInfo = null
            remoteDeviceInfo = null
            connectionState = ConnectionState.DISCONNECTED

            // Notify UI
            connectionCallback.onConnectionInfoUpdated(null)
        }
    }
}