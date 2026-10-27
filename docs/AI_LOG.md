# AI_LOG — Bitácora de uso ético de IA Generativa (Nivel 3: Colaboración Asistida)

Registro de **apoyo**, no de autoría. La IAG se utilizó para planificar, aclarar conceptos y revisar errores o alternativas; el equipo escribió y adaptó el código, tomó las decisiones de arquitectura y validó los resultados con pruebas y ejecución real. El resumen por fases está en `README.md` §6.

**Herramientas usadas:** Antigravity (Gemini) para planificación y consultas conceptuales; OpenCode (`deepseek-v4.1-flash-free`) para reproducción de fallos, revisión de casos límite y contraste de propuestas.

---

## Plantilla de entrada

### [Fecha] — Fase N — <tema>
- **Integrante:** 
- **Herramienta/modelo:** 
- **Consulta principal (resumen):** 
- **Apoyo brindado por la IAG:** 
- **Trabajo y adaptación del equipo:** 
- **Limitaciones o errores de las sugerencias:** 
- **Validación (tests, ejecución, revisión):** 
- **Lección aprendida:** 

---

## Entradas

### 2026-10-24 — Fase 8 — Corrección de `/leavecall`
- **Integrante:** Equipo
- **Herramienta/modelo:** OpenCode (`deepseek-v4.1-flash-free`)
- **Consulta principal (resumen):** “Ayúdame a localizar por qué `/leavecall` no abandona la llamada y el usuario sigue activo”.
- **Apoyo brindado por la IAG:** se usó para reproducir el caso con dos clientes y un servidor local y para revisar la ruta del comando. El fallo estaba en que `/leavecall` solo llamaba a `leaveRoomCall()`, que retorna si `activeRoomCall == null` (llamada 1 a 1). También se revisó la carrera BYE/AUDIO del relay.
- **Trabajo y adaptación del equipo:** el equipo implementó `leaveCall()` para despachar a `hangup` o `leaveRoomCall`, ajustó el endpoint real del relay, detuvo la captura antes del BYE y cambió el relay para registrar solo por HELLO, con rollback si falla el audio local.
- **Limitaciones o errores de las sugerencias:** la primera propuesta solo cambiaba el texto del comando; no cubría la llamada 1 a 1 ni la carrera del relay. Se descartó y se trabajó sobre el flujo completo. Los errores Ice que se mostraban como `null` se trataron en una corrección aparte.
- **Validación (tests, ejecución, revisión):** `VoiceCallManagerTest` (1 a 1, grupal con BYE, rollback) y `AudioRelayTest` (sin eco, BYE, remitente no registrado); ejecución manual con dos clientes; `./scripts/verify.sh` con `FALLAS=0 AVISOS=3`.
- **Lección aprendida:** un comando debe declarar su alcance (1 a 1 vs. grupal) y los protocolos de membresía deben ser idempotentes ante paquetes tardíos; el orden de parada de hilos importa para no re-registrar usuarios.

### 2026-10-24 — Fase 4/8 — Verificación de transferencia de archivos con PDF
- **Integrante:** Equipo
- **Herramienta/modelo:** OpenCode (`deepseek-v4.1-flash-free`)
- **Consulta principal (resumen):** “Ayúdame a probar la transferencia con el PDF del taller y a revisar casos límite; si hay algo que no funcione, explícame por qué”.
- **Apoyo brindado por la IAG:** se usó para proponer el escenario de prueba con servidor y dos clientes reales y para revisar posibles fallos. La transferencia del PDF funcionó (191569 B, 4 chunks, SHA-256 verificado) tanto directa como a sala. Al revisar casos límite se confirmaron tres defectos: (1) los mensajes de error Slice salían `null`, (2) un chunk duplicado podía completar la transferencia antes de tiempo y (3) el servidor no validaba tamaño total ni nombre.
- **Trabajo y adaptación del equipo:** el equipo ejecutó las pruebas, ajustó el test de Path Traversal para que usara un hash válido (el receptor ahora borra archivos corruptos), verificó que el límite de offset debe usar `AudioConstants.CHUNK_SIZE`, comprobó con dos clientes que el emisor no recibe su propio archivo en una sala e implementó `ErrorMessages`, el mapeo `Ice.Package.Chat`, el seguimiento de índices únicos con CAS y los límites de 200 MB/64 KB con saneamiento del nombre.
- **Limitaciones o errores de las sugerencias:** la primera versión del test de Path Traversal usaba un hash ficticio y fallaba al activar la limpieza; la validación de índice con `totalChunks` era correcta pero insuficiente para offsets fuera del tamaño total. Se corrigieron ambos.
- **Validación (tests, ejecución, revisión):** `ChunkAssemblerTest` (desorden, duplicados, Path Traversal y descarte de transferencias inactivas), `ErrorMessagesTest`, `FileRelayServiceTest`; transferencia manual del PDF (directa y a sala), de 2 MB y de 20 MB (directa y a sala), todos con SHA-256 idéntico; mensaje a usuario inexistente ahora muestra “Destinatario 'nadie' no encontrado…”. `./gradlew test` en verde.
- **Lección aprendida:** la verificación de integridad debe ser idempotente ante duplicados y los límites hay que aplicarlos en ambos extremos; un `null` en la UI suele ser un problema de contrato o mapeo de excepciones, no de la lógica de negocio.
