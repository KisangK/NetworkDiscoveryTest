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

        // Set up search button behavior
        searchButton.setOnClickListener {
            if (!isConnected) {
                networkDiscoveryManager.startDiscovery()
                Toast.makeText(this, "Searching for devices...", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Please disconnect first", Toast.LENGTH_SHORT).show()
            }
        }

        // Set up prepare connection button behavior
        prepareConnectionButton.setOnClickListener {
            if (!isConnected) {
                startServerMode()
            } else {
                Toast.makeText(this, "Already connected", Toast.LENGTH_SHORT).show()
            }
        }

        // Configure the disconnect button
        disconnectButton.setOnClickListener {
            if (isConnected) {
                performDisconnect()
            } else {
                Toast.makeText(this, "Not connected to any device", Toast.LENGTH_SHORT).show()
            }
        }

        // Update visibility based on connection state
        updateButtonStates()

        // Periodically update the device list
        lifecycleScope.launch {
            while (true) {
                val devices = networkDiscoveryManager.getDiscoveredDevices()
                devicesAdapter.submitList(devices)
                delay(1000) // Update every second
            }
        }
    }

    private fun setupSyncViews() {
        recyclerViewSync = findViewById(R.id.recyclerViewSync)
        addItemButton = findViewById(R.id.addItemButton)
        editText = findViewById(R.id.editText)

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

    private fun updateConnectionStatus(
        status: String,
        localDevice: NetworkDiscoveryManager.DeviceInfo? = null,
        remoteDevice: NetworkDiscoveryManager.DeviceInfo? = null
    ) {
        connectionStatusText.text = "Status: $status"

        if (localDevice != null && remoteDevice != null) {
            connectedDeviceInfo.text = buildString {
                append("Local Device: ${localDevice.deviceName}\n")
                append("IP: ${localDevice.ipAddress}\n\n")
                append("Connected to: ${remoteDevice.deviceName}\n")
                append("IP: ${remoteDevice.ipAddress}")
            }
            connectedDeviceInfo.visibility = View.VISIBLE
        } else {
            connectedDeviceInfo.visibility = View.GONE
        }
    }

    private fun startServerMode() {
        connectionManager.startServer(50000)
        val connectionCode = connectionManager.getCurrentConnectionCode()
        showConnectionCodeDialog(connectionCode ?: "Error")
        updateConnectionStatus("Waiting for connection...")
    }

    private fun showConnectionCodeDialog(code: String) {
        // Store dialog reference for later dismissal
        connectionCodeDialog = AlertDialog.Builder(this)
            .setTitle("Ready to Connect")
            .setMessage("Connection Code: $code\nEnter this code on the other device.")
            .setPositiveButton("OK", null)
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
                selectedDevice?.let { device ->
                    updateConnectionStatus("Connecting to ${device.deviceName}...")
                    connectionManager.connectToServer(device.ipAddress, device.port, code)
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

        // Temporarily disable connection buttons
        searchButton.isEnabled = false
        prepareConnectionButton.isEnabled = false

        //Re-enable after a short delay to prevent immediate re-connection
        lifecycleScope.launch {
            delay(1000)
            searchButton.isEnabled = true
            prepareConnectionButton.isEnabled = true
        }

        Toast.makeText(this, "Disconnected successfully", Toast.LENGTH_SHORT).show()
    }

    private fun updateButtonStates() {
        // Enable/disable buttons based on connection state
        searchButton.isEnabled = !isConnected
        prepareConnectionButton.isEnabled = !isConnected
        disconnectButton.isEnabled = isConnected
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
        }
    }

    override fun onConnectionInfoUpdated(connectionInfo: ConnectionManager.ConnectionInfo?) {
        runOnUiThread {
            if (connectionInfo != null) {
                // We have a valid connection - handle the connected state

                // Dismiss the connection code dialog if we're the server
                connectionCodeDialog?.dismiss()

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

    override fun onConnectionFailed(error: String) {
        runOnUiThread {
            isConnected = false
            if (!connectionManager.isServer()) {
                updateConnectionStatus("Connection Failed")
            } else {
                // If we're the server, keep showing "Waiting for connection..."
                updateConnectionStatus("Waiting for connection...")
            }
            updateButtonStates()
            Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
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