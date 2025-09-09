# 📜 Documentación Técnica Avanzada: PrintService

Esta documentación proporciona un análisis técnico profundo del `PrintService`, destinado a desarrolladores que necesiten mantener, extender o depurar el servicio. Se asume un conocimiento previo de desarrollo en Android y de los principios de comunicación de red.

### 1\. Visión General de la Arquitectura y Decisiones de Diseño

El `PrintService` está diseñado como un microservicio desacoplado que se ejecuta en un dispositivo Android, exponiendo una API RESTful simple para interactuar con hardware de impresión local (USB y Red).

**Componentes Principales:**

  * **Contenedor del Servicio (`PrintService.java`):** Un `Foreground Service` de Android.
  * **Servidor HTTP (`SimpleHttpServer`):** Una implementación basada en NanoHTTPD.
  * **Procesador de Impresión (`escpos-coffee`):** Librería para la abstracción de comandos ESC/POS.
  * **Motor de Renderizado de PDF (`PdfRenderer`):** Componente nativo de Android.
  * **Controladores de Hardware:** Lógica personalizada para la comunicación por Red (Sockets TCP) y USB (Android USB Host API).

#### Decisiones Clave de Diseño:

1.  **¿Por qué un `Foreground Service`?**

      * **Persistencia:** Los servicios en segundo plano (`Background Service`) tienen altas probabilidades de ser terminados por el sistema operativo para ahorrar batería o memoria. Un `Foreground Service` tiene la máxima prioridad, es casi inmune a la terminación y garantiza la disponibilidad constante del servidor HTTP.
      * **Transparencia:** La notificación persistente requerida por un servicio en primer plano informa claramente al usuario que una tarea de larga duración está activa, lo cual es una buena práctica de UX.

2.  **¿Por qué NanoHTTPD?**

      * **Ligereza:** Es una librería de un solo archivo, con una huella de memoria mínima y sin dependencias transitivas. Esto es ideal para entornos de recursos limitados como dispositivos Android dedicados.
      * **Simplicidad:** Su API es directa y suficiente para las necesidades del proyecto (rutas simples, manejo de `multipart/form-data`), evitando la sobrecarga de servidores más complejos como Jetty o Ktor.

3.  **Asincronismo Total:**

      * Todas las operaciones que involucran I/O (red, disco, USB) se ejecutan fuera del hilo principal de Android (`UI Thread`). Esto es **crítico** para evitar errores de "Aplicación No Responde" (ANR). La estrategia utilizada es la creación de nuevos hilos (`new Thread(...)`) para cada tarea de impresión o descarga.

### 2\. Análisis Detallado de Componentes

#### a. El Servidor HTTP: `SimpleHttpServer`

El servidor es el punto de entrada. Su lógica principal reside en el método `serve()`.

  * **Enrutamiento (Routing):** Se implementa un enrutador simple basado en `if/else` que inspecciona el método HTTP y la URI.

    ```java
    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        String method = session.getMethod().toString();
        // ...
        if ("POST".equals(method) && "/print".equals(uri)) {
            return handlePrintFileUpload(session);
        } else if (("GET".equals(method) || "HEAD".equals(method)) && "/printers".equals(uri)) {
            return handleListPrinters(session);
        }
        // ...
    }
    ```

  * **Manejo de CORS (Cross-Origin Resource Sharing):** Para que una aplicación web (servida desde otro origen) pueda llamar a esta API, el servidor debe incluir cabeceras CORS. El método `createCORSResponse` se encarga de esto.

    ```java
    private Response createCORSResponse(Response.IStatus status, String mimeType, String message) {
        Response response = newFixedLengthResponse(status, mimeType, message);
        response.addHeader("Access-Control-Allow-Origin", "*"); // Permite cualquier origen
        response.addHeader("Access-Control-Allow-Methods", "POST, GET, OPTIONS");
        response.addHeader("Access-Control-Allow-Headers", "Content-Type, X-Requested-With");
        return response;
    }
    ```

    **Nota:** `Access-Control-Allow-Origin: *` es permisivo. En un entorno de producción estricto, debería ser reemplazado por el dominio específico del cliente web.

#### b. Módulo de Impresión USB (El Núcleo de la Lógica de Hardware)

Esta es la sección más compleja, ya que interactúa directamente con la API de bajo nivel de Android USB Host.

1.  **Descubrimiento de Dispositivos (`getUsbPrinters`):**
    El código no solo busca dispositivos de clase `USB_CLASS_PRINTER`, sino que también itera las *interfaces* de cada dispositivo. Esto es más robusto porque un dispositivo multifunción podría no tener la clase de impresora a nivel de dispositivo, pero sí en una de sus interfaces.

    ```java
    // Fragmento de isUsbPrinter(device)
    for (int i = 0; i < device.getInterfaceCount(); i++) {
        UsbInterface usbInterface = device.getInterface(i);
        if (usbInterface.getInterfaceClass() == UsbConstants.USB_CLASS_PRINTER) {
            Log.d(VAR, "Dispositivo '" + device.getDeviceName() + "' tiene una interfaz de impresora.");
            return true;
        }
    }
    ```

2.  **Comunicación y el `UsbOutputStream` (Patrón Adaptador):**
    La librería `escpos-coffee` está diseñada para escribir en un `java.io.OutputStream`. Sin embargo, la API de Android USB Host no proporciona un `OutputStream` directamente; en su lugar, ofrece el método `connection.bulkTransfer(...)` que envía `byte[]`.

    Para resolver esta incompatibilidad, se creó la clase `UsbOutputStream`. Esta clase implementa el **patrón de diseño Adaptador**. "Adapta" la interfaz de `connection.bulkTransfer` a la interfaz `OutputStream` que la librería espera.

    ```java
    private static class UsbOutputStream extends java.io.OutputStream {
        private final UsbDeviceConnection connection;
        private final UsbEndpoint endpoint;
        private final int timeout = 1000;

        // ... constructor ...

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            // ... validaciones ...
            byte[] data = new byte[length];
            System.arraycopy(bytes, offset, data, 0, length);
            
            // Aquí ocurre la magia: la llamada de bajo nivel a la API de Android
            int result = connection.bulkTransfer(endpoint, data, data.length, timeout);
            if (result < 0) {
                throw new IOException("USB bulk transfer failed with result: " + result);
            }
        }
    }
    ```

    Cuando `escpos-coffee` llama a `outputStream.write(commandBytes)`, en realidad está invocando nuestro método `write` personalizado, que a su vez envía los datos a través de USB.

#### c. Procesamiento de PDF y Optimización de Memoria

1.  **Renderizado y Escalado:**
    Las impresoras térmicas tienen una resolución fija (por ejemplo, 576 puntos para papel de 80mm). Un PDF es un documento vectorial que debe ser rasterizado (convertido a imagen) a la resolución correcta. El parámetro `scaleFactor` es crucial para esto. Un valor incorrecto resultará en una imagen demasiado grande (recortada) o demasiado pequeña (desperdicio de papel).
    `int scaledWidth = (int) (page.getWidth() * scaleFactor);`

2.  **Optimización de Memoria y Color (`convertToRGB565`):**
    `PdfRenderer` crea `Bitmap` en formato `ARGB_8888` (32 bits por píxel). Esto es ineficiente para la impresión térmica, que es monocromática.

      * **Paso 1:** Se convierte el `Bitmap` a `RGB_565` (16 bits por píxel). Esto reduce el uso de memoria del `Bitmap` a la mitad sin perder la información de color necesaria para el siguiente paso.
      * **Paso 2:** La librería `escpos-coffee`, a través de `BitonalThreshold`, convierte la imagen `RGB_565` a un formato bitonal (blanco y negro) antes de generar los comandos de impresión.
      * **Paso 3:** Se llama a `bitmap.recycle()` explícitamente después de usar cada `Bitmap` de página. Esto le indica al recolector de basura que la memoria nativa del `Bitmap` puede ser liberada inmediatamente, lo cual es vital al procesar PDFs de varias páginas para evitar `OutOfMemoryError`.

### 3\. Flujo de Datos Completo (Ejemplo: `POST /printPDF`)

1.  **Cliente → Servidor:** `POST http://192.168.1.10:7777/printPDF` con cuerpo JSON `{"pdf_url": "...", "printer": "TM-T20II"}`.
2.  **`SimpleHttpServer.serve()`:** Enruta la solicitud a `handlePrintPdfUrl()`.
3.  **Descarga:** Se invoca `downloadPDF()`, que abre una `HttpURLConnection` a la `pdf_url` y guarda el stream de bytes en un archivo temporal en el almacenamiento interno de la app (ej: `/data/data/com.isi.restpos/files/download_1678886400000.pdf`).
4.  **Selección de Ruta de Impresión:** `isIPAddress("TM-T20II")` devuelve `false`. El flujo se dirige a la impresión USB.
5.  **Búsqueda de Dispositivo USB:** `findUsbPrinterByName("TM-T20II")` itera sobre los dispositivos conectados y devuelve el objeto `UsbDevice` correspondiente.
6.  **Lanzamiento del Hilo de Impresión:** Se llama a `printPDFViaUSB(usbDevice, file, 2.5f)`. Esta operación se ejecuta en un nuevo hilo.
7.  **Rasterización:** Dentro del hilo, `convertPDFToImages()` abre el archivo PDF, y para cada página:
      * Renderiza la página a un `Bitmap` `ARGB_8888`.
      * Convierte ese `Bitmap` a `RGB_565`, reduciendo su huella de memoria.
      * Añade el `Bitmap` optimizado a una `List<Bitmap>`.
8.  **Conexión USB:**
      * `usbManager.openDevice()` establece la conexión.
      * Se busca el `UsbInterface` y el `UsbEndpoint` de salida (`USB_DIR_OUT`) de tipo `USB_ENDPOINT_XFER_BULK`.
      * `connection.claimInterface()` obtiene control exclusivo sobre la interfaz de la impresora.
9.  **Impresión:**
      * Se crea `UsbOutputStream` y luego `EscPos`.
      * Se itera sobre la lista de `Bitmap`s. Para cada uno:
          * Se envuelve en `EscPosImage`.
          * `escpos.write(imageWrapper, escposImage)` traduce la imagen a una secuencia de bytes de comandos ESC/POS.
          * Estos bytes se escriben en el `UsbOutputStream`, que los envía al `endpoint` USB a través de `bulkTransfer`.
10. **Finalización:**
      * Se envían los comandos `feed` y `cut`.
      * `connection.releaseInterface()` y `connection.close()` liberan el dispositivo.
      * `cleanupFile()` elimina el archivo PDF temporal.
11. **Respuesta al Cliente:** El método `handlePrintPdfUrl` (que no esperó a que la impresión terminara) ya ha devuelto una respuesta `200 OK` con un mensaje de "éxito". El cliente sabe que la tarea fue aceptada, pero la impresión ocurre de forma asíncrona.

### 4\. Troubleshooting y Consideraciones Avanzadas

  * **Permisos USB:** Si la aplicación no tiene permiso, la llamada `usbManager.openDevice()` fallará. La primera vez que se intente imprimir en un dispositivo nuevo, se lanzará una solicitud de permiso al usuario. Si el usuario la niega, la impresión no funcionará hasta que se conceda el permiso (normalmente, desconectando y reconectando el dispositivo para que vuelva a aparecer el diálogo).
  * **Calidad de Impresión:** La calidad depende de dos factores principales: `scaleFactor` y el valor del `BitonalThreshold(127)`. Si las impresiones salen demasiado oscuras o claras, ajustar el umbral (0-255) puede mejorar significativamente el resultado.
  * **Gestión de Concurrencia:** El diseño actual procesa una solicitud de impresión a la vez por impresora de manera efectiva, pero si llegan múltiples solicitudes simultáneamente, se crearán múltiples hilos. Esto podría llevar a una contención de recursos. Para un sistema de alta carga, se debería implementar una cola de trabajos (`BlockingQueue`) para serializar las tareas de impresión de manera controlada.