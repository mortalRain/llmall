/// <reference types="vite/client" />

interface ImportMetaEnv {
  /**
   * 网关读取 authorization 时是否要求标准的 Bearer 方案。
   * 本项目网关（AuthGlobalFilter#firstHeader）按裸 JWT 读取，故默认 raw；
   * 若网关改为标准 Bearer，构建时设置 VITE_AUTH_SCHEME=bearer 即可。
   */
  readonly VITE_AUTH_SCHEME?: 'raw' | 'bearer'
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
