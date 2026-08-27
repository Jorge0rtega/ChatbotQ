import { FormControl } from '@angular/forms';
import {
  javaEmailValidator,
  javaPasswordValidator,
  javaProjectNameValidator,
  normalizeJavaEmail,
  normalizeJavaProjectName,
} from './admin-validators';

describe('admin contract validators', () => {
  it.each([
    'short1Let',
    `${'a'.repeat(128)}1`,
    'letterswithoutdigit',
    '123456789012',
    'Valid123456\n',
    '𐐀12345678901',
  ])('rejects password outside the Java contract: %s', (password) => {
    expect(javaPasswordValidator(new FormControl(password))).not.toBeNull();
  });

  it.each(['ValidPass123', 'Contraseña１２３abc'])('accepts Java-compatible password: %s', (password) => {
    expect(javaPasswordValidator(new FormControl(password))).toBeNull();
  });

  it.each(['a@b', `${'a'.repeat(318)}@b`, `${'a'.repeat(253)}@b`, ' local part@domain '])(
    'accepts and normalizes Java-compatible email: %s',
    (email) => {
      expect(javaEmailValidator(new FormControl(email))).toBeNull();
      expect(normalizeJavaEmail(email)).toBe(email.trim().toLowerCase());
    },
  );

  it.each(['', 'a@', '@b', 'ab', 'a@@b', `${'a'.repeat(319)}@b`])(
    'rejects email outside Java length/@ boundaries: %s',
    (email) => expect(javaEmailValidator(new FormControl(email))).not.toBeNull(),
  );

  it('supports optional null while requiring null when requested', () => {
    expect(javaEmailValidator(new FormControl(null))).toEqual({ required: true });
    expect(javaEmailValidator(new FormControl(null), false)).toBeNull();
  });

  it('normalizes Java whitespace before validating 1..160 Unicode code points', () => {
    expect(normalizeJavaProjectName(`\u00a0  Proyecto \u2003`)).toBe('Proyecto');
    expect(javaProjectNameValidator(new FormControl(` ${'😀'.repeat(160)} `))).toBeNull();
    expect(javaProjectNameValidator(new FormControl(` ${'😀'.repeat(160)}a `))).not.toBeNull();
  });

  it.each(['   ', '\u00a0\u2003', 'valid\nname', 'valid\u0085name'])(
    'rejects blank/control project name: %s',
    (name) => expect(javaProjectNameValidator(new FormControl(name))).not.toBeNull(),
  );

  it('counts valid surrogate pairs as one code point and lone surrogates as one', () => {
    expect(javaProjectNameValidator(new FormControl('😀'.repeat(160)))).toBeNull();
    expect(javaProjectNameValidator(new FormControl(`${'😀'.repeat(159)}\ud800`))).toBeNull();
    expect(javaProjectNameValidator(new FormControl(`${'😀'.repeat(160)}\ud800`))).not.toBeNull();
  });
});
