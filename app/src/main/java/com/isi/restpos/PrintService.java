package com.isi.restpos;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.pdf.PdfRenderer;
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

    private SimpleHttpServer simpleHttpServer;
    private Handler mainHandler;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(VAR, "Iniciando servicio de impresión...");
        mainHandler = new Handler(Looper.getMainLooper());
        createNotificationChannel();
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
                String printerIP = session.getParms().get("printer");

                if (printerIP == null || printerIP.isEmpty()) {
                    return createErrorResponse(Response.Status.BAD_REQUEST, "IP de impresora no proporcionada");
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

                Log.d(VAR, "Archivo movido a una ubicación segura: " + permanentFile.getAbsolutePath());
                printPDF(printerIP, permanentFile, 3f);

                responseJson.put("message", "Procesamiento iniciado en " + printerIP);
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
                String printerIP = jsonRequest.optString("printer");

                if (printerIP == null || printerIP.isEmpty()) {
                    return createErrorResponse(Response.Status.BAD_REQUEST, "IP de impresora no proporcionada");
                }
                if (pdfUrl == null || pdfUrl.isEmpty()) {
                    return createErrorResponse(Response.Status.BAD_REQUEST, "URL del PDF no proporcionada");
                }

                File downloadedFile = downloadPDF(pdfUrl);
                if (downloadedFile == null) {
                    return createErrorResponse(Response.Status.INTERNAL_ERROR, "No se pudo descargar el PDF");
                }

                printPDF(printerIP, downloadedFile, 2.5f);

                responseJson.put("message", "PDF descargado e impresión iniciada en " + printerIP);
                responseJson.put("status", "success");
                return createCORSResponse(Response.Status.OK, "application/json", responseJson.toString());

            } catch (Exception e) {
                Log.e(VAR, "Error en /printPDF: " + e.getMessage(), e);
                return createErrorResponse(Response.Status.INTERNAL_ERROR, "Error interno del servidor: " + e.getMessage());
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