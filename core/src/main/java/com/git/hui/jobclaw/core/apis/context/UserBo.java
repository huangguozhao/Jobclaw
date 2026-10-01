package com.git.hui.jobclaw.core.apis.context;


/**
 * 用户业务对象
 *
 * @author YiHui
 * @date 2025/7/15
 */
public record UserBo(Long userId, String nickName, String avatar, UserRoleEnum role) {
}
