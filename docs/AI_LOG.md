# AI_LOG — Bitácora de uso ético de IA Generativa (Nivel 3: Colaboración Asistida)

Registro de **apoyo**, no de autoría. La IAG se utilizó para planificar, aclarar conceptos y revisar errores o alternativas; el estudiante escribió y adaptó el código, tomó las decisiones de arquitectura y validó los resultados con pruebas y ejecución real. El resumen está en `README.md` §6.

**Integrante:** Sebastián Mejía Trujillo (A00410557)

**Herramientas usadas:** Antigravity (Gemini) para planificación y consultas conceptuales; OpenCode (`deepseek-v4.1-flash-free`) para reproducción de fallos, revisión de casos límite y contraste de propuestas.

---

## Plantilla de entrada

### <tema>
- **Integrante:** 
- **Herramienta/modelo:** 
- **Consulta principal (resumen):** 
- **Apoyo brindado por la IAG:** 
- **Trabajo y adaptación realizada:** 
- **Limitaciones o errores de las sugerencias:** 
- **Validación (tests, ejecución, revisión):** 
- **Lección aprendida:** 

---

## Entradas

### Corrección de `/leavecall`
- **Integrante:** Sebastián Mejía Trujillo (A00410557)
- **Herramienta/modelo:** OpenCode (`deepseek-v4.1-flash-free`)
- **Consulta principal (resumen):** “Ayúdame a localizar por qué `/leavecall` no abandona la llamada y el usuario sigue activo”.
- **Apoyo brindado por la IAG:** se usó para reproducir el caso con dos clientes y un servidor local y para revisar la ruta del comando. El fallo estaba en que `/leavecall` solo llamaba a `leaveRoomCall()`, que retorna si `activeRoomCall == null` (llamada 1 a 1). También se revisó la carrera BYE/AUDIO del relay.
- **Trabajo y adaptación realizada:** se implementó `leaveCall()` para despachar a `hangup` o `leaveRoomCall`, se ajustó el endpoint real del relay, se detuvo la captura antes del BYE y se cambió el relay para registrar solo por HELLO, con rollback si falla el audio local.
- **Limitaciones o errores de las sugerencias:** la primera propuesta solo cambiaba el texto del comando; no cubría la llamada 1 a 1 ni la carrera del relay. Se descartó y se trabajó sobre el flujo completo. Los errores Ice que se mostraban como `null` se trataron en una corrección aparte.
- **Validación (tests, ejecución, revisión):** `VoiceCallManagerTest` (1 a 1, grupal con BYE, rollback) y `AudioRelayTest` (sin eco, BYE, remitente no registrado); ejecución manual con dos clientes; `./scripts/verify.sh` con `FALLAS=0 AVISOS=3`.
- **Lección aprendida:** un comando debe declarar su alcance (1 a 1 vs. grupal) y los protocolos de membresía deben ser idempotentes ante paquetes tardíos; el orden de parada de hilos importa para no re-registrar usuarios.

### Verificación de transferencia de archivos con PDF
- **Integrante:** Sebastián Mejía Trujillo (A00410557)
- **Herramienta/modelo:** OpenCode (`deepseek-v4.1-flash-free`)
- **Consulta principal (resumen):** “Ayúdame a probar la transferencia con el PDF del taller y a revisar casos límite; si hay algo que no funcione, explícame por qué”.
- **Apoyo brindado por la IAG:** se usó para proponer el escenario de prueba con servidor y dos clientes reales y para revisar posibles fallos. La transferencia del PDF funcionó (191569 B, 4 chunks, SHA-256 verificado) tanto directa como a sala. Al revisar casos límite se confirmaron tres defectos: (1) los mensajes de error Slice salían `null`, (2) un chunk duplicado podía completar la transferencia antes de tiempo y (3) el servidor no validaba tamaño total ni nombre.
- **Trabajo y adaptación realizada:** se ejecutaron las pruebas, se ajustó el test de Path Traversal para que usara un hash válido (el receptor ahora borra archivos corruptos), se verificó que el límite de offset debe usar `AudioConstants.CHUNK_SIZE`, se comprobó con dos clientes que el emisor no recibe su propio archivo en una sala y se implementaron `ErrorMessages`, el mapeo `Ice.Package.Chat`, el seguimiento de índices únicos con CAS y los límites de 200 MB/64 KB con saneamiento del nombre.
- **Limitaciones o errores de las sugerencias:** la primera versión del test de Path Traversal usaba un hash ficticio y fallaba al activar la limpieza; la validación de índice con `totalChunks` era correcta pero insuficiente para offsets fuera del tamaño total. Se corrigieron ambos.
- **Validación (tests, ejecución, revisión):** `ChunkAssemblerTest` (desorden, duplicados, Path Traversal y descarte de transferencias inactivas), `ErrorMessagesTest`, `FileRelayServiceTest`; transferencia manual del PDF (directa y a sala), de 2 MB y de 20 MB (directa y a sala), todos con SHA-256 idéntico; mensaje a usuario inexistente ahora muestra “Destinatario 'nadie' no encontrado…”. `./gradlew test` en verde.
- **Lección aprendida:** la verificación de integridad debe ser idempotente ante duplicados y los límites hay que aplicarlos en ambos extremos; un `null` en la UI suele ser un problema de contrato o mapeo de excepciones, no de la lógica de negocio.

### Integración real de `AudioMixer` en `AudioPlayer`
- **Integrante:** Sebastián Mejía Trujillo (A00410557)
- **Herramienta/modelo:** OpenCode (`deepseek-v4.1-flash-free`)
- **Fecha:** 2026-10-28
- **Consulta principal (resumen):** “Revisa si hay clases que no estén conectadas al flujo; conecta `AudioMixer` si es requisito y documenta el cambio”.
- **Apoyo brindado por la IAG:** se auditó el flujo de voz y se confirmó que `AudioPlayer` escribía cada payload directo al `AudioSink` (`AudioPlayer.java:61-64`), sin usar `AudioMixer`. La IAG propuso un buffer acotado por emisor, un hilo mezclador cada 20 ms y pruebas con `FakeAudioSink`; también señaló que el README afirmaba una mezcla que no existía y que el test unitario de `AudioMixer` no demostraba la integración.
- **Trabajo y adaptación realizada:** se implementó en `AudioPlayer` una cola por nickname (fallback `senderId`) con capacidad de 2 frames y descarte del más viejo; cada tick de 20 ms se toma un frame por emisor, se llama a `AudioMixer.mix(frames, 640)` (con clipping) y se escribe en el `AudioSink`. Si no hay frames, se escribe silencio. La parada apaga el scheduler, interrumpe el receptor, limpia las colas y cierra el sink; el socket lo sigue cerrando `VoiceCallManager`. Se añadió `AudioPlayerTest` con mezcla de dos emisores, saturación, descarte del frame viejo, paquetes no-audio, recepción UDP real con socket efímero y parada idempotente.
- **Limitaciones o errores de las sugerencias:** la primera propuesta asumía que bastaba con llamar a `AudioMixer.mix` dentro del hilo receptor; eso no mezclaba voces concurrentes ni daba ritmo de reproducción. Se descartó y se separó recepción de mezcla. Tampoco se pudo verificar audio físico con micrófono/altavoz en este entorno; la validación es lógica y con sockets loopback.
- **Validación (tests, ejecución, revisión):** `AudioPlayerTest` (7 pruebas) y `AudioMixerTest` en verde; `./scripts/verify.sh` → `FALLAS=0 AVISOS=3`. El audio real en dos máquinas y el comportamiento acústico quedan pendientes de prueba manual.
- **Lección aprendida:** una clase con tests unitarios no prueba que el requisito esté integrado; la prueba debe atravesar el flujo real (socket → buffer → mezcla → sink). También es importante que el README no afirme comportamiento que el código no ejecuta.
