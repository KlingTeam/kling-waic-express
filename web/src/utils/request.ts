import type { AxiosError, InternalAxiosRequestConfig } from 'axios'
import axios from 'axios'
import { showNotify } from 'vant'
import {
  STORAGE_ACTIVE_KEY,
  STORAGE_TOKEN_KEY,
  STORAGE_USER_TOKEN_KEY,
} from '@/stores/mutation-type'

declare global {
    interface Window {
        APP_CONFIG?: {
            API_SERVER_BASE_URL: string;
        };
    }
}

// 这里是用于设定请求后端时，所用的 Token KEY
export const REQUEST_TOKEN_KEY = 'Authorization'
console.log(import.meta.env)

// 兼容处理：优先 runtime 配置，fallback 到 Vite 编译时环境变量
const baseURL =
    (window.APP_CONFIG?.API_SERVER_BASE_URL !== '${API_SERVER_BASE_URL}' ? window.APP_CONFIG?.API_SERVER_BASE_URL : '') ||
    import.meta.env.VITE_API_SERVER_BASE_URL ||
    '/';

const request = axios.create({
    baseURL,
    timeout: 600 * 1000,
});

export type RequestError = AxiosError<{
  message?: string
  result?: any
  errorMessage?: string
}>

// 异常拦截处理器
function errorHandler(error: RequestError): Promise<any> {
  if (error.response) {
    const { data = {}, status, statusText } = error.response
    // 403 无权限
    if (status === 403) {
      showNotify({
        type: 'danger',
        message: (data && data.message) || statusText,
      })
    }
    // 401 未登录/未授权
    if (status === 401 && data.result && data.result.isLogin) {
      showNotify({
        type: 'danger',
        message: 'Authorization verification failed',
      })
      // 如果你需要直接跳转登录页面
      // location.replace(loginRoutePath)
    }
  }
  return Promise.reject(error.response.status)
}

// 请求拦截器
function requestHandler(
  config: InternalAxiosRequestConfig,
): InternalAxiosRequestConfig | Promise<InternalAxiosRequestConfig> {
  const savedToken = localStorage.getItem(STORAGE_TOKEN_KEY)
  const savedUserToken = localStorage.getItem(STORAGE_USER_TOKEN_KEY)
  const savedActive = localStorage.getItem(STORAGE_ACTIVE_KEY)
  const isUserPage = location.pathname.startsWith('/creation')

  // 如果 token 存在
  // 让每个请求携带自定义 token, 请根据实际情况修改
  if ((savedToken || savedUserToken) && !config.headers[REQUEST_TOKEN_KEY]) {
    config.headers[REQUEST_TOKEN_KEY] = `Token ${
      isUserPage ? savedUserToken : savedToken
    }`
  }

  // 添加活动头部
  if (savedActive && !config.headers.Activity) {
    config.headers.Activity = savedActive
  }

  return config
}

// Add a request interceptor
request.interceptors.request.use(requestHandler, errorHandler)

// 响应拦截器
function responseHandler(response: { data: any, status: string }) {
  if (response.data.status !== 'SUCCEED') {
    if (
      response.data.status === 'KLING_OPEN_API_EXCEPTION'
      && response.data.klingOpenAPIResult?.code
    ) {
      // 排队人数较多
      throw new Error(response.data.klingOpenAPIResult.code)
    }
    else {
      throw new Error(response.data.status)
    }
  }
  return response.data.data
}

// Add a response interceptor
request.interceptors.response.use(responseHandler, errorHandler)

export default request
