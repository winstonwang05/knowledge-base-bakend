package com.knowledge.base.gateway.filter;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * 统一响应过滤器
 *
 * <p>参考susan-mall-cloud的AuthFilter实现，统一处理响应体封装</p>
 * <p>主要功能：</p>
 * <ul>
 *   <li>拦截后端服务响应，确保响应格式统一</li>
 *   <li>处理分段传输的数据</li>
 *   <li>自动包装非标准格式的响应</li>
 *   <li>记录完整的响应日志</li>
 * </ul>
 *
 * @author Winston
 * @since 1.0.0
 */
@Slf4j
@Component
public class UnifiedResponseFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpResponse originalResponse = exchange.getResponse();
        DataBufferFactory bufferFactory = originalResponse.bufferFactory();

        ServerHttpResponseDecorator decoratedResponse = new ServerHttpResponseDecorator(originalResponse) {

            @Override
            public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                if (body instanceof Flux) {
                    String contentType = getDelegate().getHeaders().getFirst(HttpHeaders.CONTENT_TYPE);

                    // 只处理JSON格式的响应
                    if (contentType != null && contentType.contains(MediaType.APPLICATION_JSON_VALUE)) {
                        Flux<? extends DataBuffer> fluxBody = Flux.from(body);

                        // 处理分段传输的数据
                        return super.writeWith(fluxBody.buffer().flatMap(dataBuffers -> {
                            String responseData;
                            try {
                                // 先合并所有DataBuffer的字节（避免分段解码导致UTF-8多字节字符被截断产生乱码）
                                int totalSize = 0;
                                for (DataBuffer dataBuffer : dataBuffers) {
                                    totalSize += dataBuffer.readableByteCount();
                                }
                                byte[] allBytes = new byte[totalSize];
                                int offset = 0;
                                for (DataBuffer dataBuffer : dataBuffers) {
                                    int size = dataBuffer.readableByteCount();
                                    dataBuffer.read(allBytes, offset, size);
                                    offset += size;
                                }
                                // 一次性解码所有字节，保证UTF-8多字节字符（如中文）的完整性
                                responseData = new String(allBytes, StandardCharsets.UTF_8);
                            } catch (Exception e) {
                                log.error("读取响应字节流异常：{}", e.getMessage(), e);
                                responseData = "";
                            }

                            // 释放原始数据缓冲区
                            dataBuffers.forEach(DataBufferUtils::release);
                            log.info("网关转发响应: URI={}, Status={}, Response={}",
                                    exchange.getRequest().getURI(),
                                    getStatusCode(),
                                    responseData);

                            // 统一处理响应数据：
                            // 1. swagger-config：改写分组地址为相对路径（经网关访问必需）；
                            // 2. 其他文档/静态资源 JSON：原样透传，避免被包装成 Result 后无法解析；
                            // 3. 业务响应：包装为标准 Result 结构。
                            String path = exchange.getRequest().getURI().getPath();
                            String finalResponse;
                            if (isSwaggerConfigRequest(path)) {
                                finalResponse = relativizeApiDocUrls(responseData);
                            } else if (shouldWrapResponse(path)) {
                                finalResponse = wrapResponse(responseData);
                            } else {
                                finalResponse = responseData;
                            }
                            byte[] uppedContent = finalResponse.getBytes(StandardCharsets.UTF_8);

                            // 设置Content-Type，确保UTF-8编码
                            getDelegate().getHeaders().setContentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8));
                            // 更新Content-Length
                            getDelegate().getHeaders().setContentLength(uppedContent.length);

                            // 返回新的数据缓冲区
                            return Mono.just(bufferFactory.wrap(uppedContent));
                        }));
                    }
                }
                return super.writeWith(body);
            }

            @Override
            public Mono<Void> writeAndFlushWith(Publisher<? extends Publisher<? extends DataBuffer>> body) {
                return writeWith(Flux.from(body).flatMapSequential(p -> p));
            }
        };

        return chain.filter(exchange.mutate().response(decoratedResponse).build());
    }

    /**
     * 判断当前请求的响应是否需要进行统一包装。
     *
     * <p>API 文档相关路径（springdoc / knife4j）返回的是 OpenAPI 规范 JSON，
     * 不是业务 Result 结构，包装后会破坏文档解析，需要原样透传：</p>
     * <ul>
     *   <li>{@code /v3/api-docs}、{@code /v3/api-docs/swagger-config}</li>
     *   <li>{@code /swagger-resources}、{@code /swagger-ui}</li>
     *   <li>{@code /doc.html}、{@code /webjars/**}、{@code /favicon.ico}</li>
     * </ul>
     *
     * <p>注意：通过网关访问时路径带有服务前缀（如
     * {@code /api/auth/v3/api-docs/swagger-config}），因此这里使用包含匹配。</p>
     *
     * @param path 请求路径
     * @return true-需要包装为统一响应，false-原样透传
     */
    private boolean shouldWrapResponse(String path) {
        if (path == null || path.isEmpty()) {
            return true;
        }
        return !(path.contains("/v3/api-docs")
                || path.contains("/swagger-resources")
                || path.contains("/swagger-ui")
                || path.contains("/webjars/")
                || path.contains("/doc.html")
                || path.endsWith("/favicon.ico"));
    }

    /**
     * 判断是否为 springdoc 的分组配置请求（swagger-config）。
     *
     * @param path 请求路径（经网关时带服务前缀，如 /api/auth/v3/api-docs/swagger-config）
     * @return true-是分组配置请求
     */
    private boolean isSwaggerConfigRequest(String path) {
        return path != null && path.endsWith("/v3/api-docs/swagger-config");
    }

    /**
     * 将 swagger-config 中的分组文档地址改写为相对路径。
     *
     * <p>背景：springdoc 在 swagger-config 里返回的分组地址是「站点根绝对路径」，
     * 例如 {@code "url":"/v3/api-docs"}。Knife4j 会原样以该地址发起请求，
     * 于是通过网关访问（页面地址 http://gateway/api/auth/doc.html）时会请求
     * {@code http://gateway/v3/api-docs}——网关没有任何以 /v3 开头的路由，
     * 结果 404，文档页只显示「主页 / Swagger Models」而没有接口。</p>
     *
     * <p>处理：去掉开头的 “/”，使其成为相对地址。浏览器会以当前文档页所在目录
     * （{@code /api/{service}/}）为基准解析，从而命中对应服务的网关路由。
     * 直连服务端口访问时该方法不经过网关，行为保持不变。</p>
     *
     * @param responseData 原始 swagger-config JSON
     * @return 改写后的 JSON；解析失败时原样返回
     */
    private String relativizeApiDocUrls(String responseData) {
        try {
            JSONObject config = JSON.parseObject(responseData);
            if (config == null) {
                return responseData;
            }
            JSONArray urls = config.getJSONArray("urls");
            if (urls != null) {
                for (int i = 0; i < urls.size(); i++) {
                    JSONObject item = urls.getJSONObject(i);
                    if (item != null) {
                        removeLeadingSlash(item, "url");
                        removeLeadingSlash(item, "location");
                    }
                }
            }
            // 兼容 springdoc 未返回 urls、仅返回顶层 url 的情况
            removeLeadingSlash(config, "url");
            return config.toJSONString();
        } catch (Exception e) {
            log.warn("改写swagger-config文档地址失败，按原样返回: {}", e.getMessage());
            return responseData;
        }
    }

    /**
     * 去除 JSON 字段值的起始斜杠，将其转为相对路径。
     *
     * @param obj JSON 对象
     * @param key 字段名
     */
    private void removeLeadingSlash(JSONObject obj, String key) {
        String value = obj.getString(key);
        if (value != null && value.startsWith("/")) {
            obj.put(key, value.substring(1));
        }
    }

    /**
     * 包装响应数据
     * <p>如果响应数据已经是标准格式，则直接返回；否则包装为标准格式</p>
     *
     * @param responseData 原始响应数据
     * @return 包装后的响应数据
     */
    private String wrapResponse(String responseData) {
        try {
            // 尝试解析为JSON
            Object json = JSON.parse(responseData);
            if (json instanceof JSONObject) {
                JSONObject obj = (JSONObject) json;
                // 检查是否已经包含code和message字段（标准Result格式）
                if (obj.containsKey("code") && obj.containsKey("message")) {
                    return responseData;
                }
            }
        } catch (Exception ignored) {
            // JSON解析失败，说明不是标准格式，需要包装
        }

        // 包装为标准Result格式
        JSONObject result = new JSONObject();
        result.put("code", 200);
        result.put("message", "success");
        result.put("data", JSON.parse(responseData));
        result.put("timestamp", System.currentTimeMillis());

        return result.toJSONString();
    }

    @Override
    public int getOrder() {
        return -2; // 设置较高优先级
    }
}
