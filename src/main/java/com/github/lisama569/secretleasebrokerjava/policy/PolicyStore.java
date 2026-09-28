package com.github.lisama569.secretleasebrokerjava.policy;

import com.github.lisama569.secretleasebrokerjava.model.PolicyRule;

import java.util.List;

/**
 * 策略存储：保存当前生效的规则集与单调递增的版本号。
 * 每次整体替换规则版本号 +1，判定缓存以该版本为键，版本一变旧缓存立即失效。
 */
public interface PolicyStore {

    /** 整体替换规则集并返回新版本号。 */
    long replaceRules(List<PolicyRule> rules);

    /** 当前规则快照（不可变）。 */
    List<PolicyRule> currentRules();

    /** 当前策略版本。 */
    long version();
}
