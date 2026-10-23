# Taller Práctico: Extensión de TaskFlow con Estado, Filtros y npm

**Curso:** Computación en Internet I  
**Duración Estimada:** 120 Minutos (2 Horas de Clase)  
**Modalidad:** Parejas o Individual  
**Nivel IAG Autorizado:** Nivel 2 (Asistente de consulta conceptual y depuración de sintaxis; prohibida la generación ciega de código sin sustentación).

---

## 🎯 Objetivos de Aprendizaje (RAA)

1. **Gestión de Estado y Modelos de Datos en el Cliente:** Extender estructuras de datos persistentes (`localStorage`) incorporando atributos de estado (`completed: boolean`).
2. **Ecosistema Node.js y npm:** Instalar y consumir librerías externas de terceros en proyectos cliente con módulos ES6 nativos (`ESM`).
3. **Resolución de Módulos Web:** Comprender cómo el navegador resuelve dependencias de paquetes mediante **Import Maps** (`<script type="importmap">`).
4. **Manipulación Dinámica del DOM:** Implementar renderizado condicional, filtrado reactivo y manejo de eventos (`change`, `click`, `submit`).

---

## 📋 Contexto del Reto

En la sesión anterior construiste la versión base de **TaskFlow**: una aplicación de lista de tareas con persistencia en `localStorage` y servidor de desarrollo `live-server`.

En este taller de 2 horas llevarás la aplicación al siguiente nivel profesional:
1. **Marcar tareas como completadas:** Los usuarios podrán marcar y desmarcar tareas mediante un checkbox, reflejando visualmente el cambio (tachado y atenuado) y persistiendo el estado.
2. **Celebración interactiva con confeti:** Al completar una tarea, se disparará una animación festiva utilizando la librería de npm [`canvas-confetti`](https://www.npmjs.com/package/canvas-confetti).
3. **Barra de filtros y contador:** Poder visualizar tareas según su estado (**Todas**, **Activas**, **Completadas**) junto con un contador dinámico de tareas pendientes.

---

## ⏱️ Distribución del Tiempo (Plan de 120 Minutos)

| Bloque | Tiempo | Fase | Meta Principal |
| :--- | :--- | :--- | :--- |
| **Bloque 1** | 25 min | **Fase 1: Modelo de Datos y Checkbox** | Agregar `completed: boolean`, checkbox y estilo tachado. |
| **Bloque 2** | 25 min | **Fase 2: Instalación de Paquete npm** | Instalar `canvas-confetti`, configurar `importmap` y disparar confeti. |
| **Bloque 3** | 35 min | **Fase 3: Filtros y Contador Dinámico** | Toolbar con botones (Todas/Activas/Completadas) y conteo de pendientes. |
| **Bloque 4** | 25 min | **Fase 4: Experiencia de Usuario y Persistencia** | Estados vacíos, accesibilidad y persistencia entre recargas. |
| **Cierre** | 10 min | **Validación y Checklist** | Comprobación final contra la rúbrica de evaluación. |

---

## 🚀 Fases del Taller

### 🔹 Fase 1: Extensión del Modelo y Marcado de Completado (25 min)

#### 1.1 Modelo de datos en `src/js/storage.js`
Actualmente, las tareas se guardan con `{ id, txt }`.
- Modifica la creación de tareas en `src/js/app.js` para que cada nueva tarea nazca con `completed: false`.
- En `src/js/storage.js`, asegura que si existían tareas guardadas anteriormente sin este campo, la función `obtenerTareas()` garantice que `completed` sea un valor booleano (`Boolean(tarea.completed)`).

#### 1.2 Interfaz y Eventos en `src/js/app.js`
- Al renderizar cada tarea dentro de la función `render()`:
  - Crea un elemento `<input type="checkbox" class="task-checkbox">`.
  - Asigna su propiedad `checked = task.completed`.
  - Si `task.completed` es `true`, agrega la clase `completed` al `<li>`.
  - Escucha el evento `'change'` en el checkbox para invocar una función `toggleTarea(task.id)`.
- Implementa la función `toggleTarea(id)`:
  - Debe buscar la tarea, invertir su valor booleano (`task.completed = !task.completed`), guardar la lista en `localStorage` y llamar a `render()`.

> **💡 Pista de Estilos:** Revisa en `src/css/styles.css` la regla `.task-item.completed .task-text` que aplica `text-decoration: line-through`.

---

### 🔹 Fase 2: Instalación y Consumo de Librería Externa con npm (25 min)

#### 2.1 Instalación con npm
En la terminal, dentro de la carpeta del proyecto `taskflow-exercise`:
```bash
npm install canvas-confetti
```
Verifica que `canvas-confetti` aparezca en la sección `"dependencies"` de tu archivo `package.json`.

#### 2.2 Configuración del Import Map en `src/index.html`
Como estamos usando módulos nativos del navegador (`ESM`) servidos por `live-server`, los navegadores no conocen por defecto dónde vive `"canvas-confetti"`.
Descomenta y revisa el bloque `<script type="importmap">` en el `<head>` de `src/index.html`:

```html
<script type="importmap">
  {
    "imports": {
      "canvas-confetti": "/node_modules/canvas-confetti/dist/confetti.module.mjs"
    }
  }
</script>
```

> **¿Por qué funciona esto?**  
> Tu script de npm en `package.json` incluye `--mount=/node_modules:./node_modules`, lo que expone la carpeta de paquetes descargados al servidor web. El Import Map le indica al navegador qué archivo `.mjs` cargar cuando encuentre `import ... from 'canvas-confetti'`.

#### 2.3 Importar y disparar confeti en `src/js/app.js`
- Importa la librería al inicio de `src/js/app.js`:
  ```javascript
  import confetti from 'canvas-confetti';
  ```
- En `dispararConfeti()`, invoca la función `confetti()` con las opciones visuales que prefieras (partículas, dispersión, colores).
- En `toggleTarea(id)`, ejecuta `dispararConfeti()` **únicamente cuando una tarea pase de incompleta a completada** (no cuando se desmarque).

---

### 🔹 Fase 3: Sistema de Filtros Dinámicos y Contador (35 min)

#### 3.1 Estructura HTML en `src/index.html`
En la sección `<section class="toolbar" id="toolbar">`:
- Remueve el `style="display: none;"`.
- Agrega un contenedor con 3 botones:
  - Botón "Todas" con atributo `data-filter="all"` y clase `active` inicial.
  - Botón "Activas" con atributo `data-filter="active"`.
  - Botón "Completadas" con atributo `data-filter="completed"`.
- Agrega un elemento `<span id="task-counter" class="counter"></span>` para mostrar el conteo.

#### 3.2 Lógica de filtrado reactivo en `src/js/app.js`
- Declara una variable de estado global `currentFilter = 'all'`.
- Configura los eventos `click` en los botones de filtro:
  - Retira la clase `active` del botón anterior y asígnala al botón seleccionado.
  - Actualiza `currentFilter` según el `dataset.filter` del botón.
  - Vuelve a invocar `render()`.
- En `render()`:
  - **Filtro:** Aplica `.filter()` sobre la lista de tareas antes de dibujarlas según el valor de `currentFilter`.
  - **Contador:** Calcula el número de tareas donde `task.completed === false` y actualiza el texto del elemento `#task-counter` (ej. `"2 pendientes"`).

---

### 🔹 Fase 4: Experiencia de Usuario y Persistencia (25 min)

1. **Estados Vacíos Contextuales:** Si la lista filtrada no tiene elementos, muestra un mensaje descriptivo según el filtro activo (ej. *"No hay tareas completadas aún"* o *"¡Excelente! No tienes tareas pendientes"*).
2. **Persistencia Total:** Verifica que al marcar tareas, agregar nuevas o recargar el navegador (`F5`), el estado de cada tarea (`completed`) se conserve fielmente.
3. **Higiene de Git:** Asegúrate de que `node_modules/` **no** esté en el área de preparación de Git (`git status`).

---

## ✅ Checklist de Validación y Criterios de Aceptación

Antes de dar por terminado tu ejercicio, valida cada uno de estos puntos:

- [ ] ¿`canvas-confetti` está registrado en `dependencies` de `package.json`?
- [ ] ¿El archivo `.gitignore` ignora `node_modules/`?
- [ ] ¿Cada tarea tiene un checkbox funcional que refleja su estado?
- [ ] ¿El texto de las tareas completadas se muestra tachado y con menor opacidad?
- [ ] ¿Se dispara confeti automáticamente únicamente al marcar una tarea como completada?
- [ ] ¿Los botones "Todas", "Activas" y "Completadas" filtran la lista en tiempo real sin perder tareas?
- [ ] ¿El contador numérico muestra exactamente la cantidad de tareas activas restantes?
- [ ] ¿Al recargar la página persisten las tareas y sus estados?
- [ ] ¿La consola del navegador está libre de errores de JavaScript?

---

## 📊 Rúbrica de Evaluación (100 Puntos)

| Criterio | Descripción | Puntaje |
| :--- | :--- | :---: |
| **Modelo y Checkbox** | Atributo `completed`, interacción mediante checkbox y estilo visual tachado. | 25 pts |
| **Integración npm & Confeti** | Instalación limpia de `canvas-confetti`, configuración de `importmap` y disparo de animación. | 25 pts |
| **Filtros Dinámicos** | Filtrado correcto por Todas/Activas/Completadas con botón activo visible. | 25 pts |
| **Contador y Persistencia** | Conteo reactivo de pendientes, persistencia en `localStorage` y manejo de recarga. | 15 pts |
| **Calidad de Código y Git** | Código limpio, modular (ESM), sin errores en consola y `node_modules` excluido en `.gitignore`. | 10 pts |

---

## 🛠️ Comandos de Inicio Rápido

```bash
# 1. Instalar live-server
npm install

# 2. Instalar la librería externa canvas-confetti
npm install canvas-confetti

# 3. Iniciar el servidor local
npm start
```
