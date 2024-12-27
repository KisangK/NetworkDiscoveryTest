package com.example.networkdiscoverytest

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import androidx.annotation.RequiresApi

class NetworkDiscoveryManager(context: Context) {
    // The NsdManager is Android's system service for network service discovery
    private val nsdManager: NsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    // We use HTTP service type for compatibility and ease of use
    private val SERVICE_TYPE = "_http._tcp."

    // Keep track of discovered devices in a thread-safe list
    private val discoveredDevices = mutableListOf<DeviceInfo>()

    // Track our own service registration state
    private var localServiceName: String? = null
    private var isServiceRegistered = false

    // Data class representing essential device information
    data class DeviceInfo(
        val deviceName: String,    // Friendly device name (e.g., "Scott's Samsung")
        val ipAddress: String,     // Device's IP address on the network
        val port: Int             // Port number for connection
    )

    // Listener for our own service registration events
    private val registrationListener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
            // Called when our service is successfully registered on the network
            localServiceName = serviceInfo.serviceName
            isServiceRegistered = true
            println("Local service registered as: ${serviceInfo.serviceName}")
        }

        override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            // Called if service registration fails
            isServiceRegistered = false
            println("Service registration failed with error code: $errorCode")
        }

        override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
            // Called when our service is successfully unregistered
            localServiceName = null
            isServiceRegistered = false
            println("Local service unregistered")
        }

        override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            println("Service unregistration failed with error code: $errorCode")
        }
    }

    // Listener for discovering other devices' services
    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            println("Discovery failed to start with error code: $errorCode")
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            println("Discovery failed to stop with error code: $errorCode")
        }

        override fun onDiscoveryStarted(serviceType: String) {
            println("Service discovery started")
        }

        override fun onDiscoveryStopped(serviceType: String) {
            println("Service discovery stopped")
        }

        @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        override fun onServiceFound(service: NsdServiceInfo) {
            println("Service found: ${service.serviceName}")

            // Skip our own service to avoid self-discovery
            if (service.serviceName == localServiceName) {
                println("Skipping own service")
                return
            }

            // Create a callback for getting detailed service information
            val serviceInfoListener = @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            object : NsdManager.ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                    println("Service info callback registration failed with error: $errorCode")
                }

                override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                    // Update existing device info with new information
                    val hostAddress = serviceInfo.hostAddresses.firstOrNull()?.hostAddress ?: ""
                    val updatedDeviceInfo = DeviceInfo(
                        deviceName = serviceInfo.serviceName,
                        ipAddress = hostAddress,
                        port = serviceInfo.port
                    )

                    // Remove old entry and add updated one
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
                        service,
                        context.mainExecutor,
                        serviceInfoListener
                    )
                } else {
                    // Fall back to older resolve method for previous Android versions
                    nsdManager.resolveService(service, createResolveListener())
                }

            } catch (e: Exception) {
                println("Error registering service info callback: ${e.message}")
            }
        }

        override fun onServiceLost(service: NsdServiceInfo) {
            println("Service lost: ${service.serviceName}")
            // Remove the lost device from our list
            synchronized(discoveredDevices) {
                discoveredDevices.removeIf { it.deviceName == service.serviceName }
            }
        }
    }

    // Create a resolver for older Android versions
    private fun createResolveListener(): NsdManager.ResolveListener {
        return object : NsdManager.ResolveListener {
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
                synchronized(discoveredDevices) {
                    discoveredDevices.add(deviceInfo)
                }
                println("Resolved and added device: ${deviceInfo.deviceName}")
            }
        }
    }

    // Register this device's service on the network
    fun registerService(deviceName: String, port: Int) {
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = deviceName
            serviceType = SERVICE_TYPE
            setPort(port)
        }

        try {
            nsdManager.registerService(
                serviceInfo,
                NsdManager.PROTOCOL_DNS_SD,
                registrationListener
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
                SERVICE_TYPE,
                NsdManager.PROTOCOL_DNS_SD,
                discoveryListener
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