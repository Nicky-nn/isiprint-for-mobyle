# Documentación: Servicio de Impresión Android (ISIPRINT for mobiles)

Este documento proporciona una visión completa del `PrintService`, una aplicación Android diseñada para actuar como un puente entre solicitudes HTTP y impresoras térmicas, tanto de red (IP) como USB.

-----

## 🚀 Guía del Consumidor (API)

Esta guía explica cómo interactuar con el servicio de impresión desde cualquier cliente HTTP (una aplicación web, un script, Postman, etc.).

### URL Base del Servicio

El servicio escucha en el puerto `7777` del dispositivo Android. La URL base será:

`http://<IP_DEL_DISPOSITIVO_ANDROID>:7777`

### Parámetro `printer`

Todas las solicitudes de impresión requieren un parámetro `printer`. Este parámetro identifica la impresora de destino y puede ser de dos tipos:

1.  🌐 **Impresora de Red:** La dirección IP de la impresora. Ejemplo: `"192.168.1.150"`.
2.  🔌 **Impresora USB:** El nombre de la impresora tal como lo reporta el endpoint de listado. Ejemplo: `"TM-T20II"`, `"USB_Printer_1234_5678"`.

### Endpoints Disponibles

#### 1\. Listar Impresoras Disponibles

Permite descubrir las impresoras USB conectadas al dispositivo.

  * **Endpoint:** `GET /printers`
  * **Método:** `GET`
  * **Descripción:** Devuelve una lista en formato JSON con los nombres de todas las impresoras USB detectadas. Estos nombres son los que se deben usar en el parámetro `printer` para las impresiones USB.
  * **Respuesta Exitosa (200 OK):**
    ```json
    {
      "printers": [
        "TM-T20II",
        "RP58_Printer"
      ],
      "total_usb_devices": 5,
      "usb_printers_found": 2
    }
    ```

#### 2\. Imprimir PDF desde URL

Descarga un archivo PDF desde una URL pública y lo envía a la impresora especificada.

  * **Endpoint:** `POST /printPDF`
  * **Método:** `POST`
  * **Cuerpo (Body):** `application/json`
  * **Descripción:** El servicio descarga el PDF y lo procesa para su impresión.
  * **Parámetros JSON:**
      * `pdf_url` (string, requerido): La URL pública del archivo PDF a imprimir.
      * `printer` (string, requerido): La IP o el nombre de la impresora de destino.
  * **Ejemplo de solicitud con `curl`:**
    ```bash
    curl -X POST http://192.168.1.10:7777/printPDF \
    -H "Content-Type: application/json" \
    -d '{
          "pdf_url": "http://www.africau.edu/images/default/sample.pdf",
          "printer": "192.168.1.150"
        }'
    ```
  * **Respuesta Exitosa (200 OK):**
    ```json
    {
      "status": "success",
      "message": "PDF descargado e impresión iniciada en 192.168.1.150"
    }
    ```
  * **Respuesta de Error (400 Bad Request):**
    ```json
    {
      "error": "URL del PDF no proporcionada",
      "status": "error"
    }
    ```

#### 3\. Imprimir PDF subiendo un archivo

Envía un archivo PDF directamente en el cuerpo de la solicitud para ser impreso.

  * **Endpoint:** `POST /print`
  * **Método:** `POST`
  * **Cuerpo (Body):** `multipart/form-data`
  * **Descripción:** Ideal para aplicaciones web donde el usuario selecciona un archivo local.
  * **Parámetros del formulario:**
      * `printer` (string, requerido): La IP o el nombre de la impresora de destino.
      * `file` (file, requerido): El archivo PDF a imprimir.
  * **Ejemplo de solicitud con `curl`:**
    ```bash
    curl -X POST http://192.168.1.10:7777/print \
    -F "printer=TM-T20II" \
    -F "file=@/ruta/a/mi/recibo.pdf"
    ```
  * **Respuesta Exitosa (200 OK):**
    ```json
    {
      "status": "success",
      "message": "Procesamiento USB iniciado en TM-T20II"
    }
    ```
  * **Respuesta de Error (400 Bad Request):**
    ```json
    {
      "error": "Impresora USB 'TM-T20II_Inexistente' no encontrada",
      "status": "error"
    }
    ```