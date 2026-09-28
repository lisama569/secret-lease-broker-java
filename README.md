# Secret Lease Broker（本地密钥租约与访问授权服务）

一个**纯本地、进程内**运行的 Spring Boot 服务，模拟“密钥后端 + 调用方身份源 + 策略引擎 +
租约管理”，不依赖任何外部服务或真实凭据。用于演示和验证：短期凭据签发 / 续租 / 吊销、
基于身份属性的策略判定、密钥轮换语义，以及这些操作在**并发与异常场景**下给出
**可解释、可复现**的结论。

- Java 21 + Spring Boot 3.4，无数据库、无消息队列、无网络依赖
- 所有数据保存在进程内存储中（`ConcurrentHashMap` / 原子引用 / 追加式审计日志）
- 时间源可注入（`ManualTimeSource`），过期结论可在测试中精确复现

---

## 1. 构建与运行

```bash
mvn spring-boot:run          # 启动（application.properties 中 server.port=0 时为随机端口）
# 或固定端口：
mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8080
```

启动时 `DemoSeeder` 会自动写入 4 个演示调用方和一套演示策略：

| 调用方 | 属性 | 说明 |
| --- | --- | --- |
| `app-reader` | role=app, env=dev, team=payments | 可申请/续租/读取 `secret.dev.*` |
| `app-legacy` | role=app, env=dev, team=legacy | 被高优先级 DENY 规则拦截 |
| `ops-admin` | role=ops, env=prod | 全路径权限（含 ROTATE/REVOKE） |
| `outsider` | role=guest | 无任何匹配规则 → 默认拒绝 |

重置全部进程内状态并重新播种（仅用于本地演示/测试隔离）：

```bash
curl -X POST http://localhost:8080/api/local/reset
```

---

## 2. 策略模型

### 2.1 规则结构

一条策略规则 `PolicyRule` 包含：

| 字段 | 含义 |
| --- | --- |
| `id` | 规则唯一 ID（冲突裁决的次级排序键） |
| `action` | 匹配的操作：`ISSUE`/`RENEW`/`REVOKE`/`READ`/`ROTATE`；`null` 表示任意操作 |
| `pathPattern` | 密钥路径模式，支持 `*` 通配（如 `secret.dev.*`、`*`） |
| `requiredAttributes` | 调用方必须**全部**具备且相等的身份属性（AND 语义） |
| `effect` | `ALLOW` 或 `DENY` |
| `priority` | 优先级，**数值越小优先级越高** |

### 2.2 判定算法（默认拒绝 + 确定优先级）

1. 取策略存储中的**原子快照**（带单调递增 `version`）；
2. 将规则按 `(priority 升序, ruleId 字典序)` 排序——这是一个全序，冲突结论唯一可复现；
3. 依次考察：先要求 action 与 path 匹配；再校验属性；属性不满足的规则只记录到
   判定依据（trace）中并跳过；
4. **第一条完全匹配的规则立即生效**（无论 ALLOW 还是 DENY）；
5. 没有任何规则完全匹配时，返回 **`DEFAULT_DENY` 默认拒绝**。

判定结果 `PolicyDecision` 携带：是否允许、命中规则 ID、原因、策略版本、以及逐条规则的
`trace`（哪条命中、哪条因属性不满足被跳过、为何走到默认拒绝），保证“给出命中的规则依据”。

### 2.3 缓存失效

`PolicyEngine` 按 `(调用方, 路径, 操作, 属性摘要, 策略版本)` 缓存判定结果。
策略通过 `/api/policy/replace` **整体替换**后版本号 +1，旧版本键不会再被命中，
因此**策略更新后旧的判定缓存立即失效**（有专门测试覆盖）。

---

## 3. 租约生命周期

```
        ISSUE（策略 ISSUE 通过）
   ┌───────────────────────────┐
   │                           ▼
 ┌───────────────┐  RENEW(成功)  ┌─────────┐  now >= expiresAt   ┌─────────┐
 │   ACTIVE      │──────────────▶│ ACTIVE  │────────────────────▶│ EXPIRED │
 │ renewCount=0  │               │count+1  │                     └─────────┘
 └───────────────┘               └─────────┘
        │  REVOKE / HARD 轮换        ▲
        ▼                            │ RENEW/READ/再次REVOKE
 ┌───────────────┐                    └── 一律拒绝，错误码分别为
 │   REVOKED     │                         LEASE_REVOKED / LEASE_EXPIRED /
 └───────────────┘                         LEASE_ALREADY_REVOKED
```

- **签发 ISSUE**：校验参数 → 调用方存在 → 策略允许；同一 `(caller,path)` 分段加锁，
  锁内检查幂等键与已有有效租约，防止并发重复签发；携带相同 `idempotencyKey` 的重放
  返回同一张租约。租约绑定签发时刻的密钥版本 `secretVersion`。
- **续租 RENEW**：校验存在 → 属主 → **存活**（先判活：已过期/已吊销直接拒绝）→
  策略 RENEW 允许；滑动续期，`expiresAt = now + ttl`，`renewCount + 1`。
- **吊销 REVOKE**：校验存在 → 属主 → 策略 REVOKE 允许 → 原子置为 REVOKED；
  重复吊销返回 `LEASE_ALREADY_REVOKED` 并带首次吊销原因。
- **读取 READ**：属主 + 策略 READ + 存活三重校验后，按租约绑定的 `secretVersion` 返回值。

### 失败原因可区分

错误码（`ErrorCode`）与 HTTP 状态码映射：

| HTTP | 类别 | 典型错误码 |
| --- | --- | --- |
| 403 | POLICY_DENIED | `POLICY_DENIED`、`LEASE_CALLER_MISMATCH` |
| 410 | LEASE_STATE | `LEASE_EXPIRED`、`LEASE_REVOKED`、`LEASE_ALREADY_REVOKED` |
| 400 | INVALID_PARAM | `PATH_BLANK`、`TTL_OUT_OF_RANGE`、`INVALID_PARAM`、`ROTATION_MODE_INVALID`、`RULE_INVALID` |
| 404 | NOT_FOUND | `LEASE_NOT_FOUND`、`UNKNOWN_CALLER`、`SECRET_NOT_FOUND` |
| 409 | CONFLICT | `DUPLICATE_ACTIVE_LEASE`、`CONCURRENT_MODIFICATION` |

调用方可以据此明确区分“策略不允许 / 租约已失效 / 参数不合法 / 并发冲突”。

---

## 4. 并发语义

- **存储层乐观锁**：`Lease` 是不可变快照，带单调 `revision`。
  `LeaseStore.compareAndSwap(id, expectedRevision, updater)` 在 `ConcurrentHashMap.compute`
  原子块内核对 revision，只有一个提交能成功，另一个得到 empty 并重读最新状态。
- **续租 / 吊销不丢更新**：服务层对 CAS 做自旋，冲突方重新做“存活 + 策略”判定。
  因此同一张租约并发续租时，`renewCount` 恰好等于成功次数，`revision` 同步推进；
  续租与吊销并发时，结果只有两种确定形态：
  续租先成功（随后吊销生效），或续租收到 `LEASE_REVOKED`——**吊销绝不会丢失**。
- **签发防重**：按 `(caller,path)` 分段锁，把“检查已有有效租约 / 幂等重放 / 插入”收敛为临界区。
- **轮换版本号**：`SecretStore.rotate` 使用每键 `compute`，并发轮换下版本号严格递增。

---

## 5. 轮换与吊销语义

密钥路径保留全部历史版本，租约在签发时绑定版本，两种模式语义明确稳定：

| 模式 | 行为 | 旧 ACTIVE 租约 |
| --- | --- | --- |
| `SOFT` | 仅产生新版本 | 继续绑定旧版本，读取**始终返回签发时的旧值**，直到自然到期；新签发租约绑定新版本 |
| `HARD` | 产生新版本 + 原子吊销该路径全部有效租约 | 立即失效，后续读得到 `LEASE_REVOKED`；需重新申请租约才能读取新版本 |

HARD 轮换先落新版本再吊销：此后任何旧租约的读得到确定的 `LEASE_REVOKED`，
任何并发续租在 CAS 重读后得到“已吊销/已过期”的确定结论。

---

## 6. 审计

每次签发 / 续租 / 吊销 / 轮换 / 读取 / 策略判定 / 策略替换都追加一条 `AuditEvent`：
全局单调 `sequence`、时间戳、类型、调用方、路径、租约 ID、结果（`SUCCESS`/`DENIED`/`REJECTED`）
与详情（命中策略规则、版本号、轮换模式等）。

```bash
curl 'http://localhost:8080/api/audit'                 # 全部
curl 'http://localhost:8080/api/audit?leaseId=<uuid>'  # 单租约全生命周期
```

关键单测日志会打印**请求（caller/path/action/attrs）、命中的规则与 trace、最终判定结果**，
见 `PolicyEngineTest`、`LeaseServiceTest` 的输出（`mvn test` 控制台或 surefire 报告）。

---

## 7. HTTP 接口一览

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/callers` | 注册调用方及身份属性 |
| POST | `/api/leases` | 申请租约（body: callerId, path, ttlMillis, idempotencyKey?） |
| POST | `/api/leases/{id}/renew` | 续租（body: callerId, ttlMillis?） |
| POST | `/api/leases/{id}/revoke` | 吊销（body: callerId, reason?） |
| GET  | `/api/secrets/{path}?callerId=&leaseId=` | 凭有效租约读取（返回租约绑定版本的值） |
| POST | `/api/secrets/{path}/rotate` | 轮换（body: callerId, value?, mode=SOFT/HARD） |
| POST | `/api/policy/replace` | 整体替换策略（版本 +1，旧判定缓存失效） |
| GET  | `/api/policy` | 查看当前策略快照与版本 |
| POST | `/api/decide` | 只做策略判定，返回 allowed/ruleId/reason/trace |
| GET  | `/api/audit?leaseId=` | 查询审计记录 |
| POST | `/api/local/reset` | 清空并重新播种（本地/测试用） |

> 路径约定使用点分单段形式（如 `secret.dev.db`），以匹配 Spring Boot 3 单段路径变量；
> 策略通配相应写作 `secret.dev.*`。

### 本地验证示例（curl）

```bash
BASE=http://localhost:8080

# 1) 正常申请并读取
L=$(curl -s -XPOST $BASE/api/leases -H 'Content-Type: application/json' \
  -d '{"callerId":"app-reader","path":"secret.dev.demo","ttlMillis":60000}')
echo "$L"
ID=$(echo "$L" | sed -n 's/.*"leaseId":"\([^"]*\)".*/\1/p')
curl -s "$BASE/api/secrets/secret.dev.demo?callerId=app-reader&leaseId=$ID"

# 2) 越权：outsider 默认拒绝（403 POLICY_DENIED）
curl -i -XPOST $BASE/api/leases -H 'Content-Type: application/json' \
  -d '{"callerId":"outsider","path":"secret.dev.demo","ttlMillis":60000}'

# 3) 高优先级 DENY 覆盖 ALLOW：app-legacy 被拒绝（403，命中 deny-legacy-team）
curl -s -XPOST $BASE/api/decide -H 'Content-Type: application/json' \
  -d '{"callerId":"app-legacy","path":"secret.dev.demo","action":"ISSUE"}'

# 4) 策略判定依据
curl -s -XPOST $BASE/api/decide -H 'Content-Type: application/json' \
  -d '{"callerId":"app-reader","path":"secret.dev.demo","action":"ISSUE"}'

# 5) HARD 轮换：旧租约立即失效（再读得到 410 LEASE_REVOKED）
curl -s -XPOST $BASE/api/secrets/secret.dev.demo/rotate -H 'Content-Type: application/json' \
  -d '{"callerId":"ops-admin","value":"v2","mode":"HARD"}'
curl -i "$BASE/api/secrets/secret.dev.demo?callerId=app-reader&leaseId=$ID"
```

---

## 8. 测试

```bash
mvn test
```

覆盖场景（共 31 个用例）：

| 测试类 | 覆盖内容 |
| --- | --- |
| `PolicyEngineTest` | 默认拒绝、显式 ALLOW、多规则冲突优先级、同优先级按 ruleId、**策略更新后缓存失效**、action 精确匹配 |
| `LeaseServiceTest` | 签发/续租/吊销、**过期**、**越权**、重复吊销、非属主、参数非法、未知租约、幂等签发、重复有效租约 |
| `LeaseConcurrencyTest` | 32 线程并发续租计数严格一致；50 轮续租 vs 吊销竞争保证吊销不丢；批量强制吊销 |
| `LeaseStoreCasTest` | **确定性**构造 CAS 冲突，证明陈旧 revision 提交失败、重试成功 |
| `SecretServiceTest` | SOFT 旧租约读旧值 / HARD 旧租约立即 LEASE_REVOKED、越权轮换 |
| `BrokerApiIntegrationTest` | 全链路 HTTP：403/410/400/404 可区分、自然过期（真实时钟 ttl=1s）、策略更新生效、SOFT/HARD 轮换、审计可追溯 |

并发计数不变量：成功续租次数 == 最终 `renewCount` == `revision - 1`，且终态必为预期状态；
失败请求同样可区分到具体错误码，全部关键步骤可在审计日志中回放复现。
