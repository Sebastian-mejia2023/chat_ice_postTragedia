const KEY = 'taskflow_tasks';

/**
 * Obtiene la lista de tareas desde localStorage.
 * 
 * TODO (Fase 1): Asegura que cada objeto retornado tenga la estructura:
 * { id: number, txt: string, completed: boolean }
 * 
 * Pista: Si una tarea previa no tiene la propiedad 'completed', puedes usar
 * Boolean(tarea.completed) para inicializarla en false.
 * 
 * @returns {Array<{id: number, txt: string, completed: boolean}>}
 */
export function obtenerTareas() {
  const data = localStorage.getItem(KEY);
  if (!data) return [];
  try {
    const list = JSON.parse(data);
    // TODO: Mapea cada tarea garantizando la propiedad 'completed: boolean'
    return list.map((task) => ({
      ...task,
    }));
  } catch (error) {
    console.error('Error al parsear tareas desde localStorage:', error);
    return [];
  }
}

/**
 * Guarda el arreglo de tareas en localStorage serializado en JSON.
 * @param {Array<{id: number, txt: string, completed: boolean}>} items
 */
export function guardarTareas(items) {
  localStorage.setItem(KEY, JSON.stringify(items));
}
