package com.example.networkdiscoverytest

import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), ConnectionManager.ConnectionCallback {
    // Core managers for network discovery and connection
    private lateinit var networkDiscoveryManager: NetworkDiscoveryManager
    private lateinit var connectionManager: ConnectionManager

    // UI components
    private lateinit var devicesAdapter: DevicesAdapter
    private lateinit var connectionStatusText: TextView
    private lateinit var connectedDeviceInfo: TextView
    private lateinit var searchButton: Button
    private lateinit var prepareConnectionButton: Button
    private lateinit var disconnectButton: Button

    // Dialog reference for managing connection code display
    private var connectionCodeDialog: AlertDialog? = null

    // State tracking
    private var selectedDevice: NetworkDiscoveryManager.DeviceInfo? = null
    private var isConnected = false

    // List to store sync items
    private val syncItems = mutableListOf<SyncItem>()
    private lateinit var recyclerViewSync: RecyclerView
    private lateinit var syncAdapter: SyncAdapter
    private lateinit var addItemButton: Button
    private lateinit var editText: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setupNetworkManagers()
        setupViews()
        setupSyncViews()
    }

    private fun setupNetworkManagers() {
        // Initialize network discovery and connection managers
        networkDiscoveryManager = NetworkDiscoveryManager(this)
        connectionManager = ConnectionManager(this)

        // Register this device's service with a unique name
        val deviceName = "${Build.MODEL}_${Build.DEVICE}"
        val servicePort = 50000
        networkDiscoveryManager.registerService(deviceName, servicePort)
    }

    private fun setupViews() {
        // Initialize UI components
        connectionStatusText = findViewById(R.id.connectionStatusText)
        connectedDeviceInfo = findViewById(R.id.connectedDeviceInfo)
        searchButton = findViewById(R.id.searchButton)
        prepareConnectionButton = findViewById(R.id.prepareConnectionButton)
        disconnectButton = findViewById(R.id.disconnectButton)

        // Initialize sync components
        recyclerViewSync = findViewById(R.id.recyclerViewSync)
        addItemButton = findViewById(R.id.addItemButton)
        editText = findViewById(R.id.editText)

        // Set up the RecyclerView adapter for discovered devices
        devicesAdapter = DevicesAdapter { device ->
            if (!isConnected) {
                selectedDevice = device
                showConnectionDialog()
            } else {
                Toast.makeText(this, "Already connected to a device", Toast.LENGTH_SHORT).show()
            }
        }

        // Configure the RecyclerView
        findViewById<RecyclerView>(R.id.devicesRecyclerView).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = devicesAdapter
        }

        setupButtonListeners()

        // Update visibility based on connection state
        updateButtonStates()

        // Start periodic device list updates
        startDeviceListUpdates()
    }

    private fun setupSyncViews() {
        syncAdapter = SyncAdapter(syncItems)
        recyclerViewSync.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = syncAdapter
        }

        addItemButton.setOnClickListener {
            val text = editText.text.toString()
            if (text.isNotEmpty()) {
                val newItem = SyncItem(text = text)
                addSyncItem(newItem)
                editText.text.clear()

                // Send to connected device
                if (isConnected) {
                    connectionManager.sendSyncMessage(
                        ConnectionManager.SyncMessage.ItemAdded(newItem)
                    )
                }
            }
        }
    }

    private fun setupButtonListeners() {
        // Search button - start discovering devices
        searchButton.setOnClickListener {
            if (!isConnected) {
                networkDiscoveryManager.startDiscovery()
                Toast.makeText(this, "Searching for devices...", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Please disconnect first", Toast.LENGTH_SHORT).show()
            }
        }

        // Prepare connection button - start server mode
        prepareConnectionButton.setOnClickListener {
            if (!isConnected) {
                startServerMode()
            } else {
                Toast.makeText(this, "Already connected", Toast.LENGTH_SHORT).show()
            }
        }

        // Disconnect button - end the current connection
        disconnectButton.setOnClickListener {
            if (isConnected) {
                performDisconnect()
            } else {
                Toast.makeText(this, "Not connected to any device", Toast.LENGTH_SHORT).show()
            }
        }
    }


    private fun startDeviceListUpdates() {
        lifecycleScope.launch {
            while (true) {
                val devices = networkDiscoveryManager.getDiscoveredDevices()
                devicesAdapter.submitList(devices)
                delay(1000) // Update every second
            }
        }
    }

    private fun updateConnectionStatus(
        status: String,
        localDevice: NetworkDiscoveryManager.DeviceInfo? = null,
        remoteDevice: NetworkDiscoveryManager.DeviceInfo? = null
    ) {
        println("Debug: Updating connection status - $status")
        println("Debug: Local device in status update - $localDevice")
        println("Debug: Remote device in status update - $remoteDevice")

        connectionStatusText.text = "Status: $status"

        if (localDevice != null && remoteDevice != null) {
            println("Debug: Both devices present, updating device info display")

            val deviceInfoText = buildString {
                // Local device section
                append("Your Device:\n")
                append("Name: ${localDevice.deviceName}\n")
                append("IP: ${localDevice.ipAddress}:${localDevice.port}\n\n")

                // Remote device section
                append("Connected Device:\n")
                append("Name: ${remoteDevice.deviceName}\n")
                append("IP: ${remoteDevice.ipAddress}:${remoteDevice.port}")
            }
            println("Debug: Setting device info text - $deviceInfoText")

            // Update the TextView and make it visible
            connectedDeviceInfo.text = deviceInfoText
            connectedDeviceInfo.visibility = View.VISIBLE
            println("Debug: Device info visibility set to VISIBLE")
        } else {
            println("Debug: Missing device info, hiding device info display")
            connectedDeviceInfo.visibility = View.GONE
        }
    }

    private fun startServerMode() {
        val connectionCode =  connectionManager.startServer(50000)
        showConnectionCodeDialog(connectionCode)
        updateConnectionStatus("Waiting for connection...")
    }

    private fun showConnectionCodeDialog(code: String) {
        // Store dialog reference for later dismissal
        connectionCodeDialog = AlertDialog.Builder(this)
            .setTitle("Ready to Connect")
            .setMessage("Connection Code: $code\nEnter this code on the other device.")
            .setPositiveButton("OK", null)
            .setCancelable(false)  // Prevent dismissal until connection is established or failed
            .show()
    }

    private fun showConnectionDialog() {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_NUMBER

        AlertDialog.Builder(this)
            .setTitle("Enter Connection Code")
            .setView(input)
            .setPositiveButton("Connect") { _, _ ->
                val code = input.text.toString()
                if (code.isEmpty()) {
                    Toast.makeText(this, "Please enter a connection code", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                selectedDevice?.let { device ->
                    updateConnectionStatus("Verifying connection code...")
                    connectionManager.connectToServer(
                        device.ipAddress,
                        50000,
                        code
                    )
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performDisconnect() {
        connectionManager.disconnect()
        isConnected = false
        selectedDevice = null

        // Reset the UI state
        updateConnectionStatus("Disconnected")
        updateButtonStates()
        connectionCodeDialog?.dismiss()
        connectionCodeDialog = null

        Toast.makeText(this, "Disconnected successfully", Toast.LENGTH_SHORT).show()

        // Restart discovery if needed
        networkDiscoveryManager.startDiscovery()
    }

    private fun updateButtonStates() {
        searchButton.isEnabled = !isConnected
        prepareConnectionButton.isEnabled = !isConnected
        disconnectButton.isEnabled = isConnected
        addItemButton.isEnabled = isConnected
    }

    private fun addSyncItem(item: SyncItem) {
        syncItems.add(item)
        syncItems.sortByDescending { it.timestamp }
        syncAdapter.notifyDataSetChanged()
    }

    // ConnectionManager.ConnectionCallback implementation
    override fun onConnectionEstablished() {
        runOnUiThread {
            isConnected = true
            updateConnectionStatus("Connected")
            Toast.makeText(this, "Connection successful!", Toast.LENGTH_SHORT).show()
            updateButtonStates()
        }
    }

    override fun onConnectionInfoUpdated(connectionInfo: ConnectionManager.ConnectionInfo?) {
        runOnUiThread {
            if (connectionInfo != null) {
                // We have a valid connection - handle the connected state
                println("Debug: MainActivity received connection info update")
                println("Debug: Local device - ${connectionInfo.localDevice}")
                println("Debug: Remote device - ${connectionInfo.remoteDevice}")

                // Dismiss the connection code dialog if we're the server
                connectionCodeDialog?.dismiss()
                connectionCodeDialog = null

                // Update UI with both local and remote device information
                updateConnectionStatus(
                    "Connected",
                    connectionInfo.localDevice,
                    connectionInfo.remoteDevice
                )

                // Update connection state and UI elements
                isConnected = true
                updateButtonStates()

                // Initiate data synchronization
                connectionManager.sendSyncMessage(
                    ConnectionManager.SyncMessage.SyncRequest(syncItems)
                )
            } else {
                println("Debug: MainActivity received null connection info")
                // Connection info is null, meaning we're disconnected
                // Update the UI to show disconnected state
                updateConnectionStatus("Disconnected")

                // Reset connection state
                isConnected = false
                updateButtonStates()

                // Clear the displayed device information
                connectedDeviceInfo.visibility = View.GONE
            }
        }
    }

    override fun onMessageReceived(message: String) {
        println("Message received in MainActivity: $message")
        if (connectionManager.verifyConnectionCode(message)) {
            println("Connection code verified successfully")
            connectionManager.sendMessage("CONNECTION_ACCEPTED")
            runOnUiThread {
                isConnected = true
                selectedDevice?.let { device ->
                    updateConnectionStatus("Connected")
                }
                Toast.makeText(this, "Connection code verified!", Toast.LENGTH_SHORT).show()
            }
        } else {
            println("Connection code verification failed")
            connectionManager.sendMessage("CONNECTION_REJECTED")
            runOnUiThread {
                isConnected = false
                updateConnectionStatus("Connection Rejected")
                Toast.makeText(this, "Invalid connection code.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onConnectionFailed(error: String) {
        runOnUiThread {
            isConnected = false
            updateConnectionStatus("Connection Failed")
            updateButtonStates()

            // Dismiss the connection code dialog if it's showing
            connectionCodeDialog?.dismiss()
            connectionCodeDialog = null

            Toast.makeText(this, error, Toast.LENGTH_SHORT).show()

            // Restart discovery if appropriate
            if (!isConnected) {
                networkDiscoveryManager.startDiscovery()
            }
        }
    }

    override fun onSyncRequestReceived(items: List<SyncItem>) {
        // Merge received items with our items
        val merged = (syncItems + items)
            .distinctBy { it.id }
            .sortedByDescending { it.timestamp }

        runOnUiThread {
            syncItems.clear()
            syncItems.addAll(merged)
            syncAdapter.notifyDataSetChanged()

            // Send our merged list back
            connectionManager.sendSyncMessage(
                ConnectionManager.SyncMessage.SyncResponse(merged)
            )
        }
    }

    override fun onSyncResponseReceived(items: List<SyncItem>) {
        runOnUiThread {
            syncItems.clear()
            syncItems.addAll(items)
            syncAdapter.notifyDataSetChanged()
        }
    }

    override fun onItemAdded(item: SyncItem) {
        runOnUiThread {
            addSyncItem(item)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        networkDiscoveryManager.cleanup()
        connectionManager.disconnect()
        connectionCodeDialog?.dismiss()
    }
}

/**
 * Adapter for the RecyclerView showing discovered devices
 */
class DevicesAdapter(
    private val onDeviceSelected: (NetworkDiscoveryManager.DeviceInfo) -> Unit
) : ListAdapter<NetworkDiscoveryManager.DeviceInfo, DevicesAdapter.ViewHolder>(
    object : DiffUtil.ItemCallback<NetworkDiscoveryManager.DeviceInfo>() {
        override fun areItemsTheSame(
            oldItem: NetworkDiscoveryManager.DeviceInfo,
            newItem: NetworkDiscoveryManager.DeviceInfo
        ) = oldItem.deviceName == newItem.deviceName

        override fun areContentsTheSame(
            oldItem: NetworkDiscoveryManager.DeviceInfo,
            newItem: NetworkDiscoveryManager.DeviceInfo
        ) = oldItem == newItem
    }
) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_2, parent, false)
        return ViewHolder(view, onDeviceSelected)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(
        view: View,
        private val onDeviceSelected: (NetworkDiscoveryManager.DeviceInfo) -> Unit
    ) : RecyclerView.ViewHolder(view) {
        private val text1: TextView = view.findViewById(android.R.id.text1)
        private val text2: TextView = view.findViewById(android.R.id.text2)

        fun bind(device: NetworkDiscoveryManager.DeviceInfo) {
            text1.text = device.deviceName
            text2.text = "${device.ipAddress}:${device.port}"
            itemView.setOnClickListener { onDeviceSelected(device) }
        }
    }
}

class SyncAdapter(private val items: List<SyncItem>) :
    RecyclerView.Adapter<SyncAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val textView: TextView = view.findViewById(android.R.id.text1)

        fun bind(item: SyncItem) {
            val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                .format(Date(item.timestamp))
            textView.text = "${item.text} ($time)"
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_1, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size
}