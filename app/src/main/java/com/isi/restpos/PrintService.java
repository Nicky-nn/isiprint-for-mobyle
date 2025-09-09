package com.isi.restpos;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.pdf.PdfRenderer;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

import com.github.anastaciocintra.escpos.EscPos;
import com.github.anastaciocintra.escpos.image.BitonalThreshold;
import com.github.anastaciocintra.escpos.image.CoffeeImage;
import com.github.anastaciocintra.escpos.image.EscPosImage;
import com.github.anastaciocintra.escpos.image.RasterBitImageWrapper;
import com.github.anastaciocintra.output.TcpIpOutputStream;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

public class PrintService extends Service {
    private static final String CHANNEL_ID = "PrintServiceChannel";
    private static final int NOTIFICATION_ID = 1;
    private static final String VAR = "PrintService";
    private static final String ACTION_USB_PERMISSION = "com.isi.restpos.USB_PERMISSION";

    private SimpleHttpServer simpleHttpServer;
    private Handler mainHandler;
    
    // Variables para USB
    private UsbManager usbManager;
    private BroadcastReceiver usbReceiver;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(VAR, "Iniciando servicio de impresión...");
        mainHandler = new Handler(Looper.getMainLooper());
        createNotificationChannel();
        
        // Inicializar USB Manager
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        setupUsbReceiver();
    }

    private void showToast(final String message) {
        mainHandler.post(() -> Toast.makeText(getApplicationContext(), message, Toast.LENGTH_LONG).show());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(VAR, "Comando de inicio del servicio recibido");

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Servicio de Impresión")
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build();

        startForeground(NOTIFICATION_ID, notification);

        new Thread(() -> {
            try {
                if (simpleHttpServer == null) {
                    Thread.sleep(500);
                    simpleHttpServer = new SimpleHttpServer();
                    Log.d(VAR, "Servidor HTTP iniciado correctamente en puerto 7777");
                } else if (!simpleHttpServer.isAlive()) {
                    simpleHttpServer.stop();
                    Thread.sleep(1000);
                    simpleHttpServer = new SimpleHttpServer();
                    Log.d(VAR, "Servidor HTTP reiniciado correctamente");
                }
            } catch (Exception e) {
                Log.e(VAR, "Error al iniciar el servidor HTTP: " + e.getMessage(), e);
                showToast("Error crítico al iniciar el servidor de impresión.");
            }
        }).start();

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(VAR, "Destruyendo servicio de impresión...");
        if (simpleHttpServer != null) {
            simpleHttpServer.stop();
            simpleHttpServer = null;
        }
        
        // Limpiar USB receiver
        if (usbReceiver != null) {
            try {
                unregisterReceiver(usbReceiver);
                usbReceiver = null;
            } catch (Exception e) {
                Log.e(VAR, "Error al desregistrar USB receiver: " + e.getMessage());
            }
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Canal del Servicio de Impresión",
                    NotificationManager.IMPORTANCE_LOW
            );
            serviceChannel.setDescription("Canal para el servicio de impresión en segundo plano");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }

    private void setupUsbReceiver() {
        usbReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (ACTION_USB_PERMISSION.equals(action)) {
                    synchronized (this) {
                        UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                            if (device != null) {
                                Log.d(VAR, "Permiso USB concedido para: " + device.getDeviceName());
                            }
                        } else {
                            Log.d(VAR, "Permiso USB denegado para dispositivo " + device);
                        }
                    }
                }
            }
        };
        
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        registerReceiver(usbReceiver, filter);
    }

    private List<UsbDevice> getUsbPrinters() {
        List<UsbDevice> printers = new ArrayList<>();
        if (usbManager == null) {
            Log.e(VAR, "UsbManager no está inicializado.");
            return printers;
        }

        HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
        Log.d(VAR, "Buscando impresoras en " + deviceList.size() + " dispositivos USB...");

        for (UsbDevice device : deviceList.values()) {
            Log.d(VAR, "Evaluando dispositivo: " + device.getDeviceName() + " (VID: " + device.getVendorId() + ", PID: " + device.getProductId() + ")");
            if (isUsbPrinter(device)) {
                printers.add(device);
                Log.d(VAR, "✅ Impresora USB encontrada: " + getUsbPrinterDisplayName(device));
            }
        }
        Log.d(VAR, "Total de impresoras USB encontradas: " + printers.size());
        return printers;
    }

    private boolean isUsbPrinter(UsbDevice device) {
        // Criterio 1: La clase del dispositivo es Impresora.
        if (device.getDeviceClass() == UsbConstants.USB_CLASS_PRINTER) {
            Log.d(VAR, "Dispositivo '" + device.getDeviceName() + "' es una impresora por clase de dispositivo.");
            return true;
        }

        // Criterio 2: Una de sus interfaces es de clase Impresora.
        // Esto es más fiable, ya que un dispositivo puede tener múltiples funciones.
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface usbInterface = device.getInterface(i);
            if (usbInterface.getInterfaceClass() == UsbConstants.USB_CLASS_PRINTER) {
                Log.d(VAR, "Dispositivo '" + device.getDeviceName() + "' tiene una interfaz de impresora.");
                return true;
            }
        }

        Log.d(VAR, "Dispositivo '" + device.getDeviceName() + "' no parece ser una impresora.");
        return false;
    }

    private void requestUsbPermission(UsbDevice device) {
        PendingIntent permissionIntent = PendingIntent.getBroadcast(
                this, 0, new Intent(ACTION_USB_PERMISSION), 
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        usbManager.requestPermission(device, permissionIntent);
    }

    private UsbDevice findUsbPrinterByName(String printerName) {
        if (usbManager == null || printerName == null) return null;

        for (UsbDevice device : usbManager.getDeviceList().values()) {
            if (isUsbPrinter(device)) {
                String deviceName = getUsbPrinterDisplayName(device);
                if (printerName.equals(deviceName)) {
                    return device;
                }
            }
        }
        return null;
    }

    private String getUsbPrinterDisplayName(UsbDevice device) {
        String productName = device.getProductName();
        String manufacturerName = device.getManufacturerName();
        
        if (productName != null && !productName.trim().isEmpty()) {
            return productName.trim().replaceAll("\\s+", "_");
        } else if (manufacturerName != null && !manufacturerName.trim().isEmpty()) {
            return manufacturerName.trim().replaceAll("\\s+", "_") + "_Printer";
        } else {
            return "USB_Printer_" + device.getVendorId() + "_" + device.getProductId();
        }
    }

    private boolean isIPAddress(String str) {
        if (str == null || str.isEmpty()) return false;
        String[] parts = str.split("\\.");
        if (parts.length != 4) return false;
        
        try {
            for (String part : parts) {
                int num = Integer.parseInt(part);
                if (num < 0 || num > 255) return false;
            }
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private void printPDFViaUSB(UsbDevice usbDevice, File pdfFile, float scaleFactor) {
        new Thread(() -> {
            try {
                Log.d(VAR, "Iniciando impresión de PDF por USB: " + pdfFile.getAbsolutePath());
                
                // Verificar permisos USB
                if (!usbManager.hasPermission(usbDevice)) {
                    Log.d(VAR, "Solicitando permisos USB...");
                    requestUsbPermission(usbDevice);
                    return;
                }

                List<Bitmap> images = convertPDFToImages(pdfFile, scaleFactor);
                Log.d(VAR, "Se generaron " + images.size() + " páginas/bitmaps para imprimir por USB");

                // Abrir conexión USB
                UsbDeviceConnection connection = usbManager.openDevice(usbDevice);
                if (connection == null) {
                    Log.e(VAR, "No se pudo abrir conexión USB con el dispositivo");
                    showToast("Error: No se pudo conectar con la impresora USB");
                    return;
                }

                // Buscar la interfaz de impresora
                UsbInterface printerInterface = null;
                UsbEndpoint endpoint = null;
                
                for (int i = 0; i < usbDevice.getInterfaceCount(); i++) {
                    UsbInterface usbInterface = usbDevice.getInterface(i);
                    if (usbInterface.getInterfaceClass() == UsbConstants.USB_CLASS_PRINTER ||
                        usbDevice.getDeviceClass() == UsbConstants.USB_CLASS_PRINTER) {
                        
                        for (int j = 0; j < usbInterface.getEndpointCount(); j++) {
                            UsbEndpoint ep = usbInterface.getEndpoint(j);
                            if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                                ep.getDirection() == UsbConstants.USB_DIR_OUT) {
                                printerInterface = usbInterface;
                                endpoint = ep;
                                break;
                            }
                        }
                        if (endpoint != null) break;
                    }
                }

                if (printerInterface == null || endpoint == null) {
                    Log.e(VAR, "No se encontró interfaz de impresora válida");
                    showToast("Error: Interfaz de impresora no encontrada");
                    connection.close();
                    return;
                }

                // Reclamar la interfaz
                connection.claimInterface(printerInterface, true);

                // Crear wrapper USB para EscPos
                UsbOutputStream usbOutputStream = new UsbOutputStream(connection, endpoint);
                EscPos escpos = new EscPos(usbOutputStream);

                // Imprimir cada página
                for (int i = 0; i < images.size(); i++) {
                    Bitmap bitmap = images.get(i);
                    Log.d(VAR, "Enviando página " + (i + 1) + " a la impresora USB...");

                    EscPosImage escposImage = new EscPosImage(new AndroidCoffeeImage(bitmap), new BitonalThreshold(127));
                    RasterBitImageWrapper imageWrapper = new RasterBitImageWrapper();
                    escpos.write(imageWrapper, escposImage);
                    escpos.feed(3);
                    bitmap.recycle();
                    escpos.flush();
                    Thread.sleep(200);
                }

                escpos.feed(1);
                escpos.cut(EscPos.CutMode.FULL);
                escpos.close();

                // Liberar interfaz y cerrar conexión
                connection.releaseInterface(printerInterface);
                connection.close();

                Log.d(VAR, "PDF impreso exitosamente por USB");
                showToast("Impresión USB enviada correctamente.");

            } catch (Exception e) {
                Log.e(VAR, "Error al procesar PDF por USB: " + e.getMessage(), e);
                showToast("Error de impresión USB: " + e.getMessage());
            } finally {
                cleanupFile(pdfFile);
            }
        }).start();
    }

    // Clase wrapper para enviar datos por USB
    private static class UsbOutputStream extends java.io.OutputStream {
        private final UsbDeviceConnection connection;
        private final UsbEndpoint endpoint;
        private final int timeout = 1000;

        public UsbOutputStream(UsbDeviceConnection connection, UsbEndpoint endpoint) {
            this.connection = connection;
            this.endpoint = endpoint;
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[]{(byte) b});
        }

        @Override
        public void write(byte[] bytes) throws IOException {
            write(bytes, 0, bytes.length);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            if (bytes == null) {
                throw new NullPointerException("bytes cannot be null");
            }
            if (offset < 0 || length < 0 || offset + length > bytes.length) {
                throw new IndexOutOfBoundsException();
            }
            
            byte[] data = new byte[length];
            System.arraycopy(bytes, offset, data, 0, length);
            
            int result = connection.bulkTransfer(endpoint, data, length, timeout);
            if (result < 0) {
                throw new IOException("USB bulk transfer failed with result: " + result);
            }
        }

        @Override
        public void close() throws IOException {
            // La conexión se cierra en el método principal
        }
    }

    private void printPDF(String printerIP, File pdfFile, float scaleFactor) {
        new Thread(() -> {
            try {
                Log.d(VAR, "Iniciando impresión de PDF: " + pdfFile.getAbsolutePath());
                List<Bitmap> images = convertPDFToImages(pdfFile, scaleFactor);
                Log.d(VAR, "Se generaron " + images.size() + " páginas/bitmaps para imprimir");

                Log.d(VAR, "Conectando a impresora en IP: " + printerIP + " puerto 9100...");
                TcpIpOutputStream outputStream = new TcpIpOutputStream(printerIP, 9100);
                EscPos escpos = new EscPos(outputStream);

                for (int i = 0; i < images.size(); i++) {
                    Bitmap bitmap = images.get(i);
                    Log.d(VAR, "Enviando página " + (i + 1) + " a la impresora...");

                    EscPosImage escposImage = new EscPosImage(new AndroidCoffeeImage(bitmap), new BitonalThreshold(127));
                    RasterBitImageWrapper imageWrapper = new RasterBitImageWrapper();
                    escpos.write(imageWrapper, escposImage);
                    escpos.feed(3);
                    bitmap.recycle();
                    escpos.flush();
                    Thread.sleep(200);
                }

                escpos.feed(1);
                escpos.cut(EscPos.CutMode.FULL);
                escpos.close();

                Log.d(VAR, "PDF impreso exitosamente");
                showToast("Impresión enviada correctamente.");

            } catch (Exception e) {
                Log.e(VAR, "Error al procesar PDF: " + e.getMessage(), e);
                if (e instanceof IOException) {
                    showToast("Error de conexión con la impresora: " + printerIP);
                } else {
                    showToast("Error de impresión: " + e.getMessage());
                }
            } finally {
                cleanupFile(pdfFile);
            }
        }).start();
    }

    private void cleanupFile(File fileToClean) {
        if (fileToClean != null && fileToClean.exists()) {
            try {
                if (fileToClean.delete()) {
                    Log.d(VAR, "Archivo de impresión eliminado: " + fileToClean.getAbsolutePath());
                } else {
                    Log.w(VAR, "No se pudo eliminar el archivo de impresión: " + fileToClean.getAbsolutePath());
                }
            } catch (Exception e) {
                Log.e(VAR, "Error eliminando el archivo de impresión: " + e.getMessage(), e);
            }
        }
    }

    private List<Bitmap> convertPDFToImages(File pdfFile, float scaleFactor) throws IOException {
        List<Bitmap> bitmaps = new ArrayList<>();
        ParcelFileDescriptor fileDescriptor = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY);
        PdfRenderer renderer = new PdfRenderer(fileDescriptor);
        for (int i = 0; i < renderer.getPageCount(); i++) {
            PdfRenderer.Page page = renderer.openPage(i);
            int scaledWidth = (int) (page.getWidth() * scaleFactor);
            int scaledHeight = (int) (page.getHeight() * scaleFactor);
            Bitmap bitmap = Bitmap.createBitmap(scaledWidth, scaledHeight, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(Color.WHITE);
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            Bitmap rgb565Bitmap = convertToRGB565(bitmap);
            bitmaps.add(rgb565Bitmap);
            bitmap.recycle();
            page.close();
        }
        renderer.close();
        fileDescriptor.close();
        return bitmaps;
    }

    private Bitmap convertToRGB565(Bitmap argb8888Bitmap) {
        Bitmap rgb565Bitmap = Bitmap.createBitmap(argb8888Bitmap.getWidth(), argb8888Bitmap.getHeight(), Bitmap.Config.RGB_565);
        Canvas canvas = new Canvas(rgb565Bitmap);
        Paint paint = new Paint();
        paint.setColor(Color.WHITE);
        canvas.drawRect(0, 0, argb8888Bitmap.getWidth(), argb8888Bitmap.getHeight(), paint);
        canvas.drawBitmap(argb8888Bitmap, 0, 0, null);
        return rgb565Bitmap;
    }

    private File downloadPDF(String pdfUrl) {
        try {
            Log.d(VAR, "Descargando PDF desde: " + pdfUrl);
            URL url = new URL(pdfUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.connect();
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                Log.e(VAR, "Error al descargar el PDF: " + connection.getResponseMessage());
                showToast("Error " + connection.getResponseCode() + " al descargar PDF.");
                return null;
            }
            File file = new File(getFilesDir(), "download_" + System.currentTimeMillis() + ".pdf");
            InputStream inputStream = connection.getInputStream();
            FileOutputStream outputStream = new FileOutputStream(file);
            byte[] buffer = new byte[1024];
            int len;
            while ((len = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, len);
            }
            outputStream.close();
            inputStream.close();
            Log.d(VAR, "PDF descargado correctamente en: " + file.getAbsolutePath());
            return file;
        } catch (Exception e) {
            Log.e(VAR, "Error al descargar el PDF: " + e.getMessage(), e);
            showToast("No se pudo descargar el PDF. Verifique la URL y la conexión.");
            return null;
        }
    }

    private static class AndroidCoffeeImage implements CoffeeImage {
        private final Bitmap bitmap;
        public AndroidCoffeeImage(Bitmap bitmap) { this.bitmap = bitmap; }
        @Override public int getWidth() { return bitmap.getWidth(); }
        @Override public int getHeight() { return bitmap.getHeight(); }
        @Override public int getRGB(int x, int y) { return bitmap.getPixel(x, y); }
        @Override public CoffeeImage getSubimage(int x, int y, int w, int h) {
            Bitmap subBitmap = Bitmap.createBitmap(bitmap, x, y, w, h);
            return new AndroidCoffeeImage(subBitmap);
        }
    }

    private class SimpleHttpServer extends NanoHTTPD {
        public SimpleHttpServer() throws IOException {
            super(7777);
            Log.d(VAR, "Creando servidor HTTP en el puerto 7777...");
            start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
            Log.d(VAR, "Servidor HTTP completamente listo y funcional");
        }

        @Override
        public Response serve(IHTTPSession session) {
            String uri = session.getUri();
            String method = session.getMethod().toString();
            Log.d(VAR, "Nueva solicitud: " + method + " " + uri);

            if ("POST".equals(method) && "/print".equals(uri)) {
                return handlePrintFileUpload(session);
            } else if ("POST".equals(method) && "/printPDF".equals(uri)) {
                return handlePrintPdfUrl(session);
            } else if (("GET".equals(method) || "HEAD".equals(method)) && "/printers".equals(uri)) {
                return handleListPrinters(session);
            } else if ("OPTIONS".equals(method)) {
                return createCORSResponse(Response.Status.OK, MIME_PLAINTEXT, "OK");
            }
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Ruta no encontrada");
        }

        private Response handlePrintFileUpload(IHTTPSession session) {
            JSONObject responseJson = new JSONObject();
            try {
                Map<String, String> files = new HashMap<>();
                session.parseBody(files);
                String printer = session.getParms().get("printer");

                if (printer == null || printer.isEmpty()) {
                    return createErrorResponse(Response.Status.BAD_REQUEST, "Parámetro 'printer' no proporcionado");
                }

                String tempFilePath = files.get("file");
                if (tempFilePath == null) {
                    return createErrorResponse(Response.Status.BAD_REQUEST, "Archivo no encontrado en la solicitud");
                }

                File tempFile = new File(tempFilePath);
                File permanentFile = new File(getFilesDir(), "printjob_" + System.currentTimeMillis() + ".pdf");

                if (!tempFile.renameTo(permanentFile)) {
                    return createErrorResponse(Response.Status.INTERNAL_ERROR, "No se pudo mover el archivo temporal");
                }

                Log.d(VAR, "Archivo movido: " + permanentFile.getAbsolutePath());

                // Determinar si es IP (red) o nombre de impresora USB
                if (isIPAddress(printer)) {
                    // Impresión por red
                    Log.d(VAR, "Imprimiendo por red en IP: " + printer);
                    printPDF(printer, permanentFile, 3f);
                    responseJson.put("message", "Procesamiento iniciado en " + printer);
                } else {
                    // Buscar impresora USB por nombre
                    UsbDevice usbDevice = findUsbPrinterByName(printer);
                    if (usbDevice == null) {
                        cleanupFile(permanentFile);
                        return createErrorResponse(Response.Status.BAD_REQUEST, "Impresora USB '" + printer + "' no encontrada");
                    }
                    
                    Log.d(VAR, "Imprimiendo por USB en: " + printer);
                    printPDFViaUSB(usbDevice, permanentFile, 3f);
                    responseJson.put("message", "Procesamiento USB iniciado en " + printer);
                }

                responseJson.put("status", "success");
                return createCORSResponse(Response.Status.OK, "application/json", responseJson.toString());

            } catch (Exception e) {
                Log.e(VAR, "Error en /print: " + e.getMessage(), e);
                return createErrorResponse(Response.Status.INTERNAL_ERROR, "Error interno del servidor: " + e.getMessage());
            }
        }

        private Response handlePrintPdfUrl(IHTTPSession session) {
            JSONObject responseJson = new JSONObject();
            try {
                Map<String, String> postData = new HashMap<>();
                session.parseBody(postData);
                String body = postData.get("postData");

                if (body == null) {
                    return createErrorResponse(Response.Status.BAD_REQUEST, "Cuerpo de la solicitud vacío");
                }

                JSONObject jsonRequest = new JSONObject(body);
                String pdfUrl = jsonRequest.optString("pdf_url");
                String printer = jsonRequest.optString("printer");

                if (pdfUrl == null || pdfUrl.isEmpty()) {
                    return createErrorResponse(Response.Status.BAD_REQUEST, "URL del PDF no proporcionada");
                }
                if (printer == null || printer.isEmpty()) {
                    return createErrorResponse(Response.Status.BAD_REQUEST, "Parámetro 'printer' no proporcionado");
                }

                File downloadedFile = downloadPDF(pdfUrl);
                if (downloadedFile == null) {
                    return createErrorResponse(Response.Status.INTERNAL_ERROR, "No se pudo descargar el PDF");
                }

                // Determinar si es IP (red) o nombre de impresora USB
                if (isIPAddress(printer)) {
                    // Impresión por red
                    Log.d(VAR, "Imprimiendo PDF descargado por red en IP: " + printer);
                    printPDF(printer, downloadedFile, 2.5f);
                    responseJson.put("message", "PDF descargado e impresión iniciada en " + printer);
                } else {
                    // Buscar impresora USB por nombre
                    UsbDevice usbDevice = findUsbPrinterByName(printer);
                    if (usbDevice == null) {
                        cleanupFile(downloadedFile);
                        return createErrorResponse(Response.Status.BAD_REQUEST, "Impresora USB '" + printer + "' no encontrada");
                    }
                    
                    Log.d(VAR, "Imprimiendo PDF descargado por USB en: " + printer);
                    printPDFViaUSB(usbDevice, downloadedFile, 2.5f);
                    responseJson.put("message", "PDF descargado e impresión USB iniciada en " + printer);
                }

                responseJson.put("status", "success");
                return createCORSResponse(Response.Status.OK, "application/json", responseJson.toString());

            } catch (Exception e) {
                Log.e(VAR, "Error en /printPDF: " + e.getMessage(), e);
                return createErrorResponse(Response.Status.INTERNAL_ERROR, "Error interno del servidor: " + e.getMessage());
            }
        }

        private Response handleListPrinters(IHTTPSession session) {
            try {
                Log.d(VAR, "Iniciando listado de impresoras...");
                
                org.json.JSONArray printersArray = new org.json.JSONArray();
                
                // Verificar si USB Manager está disponible
                if (usbManager == null) {
                    Log.e(VAR, "USB Manager no está inicializado");
                    JSONObject responseJson = new JSONObject();
                    responseJson.put("printers", printersArray);
                    responseJson.put("debug", "USB Manager no disponible");
                    return createCORSResponse(Response.Status.OK, "application/json", responseJson.toString());
                }
                
                // Obtener todos los dispositivos USB
                Map<String, UsbDevice> deviceList = usbManager.getDeviceList();
                Log.d(VAR, "Total de dispositivos USB encontrados: " + deviceList.size());
                
                List<UsbDevice> usbPrinters = getUsbPrinters();
                Log.d(VAR, "Impresoras USB encontradas: " + usbPrinters.size());

                // Agregar impresoras USB
                for (UsbDevice device : usbPrinters) {
                    String printerName = getUsbPrinterDisplayName(device);
                    Log.d(VAR, "Agregando impresora: " + printerName);
                    printersArray.put(printerName);
                }

                JSONObject responseJson = new JSONObject();
                responseJson.put("printers", printersArray);
                responseJson.put("total_usb_devices", deviceList.size());
                responseJson.put("usb_printers_found", usbPrinters.size());

                Log.d(VAR, "Respuesta de impresoras: " + responseJson.toString());
                return createCORSResponse(Response.Status.OK, "application/json", responseJson.toString());

            } catch (Exception e) {
                Log.e(VAR, "Error listando impresoras: " + e.getMessage(), e);
                
                // Crear respuesta de error con información de debug
                try {
                    JSONObject errorResponse = new JSONObject();
                    errorResponse.put("printers", new org.json.JSONArray());
                    errorResponse.put("error", e.getMessage());
                    errorResponse.put("status", "error");
                    return createCORSResponse(Response.Status.INTERNAL_ERROR, "application/json", errorResponse.toString());
                } catch (JSONException je) {
                    return createErrorResponse(Response.Status.INTERNAL_ERROR, "Error listando impresoras: " + e.getMessage());
                }
            }
        }

        private Response createCORSResponse(Response.IStatus status, String mimeType, String message) {
            Response response = newFixedLengthResponse(status, mimeType, message);
            response.addHeader("Access-Control-Allow-Origin", "*");
            response.addHeader("Access-Control-Allow-Methods", "POST, GET, OPTIONS");
            response.addHeader("Access-Control-Allow-Headers", "Content-Type, X-Requested-With");
            return response;
        }

        private Response createErrorResponse(Response.IStatus status, String errorMessage) {
            JSONObject errorJson = new JSONObject();
            try {
                errorJson.put("error", errorMessage);
                errorJson.put("status", "error");
            } catch (JSONException e) {
                Log.e(VAR, "Error creando JSON de error", e);
            }
            return createCORSResponse(status, "application/json", errorJson.toString());
        }
    }
}