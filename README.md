# cc-grant-tranche

科研资助项目按成果、预算和合规状态分期拨款服务。

- JDK 21 / Spring Boot 4.1.1 / Spring Data JPA / H2（内存库，开箱即用）
- 金额一律 `BigDecimal(19,2)`；所有处理决定保留**经办人、原因与时间**

## 主要业务规则

### 1. 项目与期次
- 项目记录**总批准金额**，下辖多个按 `sequenceNo` 有序的拨款期次；每期含**计划金额、所需成果、预算条件**。
- 建项时校验：期次号必须是从 1 开始、不重复的连续序列；**全部期次计划金额合计不得超过批准总额**（项目编号唯一）。

### 2. 成果提交与验收
- 期次状态机：`PLANNED → SUBMITTED → ACCEPTED → DISBURSED → PAID`；验收不通过进入 `REJECTED`（必须填写原因），可重新提交；撤销拨款后回到 `ACCEPTED`。
- 只有**成果验收通过**且项目**没有活动中的合规暂停**时，才能批准拨款。

### 3. 拨款批准（核心一致性）
- 批准在**单个数据库事务**内完成：加悲观行锁锁定项目 → 校验状态/合规/前置期次/剩余额度 → **原子增加累计拨款** → 生成资金记录 → 更新期次状态。
- **总额护栏**：`累计拨款 + 本期金额 ≤ 批准总额`，任何并发批准组合都无法突破。
- **前置期次护栏**：所有更早期次必须已有生效拨款，不允许跳期批准。
- **业务号幂等**：拨款/追回以调用方提供的 `businessNo` 为幂等键（数据库唯一约束兜底），重复提交原样返回同一笔记录，不重复占用额度；业务号复用到其他对象返回 409。
- **并发只产生一种一致结果**：验收决定、合规暂停/解除、拨款批准、支付、撤销、追回、预算调整确认均通过项目行悲观锁与项目版本串行化；并发竞争落败方收到业务冲突错误，不会产生半成功状态。

### 4. 撤销与追回
- **未支付（UNPAID）拨款可撤销**：资金记录置为 `REVOKED` 并永久保留（台账可查撤销人/原因/时间），累计拨款原子扣减、**额度恢复**，期次回到 `ACCEPTED` 可重新批准。
- **已支付（PAID）拨款不可撤销**，只能登记**追回（RECOVERY）**：生成独立追回记录冲减净拨款额，项目追回总额原子增加；**原拨款资金记录保持 PAID 不变**，只在其上登记累计追回额，累计追回不得超过原拨款金额。
- 追回记录同样以 `businessNo` 保证幂等。

### 4.1 未拨付预算在未来期次之间的调整

调整只重新分配项目**尚未使用**的金额，**项目批准总额与累计拨款始终不变**，只是把两个期次的计划金额做等额一增一减；已经支付、正在拨付（已批准未支付）或用于追回的金额一律保留在**原期次、原用途**，不允许移动。

**允许调整的范围**
- 调出期次与调入期次必须是**同一项目内、不同的两个期次**。
- 两个期次都必须处于**尚未拨付**状态：`PLANNED / SUBMITTED / REJECTED / ACCEPTED`。
  - 即使期次已验收通过（`ACCEPTED`），只要还没批准拨款，其计划金额仍可调整；之后拨款按**调整后金额**占用额度。
  - 期次一旦进入 `DISBURSED / PAID`（含其拨款正在支付或已登记追回），既不能调出也不能调入。
- 调整金额必须为正，且不得超过调出期次**当前尚未拨付的计划金额**（不能把别人已承诺/已支付的钱挪走）。

**两阶段：申请 → 确认**
- **申请**只登记方案并快照两期次计划金额与项目版本，不改变任何金额；以调用方 `businessNo` 幂等。
- **确认**时（而非申请时）在同一项目行锁内**重新检查全部依据**：无活动合规暂停、两期次仍未拨付、计划金额仍是申请时快照、调整额仍不超过调出余额；通过后在**同一事务**内更新两个期次并把方案置为 `CONFIRMED`（记录批准人、原因及调整前后金额）。任一检查或更新失败则**整体回滚**，不会出现只减不增的半成功状态。
- **合规暂停即作废旧方案**：发起暂停会把项目内所有待确认方案置为 `INVALIDATED`（留痕原因/时间），暂停期间不能确认；解除暂停后**旧方案不能直接沿用**，必须重新检查、重新申请。
- 申请后、确认前任一期次被拨款，或计划金额被另一笔已确认调整改变，确认时旧方案同样作废。
- 调整请求与确认均**幂等**：相同 `businessNo` 重复申请返回同一方案，重复确认已完成方案不重复转移金额。

**与拨款确认并发**
- 拨款确认与预算调整确认通过**项目版本 + 行悲观锁**裁决：进入事务时先快照项目版本、再申请项目行锁，获锁后比对版本。真正并发重叠时，先提交者推进版本，后到者在锁内发现版本已变而收到 `409` 并整体回滚——**两者恰好一个成功**；二者不重叠（先后发生）时互不影响。

### 5. 查询
- 项目余额：批准总额 / 已承诺（累计拨款）/ 已追回 / 净支付 / 可拨余额
- 期次证据：期次状态、成果材料、提交人与验收决定
- 合规状态：暂停/解除全量记录（原因、发起人、解除人）
- 资金台账：拨款与追回全量记录（经办人、原因、支付、撤销信息）
- 预算调整：调整申请/确认/作废全量记录（原因、申请人、批准人、两期次调整前后金额）

## HTTP 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/projects` | 建项并定义全部期次（超额/序号非法 → 409/400） |
| GET | `/api/projects/{id}/balance` | 项目余额 |
| GET | `/api/projects/{id}/tranches` | 期次与成果证据 |
| GET | `/api/projects/{id}/compliance` | 合规暂停记录 |
| GET | `/api/projects/{id}/fund-records` | 资金台账 |
| GET | `/api/projects/{id}/adjustments` | 预算调整记录（申请/确认/作废留痕） |
| POST | `/api/projects/tranches/{id}/submit` | 提交成果（evidence、submittedBy） |
| POST | `/api/projects/tranches/{id}/review` | 验收决定（approved、reviewedBy、comment） |
| POST | `/api/projects/tranches/{id}/disburse` | 批准拨款（businessNo 幂等、approvedBy、reason） |
| POST | `/api/projects/fund-records/{id}/pay` | 确认支付（paidBy） |
| POST | `/api/projects/fund-records/{id}/revoke` | 撤销未支付拨款（revokedBy、reason） |
| POST | `/api/projects/fund-records/{id}/recoveries` | 已支付拨款追回（businessNo、amount、recoveredBy、reason） |
| POST | `/api/projects/{id}/suspensions` | 发起合规暂停（reason、raisedBy） |
| POST | `/api/projects/suspensions/{id}/lift` | 解除暂停（liftedBy、reason） |
| POST | `/api/projects/adjustments` | 申请未拨付预算期次间调整（fromTrancheId、toTrancheId、amount、businessNo、reason、requestedBy） |
| POST | `/api/projects/adjustments/{id}/confirm` | 确认调整（confirmedBy）；重新校验并在同一事务等额转移 |

错误约定：业务规则冲突返回 `409`，资源不存在 `404`，参数校验失败 `400`，统一 JSON 错误体。

### 请求示例

```bash
# 建项：批准 100 万，分两期 40/60
curl -X POST localhost:8080/api/projects -H 'Content-Type: application/json' -d '{
  "projectCode": "GRANT-2026-001", "title": "新能源材料研究",
  "approvedAmount": 100,
  "tranches": [
    {"sequenceNo": 1, "plannedAmount": 40,
     "requiredDeliverable": "中期实验报告", "budgetConditions": "配套资金到位"},
    {"sequenceNo": 2, "plannedAmount": 60,
     "requiredDeliverable": "结题论文与专利", "budgetConditions": "决算通过审计"}
  ]
}'

# 提交成果 → 验收 → 批准拨款（businessNo 重复提交安全）
curl -X POST .../tranches/1/submit  -d '{"evidence":"中期报告.pdf","submittedBy":"pi-zhang"}'
curl -X POST .../tranches/1/review  -d '{"approved":true,"reviewedBy":"reviewer-li","comment":"达标"}'
curl -X POST .../tranches/1/disburse -d '{"businessNo":"PAY-2026-0001","approvedBy":"approver-wang","reason":"第一期"}'

# 未拨付预算调整：第一期 40 → 30，第二期 60 → 70（项目总额仍为 100）
# 1) 申请（仅登记方案，金额不变；businessNo 幂等）
curl -X POST .../adjustments -d '{
  "fromTrancheId": 1, "toTrancheId": 2, "amount": 10,
  "businessNo": "ADJ-2026-0001", "reason": "研究节奏后移", "requestedBy": "manager-qian"}'
# 2) 确认（持锁重新校验，同事务等额转移）
curl -X POST .../adjustments/1/confirm -d '{"confirmedBy":"approver-wang"}'
```

## 并发控制说明

所有写操作在事务入口对项目行执行 `SELECT ... FOR UPDATE`（悲观写锁，10s 超时）：

- 不同项目互不阻塞；同一项目的验收/暂停/拨款/支付/撤销/追回/预算调整确认严格串行；
- 累计拨款与追回总额只在持锁状态下读改写，杜绝“检查通过但提交时已超额”；
- 项目带乐观版本号：拨款确认与预算调整确认进入时先快照版本、持锁后比对版本，真重叠时先提交者推进版本、后到者收到 409 并整体回滚，二者恰好一个成功；
- 业务号唯一约束是第二道防线：即使在不同隔离级别/数据库下出现极端竞争，重复插入也会被数据库拒绝（转为 409），不会产生重复拨款或重复调整。

## 常用命令

运行测试（含业务规则、幂等与 7 个多线程并发一致性场景）：

    ./mvnw clean test

启动服务：

    ./mvnw spring-boot:run

## 测试覆盖

- `GrantServiceTest`：建项超额/序号校验、完整拨款支付流程、验收门控、合规暂停门控与解除、前置期次顺序、业务号幂等与复用冲突、撤销恢复额度与重新拨款、已支付不可撤销、追回冲减净额/原记录不变/超额追回拦截、未支付不可追回、退回重提、决定人与原因留痕查询。
- `BudgetAdjustmentTest`：申请不动金额/确认等额转移且项目总额不变、验收未拨付期次可调整并按新金额拨款、已拨付（DISBURSED/PAID/追回中）期次不可调出调入、超额/同期次/跨项目/非正数拦截、暂停作废旧方案且恢复后须重新申请、申请后拨款使旧方案失效、余额快照过期重校验作废、申请与确认幂等及业务号冲突、拨款与调整并发只成功一个且失败完整回滚。
- `GrantConcurrencyTest`（真实线程并发）：并发拨款总额不突破、同期次并发只成功一笔、同业务号并发幂等返回同一条、10 轮乱序冲击下不绕过前置期次、暂停与拨款并发只形成一种结果、验收/拨款/暂停/撤销混合并发后台账与累计字段始终一致、屏障锁确定性验证拨款确认与预算调整确认真重叠时恰好一个成功。
- `GrantControllerTest`：HTTP 全流程、校验错误、404/409 状态码与查询接口。
- `BudgetAdjustmentControllerTest`（非事务）：调整申请/确认/查询 HTTP 流程，幂等早返回路径在事务提交后仍能正确序列化懒加载关联。
