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
import java.net.ProtocolException
import java.net.SocketTimeoutException

class ConnectionManager(private val connectionCallback: ConnectionCallback) {
    // Socket-related properties for network communication
    private var serverSocket: ServerSocket? = null // It listens for connection attempts on a specific port. Only one device (the server) in a connection needs this
    private var clientSocket: Socket? = null // The actual phone call connection between two devices. Both the server and client have one
    private var printWriter: PrintWriter? = null // It writes text data to the socket connection
    private var bufferedReader: BufferedReader? = null // It reads text data from the socket connection

    // Coroutine-related properties for asynchronous operations
    private var connectionJob: Job? = null
    private var verificationJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    // Properties to track connection state and device information
    private var currentConnectionCode: String? = null
    private var isVerified = false
    private var isServer = false
    private var connectionState = ConnectionState.DISCONNECTED // Represents the connection state of the device

    // Device information
    private var localDeviceInfo: NetworkDiscoveryManager.DeviceInfo? = null // Represents this device
    private var remoteDeviceInfo: NetworkDiscoveryManager.DeviceInfo? = null // Represents the device we're connecting to

    // Protocol constants
    companion object {
        private const val PROTOCOL_VERSION = "1.0"
        private const val VERIFICATION_TIMEOUT = 30000L // 30 seconds
        private const val SOCKET_TIMEOUT = 30000 // 30 seconds
        private const val BUFFER_SIZE = 8192
    }

    // List of available connection states
    private enum class ConnectionState {
        DISCONNECTED,
        VERIFYING,
        CONNECTING,
        CONNECTED
    }

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

    sealed class VerificationResult {
        data object Success : VerificationResult()
        data class Failure(val reason: String) : VerificationResult()
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
        if (connectionState != ConnectionState.DISCONNECTED) {
            throw IllegalStateException("Cannot start server: already ${connectionState.name.lowercase()}")
        }

        isServer = true
        currentConnectionCode = generateConnectionCode()
        connectionState = ConnectionState.VERIFYING

        /* Use a separate socket for verification.
                Once the verification is complete, the verification socket is closed */
        var verificationSocket: Socket? = null
        var verificationReader: BufferedReader? = null
        var verificationWriter: PrintWriter? = null

        connectionJob = scope.launch {
            try {
                /* Create a new ServerSocket with reuse address option: If the app tries to create a
                new ServerSocket on the same port before the operating system has fully released it
                from the previous connection, it leads to an error */
                serverSocket = ServerSocket(port).apply {
                    reuseAddress = true // This allows the socket to be bound even if it's in TIME_WAIT state
                    soTimeout = SOCKET_TIMEOUT // timeout for accepting connections
                    receiveBufferSize = BUFFER_SIZE // the buffer that holds incoming data
                }

                while (isActive && connectionState == ConnectionState.VERIFYING) {
                    try {
                        // Accept verification connection with timeout
                        withTimeout(VERIFICATION_TIMEOUT) {
                            verificationSocket = serverSocket?.accept() // It pauses execution until someone tries to connect
                        }

                        // Move on if connected
                        verificationSocket?.let { socket ->
                            socket.apply {
                                keepAlive = true
                                soTimeout = SOCKET_TIMEOUT
                                receiveBufferSize = BUFFER_SIZE
                                sendBufferSize = BUFFER_SIZE
                                tcpNoDelay = true
                            }

                            verificationReader = BufferedReader(
                                InputStreamReader(socket.getInputStream())
                            )

                            verificationWriter = PrintWriter(
                                socket.getOutputStream(),
                                true
                            )

                            val verificationResult = handleServerVerification(
                                verificationReader!!,
                                verificationWriter!!
                            )

                            when (verificationResult) {
                                is VerificationResult.Success -> {
                                    // Verification successful, prepare for data connection
                                    isVerified = true
                                    connectionState = ConnectionState.CONNECTING

                                    // Accept the main data connection
                                    val dataSocket = serverSocket?.accept()
                                    dataSocket?.let {
                                        configureDataSocket(it)
                                        establishSecureConnection(it)
                                    }
                                }
                                is VerificationResult.Failure -> {
                                    connectionCallback.onConnectionFailed(verificationResult.reason)
                                    connectionState = ConnectionState.DISCONNECTED
                                }
                            }
                        }
                    } catch (e: TimeoutCancellationException) {
                        connectionCallback.onConnectionFailed("Verification timed out")
                        break
                    }
                }
            } catch (e: Exception) {
                handleConnectionError(e)
            } finally {
                // Clean up verification resources
                safeClose(verificationReader)
                safeClose(verificationWriter)
                safeClose(verificationSocket)

                if (connectionState != ConnectionState.CONNECTED) {
                    // Only close server socket if connection wasn't established
                    safeClose(serverSocket)
                    connectionState = ConnectionState.DISCONNECTED
                    isVerified = false
                }
            }
        }

        return currentConnectionCode ?: "Error"
    }

    // Start client mode (device initiating connection)
    fun connectToServer(ip: String, port: Int, connectionCode: String) {
        if (connectionState != ConnectionState.DISCONNECTED) {
            connectionCallback.onConnectionFailed(
                "Cannot connect: already ${connectionState.name.lowercase()}"
            )
            return
        }

        isServer = false
        connectionState = ConnectionState.VERIFYING

        verificationJob = scope.launch {
            var verificationSocket: Socket? = null
            var verificationReader: BufferedReader? = null
            var verificationWriter: PrintWriter? = null

            try {
                withTimeout(VERIFICATION_TIMEOUT) {
                    verificationSocket = Socket(ip, port).apply {
                        keepAlive = true
                        soTimeout = SOCKET_TIMEOUT
                        receiveBufferSize = BUFFER_SIZE
                        sendBufferSize = BUFFER_SIZE
                        tcpNoDelay = true
                    }
                    verificationReader = BufferedReader(
                        InputStreamReader(verificationSocket!!.getInputStream())
                    )
                    verificationWriter = PrintWriter(
                        verificationSocket!!.getOutputStream(),
                        true
                    )

                    // Send verification code
                    verificationWriter?.println(connectionCode)

                    // Wait for verification response
                    val response = verificationReader?.readLine()

                    when {
                        response == null ->
                            throw Exception("No verification response received")

                        response == "VERIFICATION_SUCCESS" -> {
                            // Verification successful, establish data connection
                            isVerified = true
                            connectionState = ConnectionState.CONNECTING

                            // Create main data connection
                            val dataSocket = Socket(ip, port)
                            configureDataSocket(dataSocket)
                            establishSecureConnection(dataSocket)
                        }

                        response.startsWith("VERIFICATION_FAILED:") -> {
                            val reason = response.substringAfter("VERIFICATION_FAILED:")
                            throw Exception("Verification failed: $reason")
                        }

                        else -> throw Exception("Invalid verification response")
                    }
                }
            } catch (e: Exception) {
                handleConnectionError(e)
            } finally {
                // Clean up verification resources
                safeClose(verificationReader)
                safeClose(verificationWriter)
                safeClose(verificationSocket)

                if (connectionState != ConnectionState.CONNECTED) {
                    connectionState = ConnectionState.DISCONNECTED
                    isVerified = false
                }
            }
        }
    }

    private fun handleServerVerification(
        reader: BufferedReader,
        writer: PrintWriter
    ): VerificationResult {
        return try {
            val receivedCode = reader.readLine()
                ?: return VerificationResult.Failure("No verification code received")

            if (verifyConnectionCode(receivedCode)) {
                writer.println("VERIFICATION_SUCCESS")
                VerificationResult.Success
            } else {
                writer.println("VERIFICATION_FAILED:Invalid code")
                VerificationResult.Failure("Invalid connection code")
            }
        } catch (e: Exception) {
            VerificationResult.Failure("Verification error: ${e.message}")
        }
    }

    private fun configureDataSocket(socket: Socket) {
        socket.apply {
            keepAlive = true
            receiveBufferSize = BUFFER_SIZE
            sendBufferSize = BUFFER_SIZE
            tcpNoDelay = true
        }

        localDeviceInfo = NetworkDiscoveryManager.DeviceInfo(
            deviceName = Build.MODEL,
            ipAddress = socket.localAddress.hostAddress ?: "unknown",
            port = socket.localPort
        )
        println("Debug: Local device info set - ${localDeviceInfo}")

        localDeviceInfo?.let { deviceInfo ->
            val message = "DEVICE_INFO:${deviceInfo.deviceName}|${deviceInfo.ipAddress}|${deviceInfo.port}"
            sendMessage(message)
            println("Debug: Sent device info to peer - $message")
        }
    }

    private fun establishSecureConnection(socket: Socket) {
        if (!isVerified) {
            throw SecurityException("Attempting to establish connection without verification")
        }

        try {
            clientSocket = socket
            printWriter = PrintWriter(socket.getOutputStream(), true) //Auto-flushing: Messages are sent immediately rather than being buffered.
            bufferedReader = BufferedReader(InputStreamReader(socket.getInputStream()))

            connectionState = ConnectionState.CONNECTED

            // Start the message handling loop. It starts a continuous listening loop in a coroutine (background task)
            startMessageHandling()

            // Notify successful connection
            connectionCallback.onConnectionEstablished()

        } catch (e: Exception) {
            handleConnectionError(e)
            throw e
        }
    }

    private fun startMessageHandling() {
        scope.launch {
            try {
                while (isActive && connectionState == ConnectionState.CONNECTED) {
                    val message = bufferedReader?.readLine() ?: throw Exception("Connection closed by peer")
                    handleMessage(message)
                }
            } catch (e: Exception) {
                if (connectionState == ConnectionState.CONNECTED) {
                    handleConnectionError(e)
                }
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
                    println("Debug: Received DEVICE_INFO message - $message")
                    val parts = message.substringAfter("DEVICE_INFO:").split("|")
                    remoteDeviceInfo = NetworkDiscoveryManager.DeviceInfo(
                        deviceName = parts[0],
                        ipAddress = parts[1],
                        port = parts[2].toInt()
                    )
                    println("Debug: Remote device info set - $remoteDeviceInfo")


                    // Notify UI of complete connection info when both devices are known
                    if (localDeviceInfo != null) {
                        println("Debug: Both device infos available, updating UI")
                        connectionCallback.onConnectionInfoUpdated(
                            ConnectionInfo(localDeviceInfo!!, remoteDeviceInfo!!)
                        )
                    } else {
                        println("Debug: Local device info still null after receiving remote info")
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
        val errorMessage = when (e) {
            is TimeoutCancellationException -> "Connection timed out"
            is SocketTimeoutException -> "Connection timed out"
            is ProtocolException -> "Protocol error: ${e.message}"
            is SecurityException -> e.message ?: "Security error"
            else -> "Connection error: ${e.message}"
        }

        connectionCallback.onConnectionFailed(errorMessage)
        performLocalDisconnect()
    }

    private fun safeClose(closeable: AutoCloseable?) {
        try {
            closeable?.close()
        } catch (e: Exception) {
            println("Error closing resource: ${e.message}")
        }
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
        }
    }

    // Perform the actual disconnect operations
    private fun performLocalDisconnect() {
        try {
            // Cancel any ongoing coroutines first
            connectionJob?.cancel()
            verificationJob?.cancel()

            // Close all resources
            safeClose(printWriter)
            safeClose(bufferedReader)
            safeClose(clientSocket)
            safeClose(serverSocket)
        } catch (e: Exception) {
            println("Error during disconnect: ${e.message}")
        } finally {
            // Reset all state
            connectionJob = null
            verificationJob = null
            serverSocket = null
            clientSocket = null
            printWriter = null
            bufferedReader = null
            currentConnectionCode = null
            localDeviceInfo = null
            remoteDeviceInfo = null
            isVerified = false
            connectionState = ConnectionState.DISCONNECTED

            // Notify UI
            connectionCallback.onConnectionInfoUpdated(null)
        }
    }
    class ProtocolException(message: String) : Exception(message)
}