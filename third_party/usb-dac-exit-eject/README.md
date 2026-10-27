# USB DAC exit logical eject

The Settings > Quit path now performs an exit-only logical eject before killing the process. It stops DAC keep-alive synchronously, prevents asynchronous player cleanup from reconnecting the Android kernel USB-audio driver, stops and waits for UsbStabilizer, drains/stops the direct USB stream, releases every claimed UsbInterface, closes UsbDeviceConnection, clears USB state, removes services, closes the task, and then terminates the process.

This is intentionally different from the normal "Release USB DAC" action, which continues to reconnect the kernel USB-audio driver so other Android apps can use the DAC. A normal third-party Android app cannot electrically remove power, change USB role, or invoke the privileged USB HAL port reset API. Therefore the Quit action is a logical eject, not a physical disconnect.
