# cc-grant-tranche

科研资助项目与拨款期次管理服务：按成果验收、预算条件和合规状态对资助项目进行分期拨款。

## 开发环境

- JDK 21
- Maven Wrapper 3.9.9
- Spring Boot 4.1.1

迁移项目沿用现有 Spring Boot 版本，其他项目使用上述版本。

## 主要业务规则

### 项目与期次

- 资助项目记录**总批准金额**和若干**有顺序的拨款期次**；每个期次包含计划金额、所需成果和预算条件。
- 全部期次计划金额之和**不得超过**项目批准金额，期次序号不得重复，创建时校验。
- 期次状态机：`PENDING`（待提交）→ `SUBMITTED`（验收中）→ `ACCEPTED` / `REJECTED`；驳回后可重新提交成果。

### 拨款批准

- 期次提交成果并通过验收后，且项目**没有活动中的合规暂停**，才能批准拨款。
- 批准时在同一事务内**原子增加**项目净累计拨款额并生成 `DISBURSEMENT` 资金台账记录。
- 单笔拨款金额不得超过期次计划金额；累计拨款不得超过项目批准金额。
- **不得绕过前置期次**：序号在前的期次必须已存在有效拨款，后续期次才能拨款。
- 每个期次同一时刻最多一笔有效（未撤销）拨款。

### 幂等与并发

- 拨款以**业务号**（`businessNo`，全局唯一）作为幂等键：重复提交返回既有拨款，不重复占额、不重复生成资金记录；追回记录同理。
- 所有改变项目资金/验收/合规状态的写操作先获取**项目行悲观写锁**（`SELECT ... FOR UPDATE`），在锁内完成校验与变更。因此验收决定、合规暂停与拨款批准/支付确认并发时，只会按锁顺序形成**一种**一致结果，不会突破总金额或绕过前置期次。

### 撤销与追回

- **未支付**拨款可撤销：恢复额度（净累计拨款减少），并生成 `REVERSAL` 冲正台账记录。
- **已支付**拨款不能撤销，只能通过**追回**（`CLAWBACK` 台账记录）减少净拨款额；原拨款资金记录保持不变，追回累计不得超过该拨款剩余净额。
- 所有处理决定（验收、暂停/解除、批准、支付、撤销、追回）均保留**操作人员与原因**。

### 查询

- `GET /api/projects/{id}/balance` — 项目余额（批准总额、净累计拨款、剩余额度）
- `GET /api/projects/{id}/tranches` — 期次证据与验收决定
- `GET /api/projects/{id}/compliance-holds` — 合规状态（暂停记录及是否活动中）
- `GET /api/projects/{id}/disbursements` — 拨款列表
- `GET /api/projects/{id}/fund-records` — 资金台账

### 主要写接口

- `POST /api/projects` — 创建项目（含期次定义）
- `POST /api/projects/{id}/tranches/{seq}/deliverables` — 提交成果
- `POST /api/projects/{id}/tranches/{seq}/acceptance` — 验收决定
- `POST /api/projects/{id}/holds` / `POST /api/projects/{id}/holds/{holdId}/lift` — 建立/解除合规暂停
- `POST /api/projects/{id}/tranches/{seq}/disbursements` — 批准拨款（幂等）
- `POST /api/projects/{id}/disbursements/{did}/pay` — 支付确认
- `POST /api/projects/{id}/disbursements/{did}/revoke` — 撤销未支付拨款
- `POST /api/projects/{id}/disbursements/{did}/clawbacks` — 追回已支付拨款（幂等）

业务规则冲突返回 `409` 及错误说明，参数校验失败返回 `400`。

## 常用命令

运行测试：

    ./mvnw clean test

启动服务：

    ./mvnw spring-boot:run
