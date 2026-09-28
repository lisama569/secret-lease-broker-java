# Secret Lease Broker（本地密钥租约与访问授权服务）

一套**完全本地运行**的密钥租约（lease）与访问授权演示服务：密钥后端、调用方目录、
策略与审计全部用**进程内存储**模拟，不依赖任何外部服务，也不包含任何真实凭据
（材料是形如 `simulated://secret/payments/db#1:7201…` 的随机串）。

目标：让凭据的**签发、续租、吊销、轮换与策略判定**在**并发和异常场景**下，都给出
**可解释、可复现**的结论——每次结论都有稳定错误码或命中规则，并留下可追溯审计。

---

## 1. 构建与本地验证

要求 JDK 21+（在 JDK 25 上同样可编译运行）。

```bash
# 全部测试（22 个用例：生命周期 / 并发 / 策略 / 过期 / 轮换 / REST）
mvn test

# 启动服务（随机端口；也可指定端口）
mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8088
```

应用启动时会自动播种演示数据（可用 `-Dlease.demo-seed.disabled` 关闭）：

- 调用方 `alice`：属性 `role=payments, env=prod, team=checkout`
- 调用方 `bob`：属性 `role=readonly, env=prod, team=audit`（无任何放行规则）
- 密钥 `secret/payments/db`（版本 1）
- 三条策略（版本 1），见下节

### 快速手工验证（curl）

```bash
B=http://localhost:8088

# 注册调用方（带身份属性）
curl -s -XPOST $B/api/admin/callers -H 'Content-Type: application/json' \
  -d '{"callerId":"alice","attributes":{"role":"payments","env":"prod"}}'

# 只做策略判定（不签发），观察命中规则与依据
curl -s -XPOST $B/api/admin/decisions -H 'Content-Type: application/json' \
  -d '{"callerId":"alice","secretPath":"secret/payments/db","action":"ISSUE"}'

# 申请短期租约（TTL 毫秒）
curl -s -XPOST $B/api/leases -H 'Content-Type: application/json' \
  -d '{"callerId":"alice","secretPath":"secret/payments/db","ttlMillis":120000}'

# 续租 / 吊销 / 查询 / 取用材料
curl -s -XPOST $B/api/leases/$LID/renew  -H 'Content-Type: application/json' -d '{"ttlMillis":120000}'
curl -s -XPOST $B/api/leases/$LID/revoke
curl -s        $B/api/leases/$LID
curl -s -XPOST $B/api/leases/$LID/material

# 轮换密钥（路径含 '/'，用 query 参数）
curl -s -XPOST "$B/api/admin/secrets/rotate?path=secret/payments/db"

# 整体替换策略（版本号 +1）
curl -s -XPOST $B/api/admin/policy -H 'Content-Type: application/json' \
  -d '{"rules":[{"ruleId":"allow-1","priority":100,"effect":"ALLOW","action":"ISSUE",
       "pathPrefix":"secret/payments/","attributes":{"role":"payments"}}]}'

# 审计：最近事件 / 某条租约的完整时间线
curl -s "$B/api/admin/audit?limit=50"
curl -s  $B/api/admin/audit/leases/$LID
```

运行期间，控制台的 `AUDIT` logger 会实时打印每条事件；策略引擎会打印
`policy request: ... -> allowed=... rule=... reason=...`，即"请求 / 命中规则 / 最终判定"。

---

## 2. 策略模型（默认拒绝 + 显式允许）

规则字段：

| 字段 | 含义 |
|---|---|
| `ruleId` | 稳定规则标识，作为"命中依据"进入判定结果与审计 |
| `priority` | 数字**越小优先级越高** |
| `effect` | `ALLOW` / `DENY` |
| `action` | `ISSUE` / `RENEW`；为空表示两者都适用 |
| `pathPrefix` | 密钥路径前缀匹配；为空表示通配 |
| `attributes` | 调用方必须**全部等值满足**的身份属性约束 |

### 判定过程

1. 只在 action 匹配、路径前缀命中、属性约束全部满足时，规则才算命中；
2. 命中规则按确定性顺序排序：
   **`priority` 升序 → 同优先级 `DENY` 先于 `ALLOW` → 再相同按 `ruleId` 字典序**，
   排序后的第一条决定结论；
3. **没有任何规则命中 = 默认拒绝**（结论中 `matchedRuleId` 为空，原因写明"默认拒绝"）。

因此多条规则冲突时结论**唯一、确定、可复现**，且 `Decision` 始终带
`matchedRuleId / effect / reason / policyVersion`。

### 策略更新与缓存失效

策略是**整体替换**，每次替换得到一个单调递增的 `policyVersion`。
判定缓存以策略版本为边界：版本一旦变化，**整个旧缓存表被丢弃**，新版本的第一次请求
必定基于新规则计算——"策略更新后旧判定缓存不会继续生效"。
测试见 `PolicyEngineTest.cacheInvalidatedOnPolicyUpdate`。

---

## 3. 租约生命周期

```
                ISSUE（策略允许 + TTL 合法）
   (不存在) ───────────────────────────────► ACTIVE
                                              │  │
                          到期(惰性判定/清扫器)│  │ RENEW（策略允许且 ACTIVE）
                                              ▼  └─► 新 expiresAt，renewCount+1（同 id）
                                           EXPIRED
                            REVOKE（立即生效）▲     ▲ 到期/吊销后续租 → LEASE_NOT_ACTIVE
                                              │     │
                                            REVOKED ┴ 重复 REVOKE → LEASE_ALREADY_REVOKED
```

- 租约是**不可变快照**；续租生成新快照但保持同一 `leaseId` 与 `issuedAt`；
- TTL 约束可配置（`lease.min-ttl-millis / default / max`，默认 1s / 60s / 1h）：
  缺省或非正数取默认值，越界返回 `INVALID_PARAMETER`；
- **到期立即失效**有两条独立保障：
  1. 任何读/改路径上的**惰性判定**（读到过期即落 `EXPIRED` 并审计）；
  2. `LeaseSweeper` 定时清扫（默认每 1s，`lease.sweep-delay-millis` 可调）；
- 测试使用可控时钟 `MutableTimeSource`，时间只在显式 `advance` 时前进，过期结论完全可复现。

### 失败原因可区分（稳定错误码）

| 场景 | ErrorCode | HTTP |
|---|---|---|
| 缺少身份/路径、未知调用方、TTL 越界、请求体非法 | `INVALID_PARAMETER` | 400 |
| 默认拒绝 / 命中 DENY（越权申请、过期之外的策略拒绝） | `POLICY_DENIED` | 403 |
| 租约 id 不存在 | `LEASE_NOT_FOUND` | 404 |
| 租约已过期或已吊销（续租、取用） | `LEASE_NOT_ACTIVE` | 409 |
| 对已吊销租约再次吊销 | `LEASE_ALREADY_REVOKED` | 409 |
| 租约绑定版本在后端缺失 | `SECRET_VERSION_MISMATCH` | 409 |
| 极端并发 id 冲突等 | `CONCURRENT_MODIFICATION` | 409 |

"策略不允许 / 租约已失效 / 参数不合法"三类拒绝因此能被调用方明确区分。

---

## 4. 并发模型

- **租约存储**：`ConcurrentHashMap` + 按 `leaseId` 哈希的 **64 条分段锁**。
  锁对象生命周期独立于数据值，同一租约的"读-改-写"严格串行，不同租约互不阻塞；
  插入用 `putIfAbsent` 防止重复签发。
- `mutate` 返回 `(newLease, changed)`，"本次调用是否真的完成了状态转移"由 `changed`
  精确表达，**不依赖时间戳是否相等**——因此同毫秒并发吊销也能准确判定谁是第一次、
  其余全部是 `LEASE_ALREADY_REVOKED`。
- 策略版本用 `volatile` 快照引用 + 版本号，规则整体替换；缓存按版本惰性切换。
- 密钥版本链按路径加锁，版本号不跳号、不重复。

并发测试（`LeaseConcurrencyTest`）断言：

- 64 线程并发续租：成功次数 **恰好等于** 最终 `renewCount`，无丢更新、无计数错乱；
- 48 续租 + 8 吊销并发交错：吊销**恰好成功一次**，其余吊销全部
  `LEASE_ALREADY_REVOKED`，吊销之后的续租全部 `LEASE_NOT_ACTIVE`，
  且成功续租数与 `renewCount` 恒等。

---

## 5. 轮换与吊销语义（明确且稳定）

### 轮换（rotate）

- 原子产生下一个不可变密钥版本（路径不存在则创建版本 1）；
- **不修改、不废止任何存量租约**：旧 `ACTIVE` 租约继续绑定旧版本，仍可续租/取用，
  直到自然到期或被吊销——轮换不会误伤既有授权；
- 旧租约**永远拿不到新版本材料**：`/material` 返回 `deliveredVersion`（租约绑定版本）
  与 `latestVersion`（当前最新），两者不同即提示需重新签发；
- 访问新版本的唯一方式是在策略允许下**重新 ISSUE**（重新经过一次策略判定）。

### 吊销（revoke）

- 立即把租约置为 `REVOKED`，此后续租、取用一律 `LEASE_NOT_ACTIVE`；
- 吊销幂等性体现为**明确拒绝重复操作**：第二次起返回 `LEASE_ALREADY_REVOKED`；
- 对已过期租约吊销返回 `LEASE_NOT_ACTIVE`（过期），两类 409 仍可由错误码区分。

### 审计

每个关键步骤（成功与失败）都写入进程内审计日志，序号在并发下原子单调递增，
可精确复盘交错顺序，事件类型包括：
`ISSUE / RENEW / REVOKE / REVOKE_DUPLICATE / EXPIRE / FETCH / ROTATE /
POLICY_UPDATE / DENY / *_DENIED` 等，并带 `caller / lease / path / detail`
（detail 内含命中规则、失败原因、版本变化）。

---

## 6. 测试一览

| 测试类 | 覆盖内容 |
|---|---|
| `LeaseLifecycleTest` | 签发/续租、越权 `POLICY_DENIED`、参数非法、过期续租、吊销与重复吊销、未知租约 |
| `LeaseConcurrencyTest` | 64 线程并发续租计数；48 续租 + 8 吊销并发交错的唯一性与错误码 |
| `PolicyEngineTest` | 默认拒绝、显式允许、优先级/DENY 优先/ruleId 决胜、策略更新缓存失效、action/路径作用域 |
| `LeaseExpiryTest` | 惰性过期、清扫器过期、到期前续租续命（可控时钟） |
| `SecretRotationTest` | 旧租约绑定旧版本、新租约取新版本、轮换不干扰吊销/过期、版本单调 |
| `LeaseApiTest` | REST 端到端：403/400/404/409 错误码区分、轮换版本可见性、审计查询 |
| `ApplicationTests` | Spring 上下文加载 |

关键用例通过 SLF4J 打印"请求 → 命中规则 → 最终判定/错误码"，审计事件同时输出到
`AUDIT` logger，可用 `mvn test` 直接观察。

---

## 7. 主要代码结构

```
clock/      TimeSource（System 实现 / 测试用 MutableTimeSource）
model/      Caller / SecretVersion / Lease / PolicyRule / Decision / AuditEvent 与枚举
error/      ErrorCode（稳定失败原因）、ServiceException、全局 HTTP 映射
store/      LeaseStore(分段锁) / SecretStore(版本链) / CallerDirectory 的进程内实现
policy/     PolicyStore(版本化) 与 PolicyEngine(默认拒绝+确定性优先级+版本化缓存)
service/    LeaseService(签发/续租/吊销/取用/惰性过期)、AdminService(轮换/策略)、
            LeaseSweeper(定时清扫)、AuditLog
web/        LeaseController / AdminController / GlobalExceptionHandler / dto
config/     LeaseProperties、DemoDataSeeder
```
