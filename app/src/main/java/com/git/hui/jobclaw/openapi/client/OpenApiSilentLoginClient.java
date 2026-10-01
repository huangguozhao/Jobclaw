package com.git.hui.jobclaw.openapi.client;

import com.git.hui.jobclaw.core.bizexception.StatusEnum;
import com.git.hui.jobclaw.openapi.model.OpenApiUserDTO;
import com.git.hui.jobclaw.core.utils.json.JsonUtil;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 基于技术派的静默登录客户端实现
 *
 * @author YiHui
 * @date 2025/9/15
 */
@Component
public class OpenApiSilentLoginClient {
    private final RestTemplate restTemplate;

    public OpenApiSilentLoginClient() {
        this.restTemplate = new RestTemplate();
        // 添加统一的请求头，用于身份识别
        ClientHttpRequestInterceptor interceptor = (request, body, execution) -> {
            request.getHeaders().add("pai-open-appid", "ai-oc");
            return execution.execute(request, body);
        };
        restTemplate.setInterceptors(Collections.singletonList(interceptor));
    }


    public OpenApiUserDTO loginBySession(String session) {
        String content = restTemplate.getForObject("https://www.paicoding.com/openapi/login/loginByToken?token=" + session, String.class);
        Map map = JsonUtil.toObj(content, HashMap.class);
        Map status = (Map) map.get("status");
        if (status.get("code").equals(StatusEnum.SUCCESS.getCode())) {
            Object res = map.get("result");
            return JsonUtil.toObj(JsonUtil.toStr(res), OpenApiUserDTO.class);
        }

        return null;
    }
}
