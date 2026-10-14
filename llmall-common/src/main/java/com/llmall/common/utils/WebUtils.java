package com.llmall.common.utils;


import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Collection;
import java.util.Map;

@Slf4j
public class WebUtils {

    /** 当前请求的 ServletRequestAttributes；非 Web 线程（无请求上下文）返回 null。 */
    public static ServletRequestAttributes getServletRequestAttributes() {
        RequestAttributes ra = RequestContextHolder.getRequestAttributes();
        if (ra == null) {
            return null;
        }
        return (ServletRequestAttributes) ra;
    }

    /** 当前请求；无请求上下文时返回 null。 */
    public static HttpServletRequest getRequest() {
        ServletRequestAttributes servletRequestAttributes = getServletRequestAttributes();
        return servletRequestAttributes == null ? null : servletRequestAttributes.getRequest();
    }

    /** 当前响应；无请求上下文时返回 null。 */
    public static HttpServletResponse getResponse() {
        ServletRequestAttributes servletRequestAttributes = getServletRequestAttributes();
        return servletRequestAttributes == null ? null : servletRequestAttributes.getResponse();
    }

    /** 请求头的值；无请求上下文时返回 null。 */
    public static String getHeader(String headerName) {
        HttpServletRequest request = getRequest();
        if (request == null) {
            return null;
        }
        return getRequest().getHeader(headerName);
    }

    public static void setResponseHeader(String key, String value){
        HttpServletResponse response = getResponse();
        if (response == null) {
            return;
        }
        response.setHeader(key, value);
    }

    public static boolean isSuccess() {
        HttpServletResponse response = getResponse();
        return response != null && response.getStatus() < 300;
    }

    public static String getParameters(HttpServletRequest request) {
        Map<String, String[]> parameterMap = request.getParameterMap();
        return getParameters(parameterMap);
    }

    /**
     * 把请求参数拼成 key1=value1&key2=value2；同一个 key 有多个值时用逗号连接（如 key2=value2,value3）。
     *
     * @return 拼接后的字符串
     */
    public  static <T> String getParameters(final Map<String, T> queries) {
        StringBuilder buffer = new StringBuilder();
        for (Map.Entry<String, T> entry : queries.entrySet()) {
            if(entry.getValue() instanceof String[]){
                buffer.append(entry.getKey()).append(String.join(",", ((String[])entry.getValue())))
                    .append("&");
            }else if(entry.getValue() instanceof Collection){
                buffer.append(entry.getKey()).append(
                        CollUtil.join(((Collection<String>)entry.getValue()),",")
                ).append("&");
            }
        }
        return buffer.length() > 0 ? buffer.substring(0, buffer.length() - 1) : StrUtil.EMPTY;
    }

    /** 从 url 中取出 uri：去掉 http:// 或 https:// 前缀，并截掉 ? 之后的查询串。 */
    public static String getUri(String url){
        if(StringUtils.isEmpty(url)) {
            return null;
        }

        String uri = url;
        if(uri.contains("http://") ){
            uri = uri.replace("http://", StrUtil.EMPTY);
        }else if(uri.contains("https://")){
            uri = uri.replace("https://", StrUtil.EMPTY);
        }

        int endIndex = uri.length();
        if(uri.contains("?")){
            endIndex = uri.indexOf("?");
        }
        return uri.substring(uri.indexOf("/"), endIndex);
    }

    public static String getRemoteAddr() {
        HttpServletRequest request = getRequest();
        if (request == null) {
            return "";
        }
        return request.getRemoteAddr();
    }
}

