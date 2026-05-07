package org.mine.gateway.filter;

import org.mine.gateway.util.JwtUtil;
import org.mine.gateway.util.RedisUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.annotation.Resource;

@Component
public class AuthFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(AuthFilter.class);

    @Resource
    private JwtUtil jwtUtil;

    // 现在这个会被真正使用！
    @Resource
    private RedisUtil redisUtil;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        log.info("==================================================");
        log.info("【网关过滤器已触发】当前请求路径：{}", exchange.getRequest().getPath());
        log.info("==================================================");

        String path = exchange.getRequest().getPath().value();

        // 白名单放行
        if (path.contains("/login") || path.contains("/register")) {
            log.info("✅ 白名单放行，不验证TOKEN：{}", path);
            return chain.filter(exchange);
        }

        // 获取token
        String token = exchange.getRequest().getHeaders().getFirst("token");
        log.info("🔒 需要鉴权，token：{}", token);

        // 1. 无token
        if (token == null || token.isEmpty()) {
            log.error("❌ 无token，返回401：{}", path);
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }

        // 2. 检查是否在Redis黑名单（退出登录/强制下线）
        if (redisUtil.hasKey("token_black:" + token)) {
            log.error("❌ token已退出登录，拒绝访问：{}", path);
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }

        // 3. 验证token是否合法
        if (jwtUtil.parseToken(token) == null) {
            log.error("❌ token无效，返回401：{}", path);
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }

        log.info("✅ token合法，放行：{}", path);
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return -10000;
    }
}