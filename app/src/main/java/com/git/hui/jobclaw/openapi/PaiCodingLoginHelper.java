package com.git.hui.jobclaw.openapi;

import com.git.hui.jobclaw.core.apis.context.ReqInfoContext;
import com.git.hui.jobclaw.core.apis.context.UserBo;
import com.git.hui.jobclaw.constants.user.LoginConstants;
import com.git.hui.jobclaw.openapi.client.OpenApiSilentLoginClient;
import com.git.hui.jobclaw.openapi.model.OpenApiUserDTO;
import com.git.hui.jobclaw.user.helper.SessionHelper;
import com.git.hui.jobclaw.user.service.UserService;
import com.git.hui.jobclaw.util.SessionUtil;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 通过技术派登录服务
 *
 * @author YiHui
 * @date 2025/9/15
 */
@Slf4j
@Component
public class PaiCodingLoginHelper {
    public static final String PAI_CODING_TOKEN_NAME = "f-session";

    @Autowired
    private OpenApiSilentLoginClient openApiSilentLoginClient;

    @Autowired
    private SessionHelper sessionHelper;

    @Autowired
    private UserService userService;


    /**
     * 从cookie或者请求头中，获取技术派的登录信息
     *
     * @param request
     * @return
     */
    public UserBo loginByPaiCoding(HttpServletRequest request, HttpServletResponse response) {
        if (request.getRequestURI().contains("logout")) {
            return null;
        }

        String paiCodingJwt = getPaiCodingToken(request);
        return StringUtils.isNotBlank(paiCodingJwt) ? loginByPaiCoding(paiCodingJwt, response) : null;
    }


    /**
     * 判断技术派的用户是否发生变化，若是，则同步更新账号
     *
     * @param request            请求
     * @param response           返回
     * @param lastLoginPaiUserId 上一次登录的技术派用户id
     * @return
     */
    public PaiCodingUserStatus paiCodingUserChanged(HttpServletRequest request, HttpServletResponse response, Long lastLoginPaiUserId) {
        String jwt = getPaiCodingToken(request);
        if (jwt == null) {
            // 技术派已退出，求职派需要同步退出
            return PaiCodingUserStatus.LOGOUT;
        }
        Map<String, Object> map = sessionHelper.getPayloadWithoutVerify(jwt);
        Object u = map.get("u");
        if (u == null) {
            // 技术派登录信息有误
            return PaiCodingUserStatus.SAME;
        }
        Long paiUserId = Long.valueOf(map.get("u").toString());
        if (lastLoginPaiUserId.equals(paiUserId)) {
            // 账号没有发生变化
            return PaiCodingUserStatus.SAME;
        }

        // 技术派的账号发生了切换，我们需要同步切换账号
        loginByPaiCoding(request, response);
        log.info("技术派登录账号发生变化，同步切换账号 {} -> {}", lastLoginPaiUserId, paiUserId);
        return PaiCodingUserStatus.CHANGED;
    }

    public enum PaiCodingUserStatus {
        // 同一个账号
        SAME,
        // 账号切换
        CHANGED,
        // 已登出
        LOGOUT,
        ;
    }


    private String getPaiCodingToken(HttpServletRequest request) {
        // 从cookie获取登录信息，适用于一级域名相同的场景
        Cookie ck = SessionUtil.findCookieByName(request, PAI_CODING_TOKEN_NAME);
        if (ck != null) {
            return ck.getValue();
        }


        // 从请求头中获取，适用于iframe嵌入请求方式、域名不同的场景
        String session = request.getHeader(PAI_CODING_TOKEN_NAME);
        if (StringUtils.isNotBlank(session)) {
            return session;
        }

        // 从请求参数中获取，适用于iframe嵌入请求方式、域名不同、外部授权的场景
        session = request.getParameter(PAI_CODING_TOKEN_NAME);
        return StringUtils.isNotBlank(session) ? session : null;
    }

    public UserBo loginByPaiCoding(String session, HttpServletResponse response) {
        if (StringUtils.isBlank(session)) {
            return null;
        }
        OpenApiUserDTO openUser = openApiSilentLoginClient.loginBySession(session);
        if (openUser == null) {
            return null;
        }

        UserBo user;
        // 如果技术派的用户，有wxid，则基于wxid来登录
        if (StringUtils.isNotBlank(openUser.getWxId())) {
            user = userService.autoRegisterPaiCodingUserInfo(openUser);
        } else if (StringUtils.isNotBlank(openUser.getZsxqId()) && openUser.getLoginName().startsWith("zsxq_")) {
            // 基于知识星球用户的登录，同样使用用户名方式进行匹配
            // fixme 对于星球登录，需要判断，是否需要同步会员时间
            user = userService.autoRegisterPaiCodingUserInfo(openUser);
        } else {
            // 普通的用户名密码登录方式，统一给ai-oc的用户添加前缀 pai_
            openUser.setLoginName("pai_" + openUser.getLoginName());
            user = userService.autoRegisterPaiCodingUserInfo(openUser);
        }

        // 自动生成 ai-oc 的 session，写入技术派的用户登录信息，用于支持账号切换
        String ocSession = sessionHelper.genSession(user, openUser.getUserId());
        response.addCookie(SessionUtil.newCookie(LoginConstants.SESSION_KEY, ocSession));
        ReqInfoContext.getReqInfo().setSession(ocSession);
        return user;
    }
}
