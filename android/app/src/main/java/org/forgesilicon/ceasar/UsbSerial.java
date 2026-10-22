package org.forgesilicon.ceasar;

import android.hardware.usb.*;
import java.util.ArrayList;
import java.util.List;

/** USB CDC ACM only: no vendor-specific UART bridge driver is needed for native USB. */
public final class UsbSerial implements AutoCloseable {
    public interface Listener {
        void onLines(List<String> lines);
        void onError(String message);
    }
    private volatile boolean running;
    private UsbDeviceConnection connection;
    private UsbEndpoint output;
    private UsbInterface dataInterface, controlInterface;
    private Thread reader;

    public static boolean isSupported(UsbDevice device) {
        for (int i = 0; i < device.getInterfaceCount(); i++)
            if (device.getInterface(i).getInterfaceClass() == UsbConstants.USB_CLASS_CDC_DATA) return true;
        return false;
    }

    public void open(UsbManager manager, UsbDevice device, Listener listener) {
        UsbEndpoint input = null;
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface iface = device.getInterface(i);
            if (iface.getInterfaceClass() == UsbConstants.USB_CLASS_COMM && controlInterface == null)
                controlInterface = iface;
            if (iface.getInterfaceClass() == UsbConstants.USB_CLASS_CDC_DATA && dataInterface == null) {
                for (int e = 0; e < iface.getEndpointCount(); e++) {
                    UsbEndpoint ep = iface.getEndpoint(e);
                    if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK && ep.getDirection() == UsbConstants.USB_DIR_IN) {
                        dataInterface = iface; input = ep; break;
                    }
                }
            }
        }
        if (input == null || controlInterface == null) throw new IllegalStateException("No CDC ACM interfaces found");
        for (int e = 0; e < dataInterface.getEndpointCount(); e++) {
            UsbEndpoint ep = dataInterface.getEndpoint(e);
            if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK && ep.getDirection() == UsbConstants.USB_DIR_OUT) output = ep;
        }
        connection = manager.openDevice(device);
        if (connection == null) throw new IllegalStateException("USB permission or device access unavailable");
        if (!connection.claimInterface(controlInterface, true) || !connection.claimInterface(dataInterface, true)) {
            close(); throw new IllegalStateException("Could not claim USB interfaces");
        }
        // 115200 baud, one stop bit, no parity, eight data bits. Native USB ignores baud rate.
        byte[] coding = {(byte) 0x00, (byte) 0xc2, 0x01, 0x00, 0x00, 0x00, 0x08};
        if (connection.controlTransfer(0x21, 0x20, 0, controlInterface.getId(), coding, coding.length, 1000) != coding.length
                || connection.controlTransfer(0x21, 0x22, 3, controlInterface.getId(), null, 0, 1000) < 0) {
            close(); throw new IllegalStateException("Firmware rejected CDC line coding or DTR/RTS");
        }
        final UsbEndpoint endpoint = input;
        final UsbDeviceConnection opened = connection;
        running = true;
        reader = new Thread(() -> {
            byte[] buffer = new byte[4096];
            LineFramer framer = new LineFramer();
            try {
                while (running) {
                    int count = opened.bulkTransfer(endpoint, buffer, buffer.length, 200);
                    if (count > 0) {
                        List<String> lines = new ArrayList<>();
                        framer.accept(buffer, count, lines::add);
                        if (!lines.isEmpty() && running) listener.onLines(lines);
                    }
                    // A negative synchronous read can mean timeout; detach is handled by the Activity.
                }
            } catch (Exception error) {
                if (running) listener.onError("USB read failed. Reconnect the wearable.");
            }
        }, "ceasar-usb-reader");
        reader.start();
    }

    public synchronized void requestSync() {
        if (!running || connection == null || output == null) throw new IllegalStateException("USB output unavailable");
        byte[] command = "{\"cmd\":\"sync\"}\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int offset = 0;
        while (offset < command.length) {
            int written = connection.bulkTransfer(output, command, offset, command.length - offset, 1000);
            if (written <= 0) throw new IllegalStateException("Sync command could not be sent");
            offset += written;
        }
    }

    @Override public synchronized void close() {
        running = false;
        if (reader != null && reader != Thread.currentThread()) {
            try { reader.join(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        if (connection != null) {
            if (dataInterface != null) connection.releaseInterface(dataInterface);
            if (controlInterface != null) connection.releaseInterface(controlInterface);
            connection.close(); connection = null;
        }
    }
}
