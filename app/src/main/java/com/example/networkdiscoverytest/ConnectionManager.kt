package com.example.networkdiscoverytest

import android.os.Build
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket

class ConnectionManager(private val connectionCallback: ConnectionCallback) {
    // Socket-related properties for network communication
    private var serverSocket: ServerSocket? =
        null // It listens for connection attempts on a specific port. Only one device (the server) in a connection needs this
    private var clientSocket: Socket? =
        null // The actual phone call connection between two devices. Both the server and client have one
    private var printWriter: PrintWriter? = null // It writes text data to the socket connection
    private var bufferedReader: BufferedReader? =
        null // It reads text data from the socket connection

    // Coroutine-related properties for asynchronous operations
    private var connectionJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    // Properties to track connection state and device information
    private var currentConnectionCode: String? = null
    private var isServer = false
    private var localDeviceInfo: NetworkDiscoveryManager.DeviceInfo? =
        null // Represents this device
    private var remoteDeviceInfo: NetworkDiscoveryManager.DeviceInfo? =
        null // Represents the device we're connecting to
    private var connectionState =
        ConnectionState.DISCONNECTED // Represents the connection state of the device

    private var pendingConnectionCode: String? = null

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
        fun onConnectionInfoUpdated(connectionInfo: ConnectionInfo?) // The app uses this to display details about both connected devices.
        fun onSyncRequestReceived(items: List<SyncItem>)
        fun onSyncResponseReceived(items: List<SyncItem>)
        fun onItemAdded(item: SyncItem)
    }

    // Start server mode (device accepting connections)
    fun startServer(port: Int): String {
        println("DEBUG: Starting server on port $port - Current State: $connectionState")
        if (connectionState != ConnectionState.DISCONNECTED) {
            println("DEBUG: Performing cleanup before starting server")
            performLocalDisconnect()
        }
        isServer = true
        currentConnectionCode = generateConnectionCode()
        connectionState = ConnectionState.CONNECTING

        connectionJob = scope.launch {
            try {
                println("DEBUG: Creating server socket")
                /* Create a new ServerSocket with reuse address option: If the app tries to create a
                new ServerSocket on the same port before the operating system has fully released it
                from the previous connection, it leads to an error */
                serverSocket = ServerSocket(port).apply {
                    // This allows the socket to be bound even if it's in TIME_WAIT state
                    reuseAddress = true
                    // Set a reasonable timeout for accepting connections
                    soTimeout = 30000 // 30 seconds
                }
                println("DEBUG: Waiting for client connection")
                while (isActive && connectionState == ConnectionState.CONNECTING) {
                    val socket =
                        serverSocket?.accept() // It pauses execution until someone tries to connect

                    // When someone does connect, we set up our identity
                    socket?.let {
                        println("DEBUG: Client connected from ${it.inetAddress.hostAddress}")
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
                }
            } catch (e: Exception) {
                println("DEBUG: Server start failed: ${e.message}")
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
    fun connectToServer(ip: String, port: Int, connectionCode: String) {
        println("DEBUG: Connection attempt initiated")
        println("DEBUG: Current connection state: $connectionState")

        when (connectionState) {
            ConnectionState.CONNECTED -> {
                println("DEBUG: Rejected - Already connected")
                connectionCallback.onConnectionFailed("Already connected to another device. Please disconnect first.")
                return
            }

            ConnectionState.CONNECTING -> {
                println("DEBUG: Rejected - Connection in progress")
                connectionCallback.onConnectionFailed("Connection attempt already in progress.")
                return
            }

            ConnectionState.DISCONNECTED -> {
                if (clientSocket?.isClosed == false) {
                    println("DEBUG: Warning - Client socket still open, forcing cleanup")
                    performLocalDisconnect()
                    // Add a small delay to ensure cleanup
                    Thread.sleep(500)
                }

                println("DEBUG: Starting new connection as client")
                isServer = false
                connectionState = ConnectionState.CONNECTING
                pendingConnectionCode = connectionCode

                connectionJob = scope.launch {
                    try {
                        withTimeout(10000) {
                            println("DEBUG: Attempting socket connection to $ip:$port")
                            val socket = Socket(ip, port)
                            println("DEBUG: Socket connection established")
                            localDeviceInfo = NetworkDiscoveryManager.DeviceInfo(
                                deviceName = Build.MODEL,
                                ipAddress = socket.localAddress.hostAddress ?: "unknown",
                                port = socket.localPort
                            )
                            establishConnection(socket)
                        }
                    } catch (e: Exception) {
                        println("DEBUG: Connection attempt failed: ${e.message}")
                        println("DEBUG: Stack trace: ${e.stackTrace.joinToString("\n")}")
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
            printWriter = PrintWriter(socket.getOutputStream(), true)
            bufferedReader = BufferedReader(InputStreamReader(socket.getInputStream()))

            println("Connection established with ${socket.inetAddress.hostAddress}:${socket.port}")
            println("DEBUG: Basic socket connection established")

            if (isServer) {
                println("DEBUG: Server waiting for verification code...")
                val receivedMessage = bufferedReader?.readLine()
                println("DEBUG: Received message: $receivedMessage")

                if (receivedMessage?.startsWith("CODE:") == true) {
                    val code = receivedMessage.substringAfter("CODE:")
                    println("DEBUG: Extracted code: $code")
                    println("DEBUG: Expected code: $currentConnectionCode")

                    if (verifyConnectionCode(code)) {
                        println("DEBUG: Code verified successfully")
                        sendMessage("CONNECTION_ACCEPTED")
                        completeConnection(socket)
                    } else {
                        println("DEBUG: Invalid code received, cleaning up client connection only")
                        sendMessage("CONNECTION_REJECTED")

                        cleanupClientConnection()

                        connectionCallback.onConnectionFailed("Invalid connection code")

                        println("DEBUG: Server ready for new connection attempts")
                    }
                } else {
                    println("DEBUG: Invalid message format, cleaning up client connection")
                    cleanupClientConnection()
                    connectionCallback.onConnectionFailed("Invalid verification message format")
                }
            } else {
                val code = pendingConnectionCode ?: ""
                sendMessage("CODE:$code")

                val response = bufferedReader?.readLine()
                when (response) {
                    "CONNECTION_ACCEPTED" -> completeConnection(socket)
                    "CONNECTION_REJECTED" -> {
                        cleanupClientConnection()
                        connectionCallback.onConnectionFailed("Connection code rejected")
                    }

                    else -> {
                        cleanupClientConnection()
                        connectionCallback.onConnectionFailed("Invalid server response")
                    }
                }
            }
        } catch (e: Exception) {
            handleConnectionError(e)
        }
    }

    private fun completeConnection(socket: Socket) {
        localDeviceInfo?.let { sendDeviceInfo(it) }

        connectionState = ConnectionState.CONNECTED
        connectionCallback.onConnectionEstablished()

        scope.launch {
            try {
                while (isActive) {
                    val message =
                        bufferedReader?.readLine() ?: throw Exception("Connection closed by peer")
                    handleMessage(message)
                }
            } catch (e: Exception) {
                connectionCallback.onConnectionFailed("Communication error: ${e.message}")
            }
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

            message.startsWith("CODE:") -> {
                val code: String = message.substringAfter("CODE:")
                if (verifyConnectionCode(code)) {
                    sendMessage("CONNECTION_ACCEPTED")
                    connectionState = ConnectionState.CONNECTED
                    connectionCallback.onConnectionEstablished()
                } else {
                    sendMessage("CONNECTION_REJECTED")
                    performLocalDisconnect()
                    connectionCallback.onConnectionFailed("Invalid connection code.")
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
        }
    }

    // Format and send device information message
    private fun sendDeviceInfo(deviceInfo: NetworkDiscoveryManager.DeviceInfo) {
        val message =
            "DEVICE_INFO:${deviceInfo.deviceName}|${deviceInfo.ipAddress}|${deviceInfo.port}"
        sendMessage(message)
    }

    private fun handleConnectionError(e: Exception) {
        println("DEBUG: Handling connection error: ${e.message}")
        println("DEBUG: Current state before error handling: $connectionState")

        connectionState = ConnectionState.DISCONNECTED
        performLocalDisconnect()

        when (e) {
            is TimeoutCancellationException -> {
                println("DEBUG: Connection timed out")
                connectionCallback.onConnectionFailed("Connection timed out")
            }

            else -> {
                println("DEBUG: General connection failure")
                connectionCallback.onConnectionFailed("Connection failed: ${e.message}")
            }
        }
        println("DEBUG: Error handling completed")

    }

    // Send a message to the connected device
    private fun sendMessage(message: String) {
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
        println("DEBUG: Starting local disconnect process")
        println("DEBUG: Initial state - Connection state: $connectionState")
        println("DEBUG: Initial state - Client socket closed? ${clientSocket?.isClosed}")
        println("DEBUG: Initial state - Server socket closed? ${serverSocket?.isClosed}")

        try {
            connectionState = ConnectionState.DISCONNECTED
            // Cancel any ongoing coroutines first
            println("DEBUG: Cancelling connection job")
            connectionJob?.cancel()
            connectionJob = null

            // Close streams first
            println("DEBUG: Closing streams")
            try {
                printWriter?.let {
                    it.flush()
                    it.close()
                }
                bufferedReader?.close()
            } catch (e: Exception) {
                println("DEBUG: Error closing streams: ${e.message}")
            }

            // Then close sockets
            clientSocket?.let { socket ->
                try {
                    println("DEBUG: Shutting down client socket")
                    if (!socket.isInputShutdown) socket.shutdownInput()
                    if (!socket.isOutputShutdown) socket.shutdownOutput()
                    if (!socket.isClosed) socket.close()
                    println("DEBUG: Client socket closed successfully")
                } catch (e: Exception) {
                    println("Error closing client socket: ${e.message}")
                }
            }

            serverSocket?.let { server ->
                try {
                    println("DEBUG: Checking server socket")
                    if (!server.isClosed) {
                        server.close()
                        println("DEBUG: Server socket closed successfully")
                    }
                } catch (e: Exception) {
                    println("Error closing server socket: ${e.message}")
                }
            }
        } catch (e: Exception) {
            println("Error during disconnect: ${e.message}")
        } finally {
            println("DEBUG: Resetting connection state")
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
            pendingConnectionCode = null


            // Notify UI
            println("DEBUG: Notifying UI of disconnection")
            connectionCallback.onConnectionInfoUpdated(null)

            println("DEBUG: Disconnect process completed")
            println("DEBUG: Final state - Connection state: $connectionState")
            println("DEBUG: Final state - All sockets null? ${clientSocket == null && serverSocket == null}")
        }
    }

    private fun cleanupClientConnection(){
        println("DEBUG: Starting client connection cleanup")
        try {
            printWriter?.let {
                println("DEBUG: Closing print writer")
                it.flush()
                it.close()
            }
            printWriter = null

            bufferedReader?.let{
                println("DEBUG: Closing buffered reader")
                it.close()
            }
            bufferedReader = null

            clientSocket?.let{ socket ->
                println("DEBUG: Closing client socket")
                if (!socket.isClosed){
                    if (!socket.isInputShutdown) socket.shutdownInput()
                    if (!socket.isOutputShutdown) socket.shutdownOutput()
                    socket.close()
                }
            }
            clientSocket = null

            println("DEBUG: Resetting client-specific state")
            pendingConnectionCode = null
            localDeviceInfo = null
            remoteDeviceInfo = null

            if (!isServer){
                connectionState = ConnectionState.DISCONNECTED
            }

            println("DEBUG: Client connection cleanup completed")
        } catch (e: Exception){
            println("DEBUG: Error during client connection cleanup: ${e.message}")
        }
    }

    fun isServer(): Boolean {
        return isServer
    }
}