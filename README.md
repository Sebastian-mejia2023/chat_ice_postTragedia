# Plataforma Distribuida de Chat Multimedia y Voz UDP con ZeroC Ice

> **Universidad Icesi**  
> **Facultad de Ingeniería, Diseño y Ciencias Aplicadas**  
> **Departamento de Computación y Sistemas Inteligentes**  
> **Curso:** Computación en Internet I (09810 - TIC) — Semestre 2026-2  
> **Taller Evaluativo Unidad 2:** Sistemas Distribuidos, Middleware y Comunicación Multimedia  
> **Política IAG:** Nivel 3 (Colaboración Asistida)  
> **Calificación Objetivo:** 5.0 / 5.0  

---

### Integrante
| Nombre Completo | Código Estudiantil |
|---|---|
| Sebastián Mejía Trujillo | A00410557 |

---

## 1. Arquitectura del Sistema

El sistema implementa una arquitectura distribuida híbrida de **dos canales ortogonales**, combinando las garantías de tipado estricto y fiabilidad de **ZeroC Ice 3.7 sobre TCP** con la baja latencia del streaming de voz sobre datagramas **UDP puros**:

```mermaid
flowchart TD
    subgraph Clientes["Nodos Cliente (Java)"]
        UI["CLI Interactiva (CommandLineUI)"]
        CB["ChatCallbackI (Callback Servant)"]
        FS["FileSender / FileReceiver"]
        AudioMod["AudioCapture / AudioPlayer / AudioMixer"]
    end

    subgraph CanalIce["Canal de Control, Mensajería y Archivos (TCP / Ice 3.7)"]
        ICE["Invocaciones RPC y Callbacks Asíncronos"]
    end

    subgraph CanalUDP["Canal de Voz en Tiempo Real (UDP Puro)"]
        UDPDirect["P2P Directo 1 a 1"]
        UDPRelay["AudioRelay Centralizado (SFU)"]
    end

    subgraph ServidorCentral["Servidor Central Ice y AudioRelay"]
        ChatSvc["ChatServiceI (Servant Ice 3.7)"]
        UserReg["UserRegistry (ConcurrentHashMap)"]
        RoomMgr["RoomManager (Salas y Membresías)"]
        CallMgr["CallManager (Estado de Llamadas)"]
        FileRelay["FileRelayService (Chunking Relay 48 KB)"]
        Reaper["ClientReaper (Detección de Caídas)"]
        RelaySFU["AudioRelay (DatagramSocket en puerto 10005)"]
    end

    UI -->|RPC Tipadas| ICE
    ICE -->|Peticiones TCP| ChatSvc
    CB <---|Notificaciones Asíncronas (Callbacks)| ICE
    FS <-->|Chunks 48 KB| ICE
    ChatSvc --> UserReg & RoomMgr & CallMgr & FileRelay & Reaper
    AudioMod <-->|Datagramas Voz 1 a 1| UDPDirect
    AudioMod <-->|Datagramas Voz Grupal (HELLO/AUDIO/BYE)| UDPRelay
    UDPRelay <--> RelaySFU
```

### Canales de Comunicación
1. **Canal de Control, Señalización, Chat y Archivos (ZeroC Ice sobre TCP):**
   - **Sesión y Presencia:** Autenticación por nickname único (`putIfAbsent`) y detección de desconexiones limpias o abruptas mediante cierre de conexión y ping periódico (`setCloseCallback` + `ClientReaper`).
   - **Mensajería Privada y Grupal:** Despacho asíncrono (`*Async(...)`) con manejo de fallos y sin reenviar el mensaje al emisor.
   - **Transferencia Multimedia (Chunking):** Secuencias binarias `sequence<byte>` fragmentadas en bloques de **48 KB** (`CHUNK_SIZE`), respetando `Ice.MessageSizeMax = 1024 KB`. Reconstrucción en disco con `RandomAccessFile`, sanitización contra *Path Traversal* y verificación de integridad mediante hash **SHA-256**.
   - **Señalización de Voz:** Solicitud, timbrado, aceptación, rechazo y finalización de llamadas mediante contratos Slice.

2. **Canal de Streaming de Audio en Tiempo Real (UDP Puro):**
   - Captura y reproducción continua mediante la API `javax.sound.sampled` de Java en formato **PCM 16 kHz, 16 bit, mono, signed, little-endian**.
   - Empaquetamiento en tramas de 20 ms = 640 bytes de payload de audio, encapsulados en datagramas `DatagramPacket` con cabecera binaria estructurada (`PacketCodec`).
   - **Llamadas 1 a 1:** Comunicación directa Peer-to-Peer sobre UDP entre ambos clientes.
   - **Conferencias Grupales (AudioRelay SFU Ligero):** El servidor recibe los flujos de voz de la sala y los retransmite a los demás integrantes, **suprimiendo estrictamente el eco hacia el emisor**. En el cliente, `AudioMixer` realiza la mezcla aditiva de múltiples voces con protección de saturación aritmética (*clipping*).

---

## 2. Requisitos y Preparación del Entorno

- **Java Development Kit (JDK):** Versión 17 o superior.
- **ZeroC Ice:** 3.7.10 (compilador Slice `slice2java` en PATH).
- **Gradle:** Wrapper incluido en el repositorio (`./gradlew` para Linux/macOS y `gradlew.bat` para Windows).

Verificación de herramientas:
```bash
java -version
slice2java --version
./gradlew test
```

---

## 3. Instructivo Paso a Paso de Uso

### Paso 1: Iniciar el Servidor
En una terminal principal:
```bash
./gradlew runServer
```
El servidor quedará a la escucha en:
- **Canal Ice (TCP):** puerto `10000` (`tcp -h 0.0.0.0 -p 10000`).
- **AudioRelay UDP:** puerto `10005` (`0.0.0.0:10005`).

---

### Paso 2: Conectar Clientes

#### **Opción A: Prueba Local (Misma máquina)**
Abre dos o más terminales adicionales:
```bash
# Terminal 2 (Cliente 1)
./gradlew runClient

# Terminal 3 (Cliente 2)
./gradlew runClient
```

#### **Opción B: Prueba en Red Local (Varios computadores en LAN / WiFi)**
1. En la máquina del servidor, consulta tu IP local con `ip a` (ejemplo: `192.168.1.50`).
2. En cada máquina cliente, ejecuta pasando la IP del servidor:
   ```bash
   ./gradlew :client:run --args="192.168.1.50"
   ```
   Si prefieres no pasar argumentos, cambia `Chat.Server.Host` en `client/config.client` por la IP del servidor.

---

### Paso 3: Inicio de Sesión y Presencia
En la terminal del Cliente 1:
```text
/login alice
```
En la terminal del Cliente 2:
```text
/login bob
```
Ambos clientes verán de inmediato la notificación en tiempo real:
```text
[SISTEMA] Usuario conectado: bob
```
Para consultar todos los usuarios activos:
```text
/users
```

---

### Paso 4: Mensajería Privada (1 a 1)
Desde la terminal de Alice, envía un mensaje privado a Bob:
```text
/msg bob ¡Hola Bob! ¿Cómo estás?
```
Bob recibirá en su consola:
```text
[14:32:05] [DIRECTO de alice] ¡Hola Bob! ¿Cómo estás?
```

---

### Paso 5: Creación de Salas Grupales y Broadcast
1. Alice crea una sala de chat:
   ```text
   /create proyectos
   ```
2. Bob consulta las salas disponibles y se une a ella:
   ```text
   /rooms
   /join proyectos
   ```
3. Alice y Bob chatean en la sala:
   ```text
   /room proyectos Bienvenidos al canal del equipo
   ```
   *El mensaje se entrega a todos los miembros de la sala sin duplicarse al emisor.*

---

### Paso 6: Transferencia de Archivos Multimedia (Chunking sobre Ice)
Puedes transferir imágenes (`.png`, `.jpg`), documentos (`.pdf`, `.txt`) o notas de audio (`.wav`) a un usuario o a una sala completa.

- **Envío a usuario privado:**
  ```text
  /sendfile bob /ruta/a/mi_imagen.png
  ```
- **Envío a una sala grupal:**
  ```text
  /sendfile #proyectos /ruta/a/documento.pdf
  ```

El receptor reconstruirá el archivo fragmentado en bloques de 48 KB dentro del directorio `downloads/` y validará automáticamente el hash SHA-256:
```text
[ARCHIVO] Recibiendo archivo 'documento.pdf' (1250000 bytes) de sala #proyectos
[ARCHIVO] Archivo completado exitosamente: .../downloads/documento.pdf [SHA-256 VERIFICADO]
```

El servidor valida que el nombre sea un nombre base (sin rutas), que el tamaño total no supere **200 MB** y que cada chunk no pase de **64 KB**, con índices y offsets coherentes. El receptor tolera chunks duplicados o fuera de orden, no sobrescribe archivos existentes (sufijo `_1`), elimina el archivo si el SHA-256 no coincide y descarta transferencias abandonadas tras 2 minutos de inactividad. Probado con un PDF de 191569 B y binarios de 2 MB y 20 MB, tanto directos como a sala, con hash idéntico.

---

### Paso 7: Llamadas de Voz Directas 1 a 1 (UDP)
1. Alice inicia la llamada a Bob:
   ```text
   /call bob
   ```
2. Bob recibe la notificación y contesta:
   ```text
   [LLAMADA] ¡Llamada entrante de alice! Escribe /accept para contestar o /reject para rechazar.
   /accept
   ```
3. **¡Audio en vivo!** Ambos usuarios pueden hablar a través de su micrófono y escuchar por sus audífonos/altavoces mediante flujo UDP continuo.
4. Para colgar en cualquier momento:
   ```text
   /hangup
   ```

---

### Paso 8: Conferencias de Voz Grupal y Mute (AudioRelay)
1. Únete primero a la sala de texto (si aún no eres miembro) y luego a su llamada de voz:
   ```text
   /join proyectos
   /groupcall proyectos
   ```
2. El servidor enlaza a los participantes a través del `AudioRelay` (puerto UDP 10005) suprimiendo el eco de red.
3. **Control de Micrófono (Mute / Unmute):**
   - Para silenciarte sin colgar:
     ```text
     /mute
     ```
   - Para reactivar el micrófono:
     ```text
     /unmute
     ```
4. Para abandonar la conferencia grupal (o cualquier llamada activa):
   ```text
   /leavecall
   ```

> [!TIP]
> **Consejo para pruebas en la misma máquina:**  
> Si ejecutas dos clientes en el mismo equipo, utiliza audífonos o silencia uno con `/mute` para evitar acople acústico ambiental entre el micrófono y los parlantes.

---

## 4. Manual de Usuario y Comandos

A continuación se detalla el catálogo completo de comandos disponibles en la interfaz CLI:

| Comando | Sintaxis | Descripción |
|---|---|---|
| `/login` | `/login <nickname>` | Autentica e inicia sesión con un identificador alfanumérico único. |
| `/users` | `/users` | Lista todos los usuarios conectados actualmente en la plataforma. |
| `/msg` | `/msg <usuario> <texto>` | Envía un mensaje privado directo (1 a 1) al usuario indicado. |
| `/create` | `/create <sala>` | Crea una nueva sala de conversación grupal. |
| `/rooms` | `/rooms` | Lista todas las salas de chat creadas y disponibles. |
| `/join` | `/join <sala>` | Se suscribe como miembro de una sala de chat existente. |
| `/leave` | `/leave <sala>` | Abandona la membresía de una sala de chat. |
| `/room` (o `/r`) | `/room <sala> <texto>` | Difunde un mensaje a todos los miembros de la sala (sin eco al emisor). |
| `/sendfile` | `/sendfile <user\|#sala> <path>` | Transfiere un archivo multimedia en fragmentos de 48 KB sobre Ice. |
| `/call` | `/call <usuario>` | Solicita una llamada de voz 1 a 1 sobre datagramas UDP. |
| `/accept` | `/accept` | Acepta una llamada de voz entrante. |
| `/reject` | `/reject` | Rechaza una llamada de voz entrante. |
| `/hangup` | `/hangup` | Finaliza la llamada de voz activa (1 a 1 o grupal) y libera sockets y líneas de audio. |
| `/groupcall` | `/groupcall <sala>` | Se une a la conferencia de voz de la sala mediante el AudioRelay. |
| `/leavecall` | `/leavecall` | Abandona la llamada activa (1 a 1 o grupal); en una llamada grupal no interrumpe a los demás participantes. |
| `/mute` | `/mute` | Silencia el micrófono (deja de transmitir paquetes UDP). |
| `/unmute` | `/unmute` | Reactiva la transmisión del micrófono. |
| `/help` | `/help` | Muestra la ayuda interactiva en consola. |
| `/exit` | `/exit` | Cierra sesión de forma ordenada y finaliza el cliente. |

---

## 5. Cuestionario de Análisis Conceptual

A continuación se presentan las respuestas formales a las cuatro preguntas de ingeniería planteadas en la **Sección 4** de la guía del taller evaluativo:

### Pregunta 1: Arquitectura Híbrida TCP/UDP
> *¿Cuáles son las razones técnicas fundamentales para diseñar una arquitectura híbrida donde el control, el chat de texto y la transferencia de archivos corren sobre ZeroC Ice (TCP), mientras que el flujo de voz corre directamente sobre UDP? ¿Qué consecuencias acústicas y de rendimiento ocurrirían si la voz se transmitiera sobre Ice/TCP bajo condiciones de fluctuación de red (jitter) o pérdida de paquetes?*

**Respuesta:**  
La razón fundamental radica en la discrepancia de requisitos de Calidad de Servicio (QoS) entre ambos tipos de tráfico:
1. **Control, Mensajería y Archivos (Fiabilidad Estricta):** Requieren entrega ordenada, libre de errores y sin pérdidas. Un byte alterado o perdido en un archivo o mensaje invalida la información. TCP proporciona estas garantías mediante el protocolo de ventana deslizante, acuses de recibo (ACK) y retransmisiones automáticas (ARQ).
2. **Voz en Tiempo Real (Baja Latencia y Fluidez Acústica):** El oído humano es altamente sensible a la latencia interactiva (el estándar ITU-T G.114 exige retardos unidireccionales $\le 150\text{ ms}$ para una conversación fluida), pero es tolerante a pérdidas menores de paquetes de audio aislados (1–3%).

**Consecuencias de transmitir voz sobre TCP:**  
Bajo condiciones de pérdida de paquetes o fluctuación (*jitter*), el mecanismo de retransmisión y control de congestión de TCP detiene la entrega de los paquetes posteriores en el buffer del receptor hasta que el paquete perdido sea retransmitido y confirmado con éxito (**bloqueo de inicio de línea** o *head-of-line blocking*). Acústicamente, esto produce una **acumulación severa de retardo**, pausas o congelamientos repentinos del audio, seguidos de una ráfaga de reproducción acelerada ("efecto acordeón"), destruyendo por completo la inteligibilidad y la naturaleza interactiva de la llamada. Con UDP, el paquete perdido simplemente se descarta y el flujo continúa en tiempo real sin retrasar las muestras subsiguientes.

---

### Pregunta 2: Manejo de Buffer y Chunking en Ice
> *Si un usuario intenta transferir un video de 80 MB mediante una única llamada RPC pasando toda la secuencia de bytes en un parámetro `sequence<byte>`, ¿qué excepción o comportamiento manifestará ZeroC Ice por defecto? Explique cómo la técnica de fragmentación en bloques resuelve este problema y qué parámetros de configuración de Ice intervienen.*

**Respuesta:**  
Por defecto, ZeroC Ice impone un límite de seguridad en el tamaño de los mensajes para prevenir ataques de denegación de servicio por agotamiento de memoria (*Out Of Memory*). Este límite está controlado por la propiedad:
$$\text{Ice.MessageSizeMax} = 1024\text{ KB (1 MB por defecto)}$$
Si un cliente intenta enviar un arreglo de 80 MB en una sola invocación, el serializador de Ice abortará la operación arrojando la excepción:
$$\text{com.zeroc.Ice.MemoryLimitException (subtipo de MarshalException)}$$
tanto en el emisor como en el receptor.

**Solución mediante Chunking:**  
La técnica de fragmentación divide el archivo en bloques sucesivos de tamaño constante y acotado (en nuestra solución, `CHUNK_SIZE = 48 KB`, muy por debajo del megabyte por defecto). Cada bloque viaja encapsulado en una estructura `FileChunk` con metadatos asociados (`transferId`, `chunkIndex`, `totalChunks`, `totalSize`, `sha256`, `data`). El servidor retransmite cada bloque de forma asíncrona hacia el destinatario sin retener el archivo completo en memoria RAM ni en disco. En el receptor, los bloques se escriben en las posiciones correspondientes mediante `RandomAccessFile` y, al recibir el último fragmento, se verifica el hash SHA-256 completo para certificar que no hubo corrupción binaria.

---

### Pregunta 3: Escalabilidad en Llamadas Grupales (Relay vs. Mesh)
> *Analice comparativamente la topología de Audio Relay centralizado frente a la topología Mesh Peer-to-Peer elegida por su equipo. Si una sala cuenta con $N$ participantes activos hablando simultáneamente, ¿cuál es el número total de paquetes UDP que debe emitir cada cliente y cuál es la carga total que soporta la red del servidor en ambas alternativas?*

**Respuesta:**  
Nota: la guía plantea la malla Mesh como topología de referencia y la pregunta asume que el equipo la eligió; en este caso se evaluaron ambas opciones y se implementó Audio Relay centralizado, por lo que la comparación se usa para justificar esa decisión de diseño.

Sea $N$ el número de participantes en la llamada grupal y $f$ la frecuencia de paquetes UDP emitidos por segundo por cada emisor (por ejemplo, con frames de 20 ms, $f = 50\text{ paquetes/s}$):

| Criterio de Análisis | Topología Mesh Peer-to-Peer | Topología Audio Relay Centralizado (SFU) |
|---|:---:|:---:|
| **Paquetes emitidos por cada cliente** | $(N - 1) \cdot f$ | **$1 \cdot f$ (constante)** |
| **Paquetes recibidos por cada cliente** | $(N - 1) \cdot f$ | $(N - 1) \cdot f$ |
| **Carga de entrada en la red del servidor** | $0\text{ paquetes/s}$ | $N \cdot f$ |
| **Carga de salida en la red del servidor** | $0\text{ paquetes/s}$ | $N \cdot (N - 1) \cdot f$ |
| **Tráfico total global en la red** | $N \cdot (N - 1) \cdot f$ | $N \cdot f + N \cdot (N - 1) \cdot f$ |

**Justificación de la elección de Audio Relay (SFU Ligero):**  
En una malla Mesh, el ancho de banda de subida (*uplink*) de cada cliente escala linealmente con $(N - 1)$. En entornos domésticos o de laboratorio universitario con conexiones asimétricas, el uplink de los clientes colapsa rápidamente con 4 o 5 participantes. Con la arquitectura de **Audio Relay centralizado**, cada cliente envía **únicamente un solo flujo de subida ($f$)**, delegando la retransmisión al servidor. Adicionalmente, el relay resuelve de forma transparente los problemas de NAT y enrutamiento entre participantes mediante el registro dinámico con el paquete inicial `HELLO`.

---

### Pregunta 4: Transparencia y Callbacks en Ice
> *Describa el ciclo de vida y la configuración de adaptadores de objetos requerida en el cliente para permitir que el servidor Ice invoque métodos de retorno (callbacks) sobre él. ¿Qué papel juegan los proxies directos vs indirectos en este flujo?*

**Respuesta:**  
Para que el servidor pueda invocar métodos de forma asíncrona sobre el cliente, el cliente debe asumir el rol de servidor de objetos para su propio servant de callback (`ChatCallbackI`).

**Ciclo de Vida y Configuración en el Cliente:**
1. **Creación del Adaptador:** El cliente crea un `ObjectAdapter` local asociado a su comunicador, especificando un endpoint TCP con su IP de red local (LAN) o interfaz disponible:
   ```java
   ObjectAdapter clientAdapter = communicator.createObjectAdapterWithEndpoints("ClientAdapter", "tcp -h " + localIp);
   ```
2. **Registro del Servant:** Se añade la instancia de la clase `ChatCallbackI` al adaptador, asignándole un identificador único (UUID):
   ```java
   ChatCallbackPrx callbackPrx = ChatCallbackPrx.uncheckedCast(
       clientAdapter.add(new ChatCallbackI(...), Util.stringToIdentity(UUID.randomUUID().toString()))
   );
   ```
3. **Activación Obligatoria:** **Antes** de registrarse ante el servidor, se debe invocar `clientAdapter.activate()`. Si el adaptador no está activo al llegar el primer callback desde el servidor, la petición remota quedará congelada o será rechazada.
4. **Registro Remoto:** El cliente invoca `chatService.login(nickname, callbackPrx)`, pasando su proxy tipado al servidor.
5. **Destrucción y Cierre:** Al finalizar la sesión, se destruye el adaptador mediante `clientAdapter.destroy()` liberando el socket TCP local.

**Papel de los Proxies Directos vs. Indirectos:**
- **Proxy Directo:** Contiene explícitamente en su cadena de texto la dirección IP, puerto y protocolo de transporte del endpoint (ej. `uuid:tcp -h 192.168.1.30 -p 45213`). Es el mecanismo empleado en este proyecto, ya que permite al servidor despachar el callback inmediatamente a la dirección física del cliente sin necesidad de intermediarios.
- **Proxy Indirecto:** No incluye direcciones IP ni puertos fijos; únicamente contiene la identidad del objeto y un identificador de adaptador (ej. `uuid@ClientAdapter`). Requiere un servicio de localización de objetos distribuido (**IceGrid**) para resolver dinámicamente la ubicación física del objeto servant en tiempo de ejecución.

---

## 6. Bitácora de Uso Ético de IAG (Nivel 3: Colaboración Asistida)

La Inteligencia Artificial Generativa se utilizó como **apoyo académico**, no como autora del proyecto. Su aporte se centró en la planificación de las fases, la explicación de conceptos de Ice, UDP, concurrencia y audio PCM, y la revisión de errores o alternativas de diseño. El estudiante se encargó de escribir y mantener el código, tomar las decisiones de arquitectura, ejecutar las pruebas y validar cada resultado. Cuando la IAG propuso fragmentos de código o comandos, se tomaron como referencia; se revisaron, adaptaron o descartaron y se validó la versión final. Las sugerencias que no encajaban con el contrato Slice, las restricciones del taller o el comportamiento real de Ice se descartaron; ninguna se incorporó sin revisión.

| Fecha | Fase / Componente | Herramienta | Consulta principal (apoyo) | Código o propuesta de la IAG y adaptación crítica realizada | Lección aprendida |
|---|---|---|---|---|---|
| **2026-10-23** | **Fase 0 y 1:** Setup y contrato Slice | Antigravity (Gemini) | *"¿Cómo organizar el proyecto Gradle multimódulo y qué revisar en el contrato Ice?"* | La IAG ayudó a ordenar la estructura y a repasar dudas del contrato. Al compilar, se detectó que `ice-builder` 1.4.7 no funcionaba con la configuración de Gradle; se actualizó a 1.5.2 y se configuró `iceHome` para `slice2java 3.7.10`. | Las versiones del plugin y del compilador Slice deben verificarse juntas; la compilación real manda sobre la sugerencia. |
| **2026-10-23** | **Fase 2 y 3:** Presencia y salas thread-safe | Antigravity (Gemini) | *"¿Cómo proteger las colecciones del servidor y notificar a los clientes sin bloquear?"* | La IAG permitió contrastar por qué una RPC síncrona dentro de `synchronized` podía congelar el servidor con un cliente caído. Se adoptó `ConcurrentHashMap.newKeySet()` y despacho `*Async(...)` con `whenComplete`, y se validó con 200 hilos concurrentes. | Jamás se deben invocar operaciones de red remotas dentro de un bloque sincronizado: se arriesgan *deadlocks* y degradación por clientes lentos. |
| **2026-10-23** | **Fase 4:** Chunking multimedia | Antigravity (Gemini) | *"¿Por qué falla un `sequence<byte>` grande y cómo reconstruir chunks desordenados?"* | Se consultó cómo funciona `Ice.MessageSizeMax` y cómo escribir bloques por offset. Se implementó `RandomAccessFile` con offsets por `chunkIndex`, verificación SHA-256, sanitización de nombres y pruebas de duplicados, desorden y *Path Traversal*. | En transferencias distribuidas, el orden de llegada de los fragmentos no está garantizado; el ensamblado posicional por chunks resuelve la integridad. |
| **2026-10-23** | **Fase 5 y 6:** Voz UDP y AudioRelay | Antigravity (Gemini) | *"¿Cómo estructurar el protocolo UDP de voz y mezclar PCM sin distorsión?"* | Se usó para aclarar el formato de los datagramas y el riesgo de desbordamiento PCM. Se definió HELLO/AUDIO/BYE, el relay sin eco y `AudioMixer` con saturación aritmética; se probó con *fakes* y con clientes reales. | En PCM de 16 bits, el desbordamiento al sumar voces genera ruidos de clic; la saturación en `Short.MAX_VALUE` / `Short.MIN_VALUE` evita la distorsión. |
| **2026-10-24** | **Corrección de `/leavecall`** | OpenCode (`deepseek-v4.1-flash-free`) | *"Ayúdame a localizar por qué `/leavecall` no abandona una llamada 1 a 1."* | Se usó para reproducir el fallo con dos clientes y revisar la carrera BYE/AUDIO. La primera sugerencia solo cambiaba el texto del comando; se descartó y se corrigió `leaveCall()`, el registro del relay solo por HELLO y el rollback si falla el audio local. | Un comando debe declarar su alcance (1 a 1 vs. grupal) y los protocolos de membresía deben ser idempotentes ante paquetes tardíos. |
| **2026-10-24** | **Verificación de transferencia de archivos** | OpenCode (`deepseek-v4.1-flash-free`) | *"Ayúdame a probar la transferencia con el PDF del taller y a revisar casos límite."* | Se usó para preparar el escenario de prueba y revisar posibles fallos. Se transfirió el PDF (191569 B) y binarios de 2 MB y 20 MB, directos y a sala; se corrigieron los errores `null`, el conteo de duplicados y las validaciones del servidor; se verificó SHA-256 idéntico. | La integridad debe ser idempotente ante duplicados y los límites hay que aplicarlos en ambos extremos; un `null` en la UI suele ser un problema de contrato o mapeo de excepciones. |
