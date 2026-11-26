/** 与后端 WebConst.PASSWORD_VALIDATE_PATTERN 保持一致：8-24 位，且必须包含大写字母、小写字母、数字、特殊字符 */
export const PASSWORD_STRENGTH_PATTERN = /^(?![A-Za-z0-9]+$)(?![a-z0-9\W]+$)(?![A-Za-z\W]+$)(?![A-Z0-9\W]+$)[a-zA-Z0-9_\W]{8,24}$/;

export const PASSWORD_STRENGTH_MESSAGE = '密码需为 8-24 位，且必须包含大写字母、小写字母、数字和特殊字符';

export const EMAIL_PATTERN = /^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$/;

export const EMAIL_MESSAGE = '邮箱格式不正确';

export function isStrongPassword(password: string): boolean {
  return PASSWORD_STRENGTH_PATTERN.test(password ?? '');
}

export function isEmail(email: string): boolean {
  return EMAIL_PATTERN.test((email ?? '').trim());
}
