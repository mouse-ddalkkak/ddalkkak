import { api } from './client'

export const getMe = () => api<{ username: string }>('GET', '/api/auth/me')
export const login = (username: string, password: string) =>
  api<{ firstLogin: boolean }>('POST', '/api/auth/login', { body: { username, password } })
export const logout = () => api<void>('POST', '/api/auth/logout')
