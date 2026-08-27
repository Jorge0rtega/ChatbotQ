import { AbstractControl, ValidationErrors } from '@angular/forms';

const JAVA_CONTROL_CHARACTER = /[\u0000-\u001f\u007f-\u009f]/u;
const UNICODE_LETTER = /\p{L}/u;
const UNICODE_DIGIT = /\p{Nd}/u;
const JAVA_EDGE_WHITESPACE = /[\p{Z}\u0009-\u000d\u001c-\u001f]/u;

export function javaPasswordValidator(control: AbstractControl): ValidationErrors | null {
  const value = String(control.value ?? '');
  if (value.length < 12 || value.length > 128) return { javaPassword: true };

  let hasLetter = false;
  let hasDigit = false;
  // Java validates UTF-16 `char` values, so iterate code units rather than code points.
  for (let index = 0; index < value.length; index += 1) {
    const character = value[index];
    if (JAVA_CONTROL_CHARACTER.test(character)) return { javaPassword: true };
    hasLetter ||= UNICODE_LETTER.test(character);
    hasDigit ||= UNICODE_DIGIT.test(character);
  }
  return hasLetter && hasDigit ? null : { javaPassword: true };
}

export function normalizeJavaEmail(value: string): string {
  // Java String.trim() removes only code units <= U+0020.
  return value.replace(/^[\u0000-\u0020]+|[\u0000-\u0020]+$/gu, '').toLowerCase();
}

export function javaEmailValidator(
  control: AbstractControl,
  required = true,
): ValidationErrors | null {
  if (control.value === null || control.value === undefined) return required ? { required: true } : null;
  const email = normalizeJavaEmail(String(control.value));
  const at = email.indexOf('@');
  return email.length >= 3 &&
    email.length <= 320 &&
    at > 0 &&
    at === email.lastIndexOf('@') &&
    at < email.length - 1
    ? null
    : { javaEmail: true };
}

export function normalizeJavaProjectName(value: string): string {
  const codePoints = Array.from(value);
  let start = 0;
  let end = codePoints.length;
  while (start < end && JAVA_EDGE_WHITESPACE.test(codePoints[start])) start += 1;
  while (end > start && JAVA_EDGE_WHITESPACE.test(codePoints[end - 1])) end -= 1;
  return codePoints.slice(start, end).join('');
}

export function javaProjectNameValidator(control: AbstractControl): ValidationErrors | null {
  if (control.value === null || control.value === undefined) return { required: true };
  const normalized = normalizeJavaProjectName(String(control.value));
  const length = Array.from(normalized).length;
  if (length < 1 || length > 160) return { javaProjectName: true };
  return JAVA_CONTROL_CHARACTER.test(normalized) ? { javaProjectName: true } : null;
}
