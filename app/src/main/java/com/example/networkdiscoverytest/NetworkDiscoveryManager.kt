package com.example.networkdiscoverytest

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import androidx.annotation.RequiresApi

class NetworkDiscoveryManager(context: Context) {
    /* context.getSystemService() is an Android system method that provides access to Android system-level services.
    These are fundamental services that Android makes available to apps */
    /* When your app calls getSystemService(Context.NSD_SERVICE):
    Android looks up the system service registry and finds the NSD service */
    /* Network Service Discovery manager:
    Register your device as a service on the local network
    Discover other devices advertising themselves
    Resolve service details like IP addresses and ports */
    private val nsdManager: NsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    /* "_http._tcp." was chosen because:
    It's widely supported
    It's a common choice for general-purpose network discovery */
    private val SERVICE_TYPE = "_http._tcp."

    // Keep track of discovered devices in a thread-safe list
    private val discoveredDevices = mutableListOf<DeviceInfo>()

    // Track our own service registration state
    private var localServiceName: String? = null // Stores the name of this device's service when it's registered on the network
    private var isServiceRegistered = false // Used to prevent duplicate registrations and ensure proper cleanup

    // Data class representing essential device information
    data class DeviceInfo(
        val deviceName: String,
        val ipAddress: String,     // Device's IP address on the network
        val port: Int             // Port number for connection
    )

    /* NsdManager.RegistrationListener is an interface provided by Android
    that defines callbacks for service registration events. */
    private val registrationListener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
            localServiceName = serviceInfo.serviceName
            isServiceRegistered = true
            println("Local service registered as: ${serviceInfo.serviceName}")
        }

        override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            isServiceRegistered = false
            println("Service registration failed with error code: $errorCode")
        }

        override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
            localServiceName = null
            isServiceRegistered = false
            println("Local service unregistered")
        }

        override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            println("Service unregistration failed for ${serviceInfo.serviceName}")
            println("Error code: $errorCode")

            when (errorCode) {
                NsdManager.FAILURE_ALREADY_ACTIVE -> {
                    println("Unregistration failed because another operation is in progress")
                    // Maybe try again after a delay
                }
                NsdManager.FAILURE_INTERNAL_ERROR -> {
                    println("Internal system error during unregistration")
                    // Maybe notify the user or try an alternative cleanup
                }
                else -> {
                    println("Unknown error during unregistration")
                }
            }

            isServiceRegistered = true  // Keep marked as registered since unregistration failed
        }
    }

    /* discoveryListener's job is to monitor and report on network activity
    - specifically looking for other devices that are advertising their presence.*/
    private val discoveryListener = object : NsdManager.DiscoveryListener {
        // Called when we can't even begin looking for other devices
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            println("Discovery failed to start with error code: $errorCode")
        }

        // Called when we can't properly stop the discovery process
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            println("Discovery failed to stop with error code: $errorCode")
        }

        // Called when we successfully start looking for other devices
        override fun onDiscoveryStarted(serviceType: String) {
            println("Service discovery started")
        }

        // Called when we successfully stop looking for other devices
        override fun onDiscoveryStopped(serviceType: String) {
            println("Service discovery stopped")
        }

        // Called when we find a new device advertising itself
        @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        override fun onServiceFound(service: NsdServiceInfo) {
            println("Service found: ${service.serviceName}")

            // Skip our own service to avoid self-discovery
            if (service.serviceName == localServiceName) {
                println("Skipping own service")
                return
            }

            // Listener that will receive detailed information about the discovered service
            val serviceInfoListener = @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            object : NsdManager.ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                    println("Service info callback registration failed with error: $errorCode")
                }

                // Update existing device info with new information
                override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                    val hostAddress = serviceInfo.hostAddresses.firstOrNull()?.hostAddress ?: ""
                    val updatedDeviceInfo = DeviceInfo(
                        deviceName = serviceInfo.serviceName,
                        ipAddress = hostAddress,
                        port = serviceInfo.port
                    )

                    // Remove old entry and add updated one
                    // It handles updating our list of known devices in a thread-safe way.
                    synchronized(discoveredDevices) {
                        discoveredDevices.removeIf { it.deviceName == serviceInfo.serviceName }
                        discoveredDevices.add(updatedDeviceInfo)
                    }
                    println("Updated device: ${updatedDeviceInfo.deviceName}")
                }

                override fun onServiceLost() {
                    println("Service was lost during info callback")
                }

                override fun onServiceInfoCallbackUnregistered() {
                    println("Service info callback was unregistered")
                }
            }

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    // Register for service updates on newer Android versions
                    nsdManager.registerServiceInfoCallback(
                        service, // The discovered service we want to learn more about
                        context.mainExecutor, // Where we want to receive callbacks
                        serviceInfoListener // How we'll handle the information we receive
                    )
                } else {
                    // Fall back to older resolve method for previous Android versions
                    /* The key difference from the newer registerServiceInfoCallback method is that
                    resolveService is a one-time operation.
                    Think of it like sending someone to get information once versus having them stay
                    and keep you updated about any changes. */
                    nsdManager.resolveService(service, createResolveListener())
                }

            } catch (e: Exception) {
                println("Error registering service info callback: ${e.message}")
            }
        }

        override fun onServiceLost(service: NsdServiceInfo) {
            // Called when a previously discovered device is no longer available
            println("Service lost: ${service.serviceName}")
            // Remove the lost device from our list
            synchronized(discoveredDevices) {
                discoveredDevices.removeIf { it.deviceName == service.serviceName }
            }
        }
    }

    // Create a resolver for older Android versions
    /* NsdManager.ResolveListener is responsible for handling the process of getting detailed
    information about a discovered network service. */
    private fun createResolveListener(): NsdManager.ResolveListener {
        return object : NsdManager.ResolveListener {
            // Handles situations when we can't get the information we need
            override fun onResolveFailed(service: NsdServiceInfo, errorCode: Int) {
                println("Failed to resolve service: error $errorCode")
            }

            override fun onServiceResolved(service: NsdServiceInfo) {
                // Skip our own service
                if (service.serviceName == localServiceName) {
                    println("Skipping own service in resolve")
                    return
                }
                val deviceInfo = DeviceInfo(
                    deviceName = service.serviceName,
                    ipAddress = service.host.hostAddress ?: "",
                    port = service.port
                )
                // Safely adds information to our list of known devices using thread synchronization
                synchronized(discoveredDevices) {
                    discoveredDevices.add(deviceInfo)
                }
                println("Resolved and added device: ${deviceInfo.deviceName}")
            }
        }
    }

    // Registers your device as a discoverable service on the local network
    fun registerService(deviceName: String, port: Int) {
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = deviceName // The name others will see
            serviceType = SERVICE_TYPE // Using "_http._tcp."
            setPort(port) // The communication channel number
        }

        try {
            nsdManager.registerService(
                serviceInfo,
                NsdManager.PROTOCOL_DNS_SD, // We're using DNS Service Discovery
                registrationListener // Our callback handler for registration events
            )
            println("Attempting to register service: $deviceName")
        } catch (e: Exception) {
            println("Failed to register service: ${e.message}")
        }
    }

    // Unregister this device's service
    private fun unregisterService() {
        if (isServiceRegistered) {
            try {
                nsdManager.unregisterService(registrationListener)
                println("Unregistering service")
            } catch (e: Exception) {
                println("Failed to unregister service: ${e.message}")
            }
        }
    }

    // Start discovering other devices
    fun startDiscovery() {
        try {
            nsdManager.discoverServices(
                SERVICE_TYPE, // What type of services are we looking for
                NsdManager.PROTOCOL_DNS_SD, // Which discovery protocol should we use
                discoveryListener // How should we handle what we find
            )
        } catch (e: Exception) {
            println("Failed to start discovery: ${e.message}")
        }
    }

    // Stop discovering devices
    private fun stopDiscovery() {
        try {
            nsdManager.stopServiceDiscovery(discoveryListener)
        } catch (e: Exception) {
            println("Failed to stop discovery: ${e.message}")
        }
    }

    // Get the current list of discovered devices
    fun getDiscoveredDevices(): List<DeviceInfo> {
        synchronized(discoveredDevices) {
            return discoveredDevices.toList()
        }
    }

    // Clean up resources
    fun cleanup() {
        stopDiscovery()
        unregisterService()
    }
}