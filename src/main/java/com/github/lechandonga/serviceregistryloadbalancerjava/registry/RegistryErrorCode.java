package com.github.lechandonga.serviceregistryloadbalancerjava.registry;

/**
 * 注册中心操作被拒绝时的原因码，调用方可以据此区分不同的拒绝场景。
 */
public enum RegistryErrorCode {

    /** 实例已注册且仍然存活（未过期、未注销），重复注册被拒绝。 */
    ALREADY_REGISTERED,

    /** 续约的实例不存在（已注销或从未注册）。 */
    INSTANCE_NOT_FOUND,

    /** 对已经超过租约、被注册中心判定为过期的实例做续约，续约被拒绝。 */
    LEASE_EXPIRED,

    /** 请求参数非法（服务名 / 实例 id / 端口不合法）。 */
    INVALID_REQUEST
}
