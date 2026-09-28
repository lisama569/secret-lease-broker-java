package com.github.lisama569.secretleasebrokerjava.model;

/**
 * 受策略管控的操作类型。
 */
public enum AccessAction {
    /** 申请新租约 */
    ISSUE,
    /** 续租 */
    RENEW,
    /** 吊销 */
    REVOKE,
    /** 凭租约读取密钥内容 */
    READ,
    /** 轮换密钥路径下的密钥值 */
    ROTATE
}
