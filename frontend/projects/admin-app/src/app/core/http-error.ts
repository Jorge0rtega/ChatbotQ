import { HttpErrorResponse } from '@angular/common/http';
export function httpErrorMessage(error: unknown): string {
  if (error instanceof HttpErrorResponse) {
    const server = typeof error.error === 'object' && error.error ? (error.error.message ?? error.error.detail) : undefined;
    if (typeof server === 'string' && server.trim()) return server;
    if (error.status === 0) return 'No se pudo conectar con el servidor.';
    if (error.status === 400) return 'Los datos enviados no son válidos.';
    if (error.status === 401) return 'La sesión no es válida o ha caducado.';
    if (error.status === 403) return 'No tienes permisos para realizar esta acción.';
    if (error.status === 404) return 'El recurso solicitado no existe.';
    if (error.status === 409) return 'La operación entra en conflicto con datos existentes.';
  }
  return 'Ocurrió un error inesperado. Inténtalo de nuevo.';
}
