# 模块八：锯切批次交接与产出谱系

把加工工单从「计划板数」推进到「可追溯的逐片实产」。班组开锯建批并冻结现场口径，交接时逐片扫码登记，
系统比较计划与实产、把差异挂起待核，只有差异全部核清后才能收口——收口一次性固化产出谱系并按
**合格实产**推进工单完成数。

## 一、数据模型（mysql/schema.sql，6 张表）

| 表 | 作用 | 关键约束 |
|---|---|---|
| `mill_saw_batch` | 批次主表 + 开锯冻结快照（工单号/荒料/锯切方式/班组/计划板数/容差），状态 `OPEN→WAIT_CHECK→CLOSED` | `version` 乐观锁 |
| `mill_saw_plan` | 开锯时从排板方案复制的顺序与计划尺寸，建立后不可变 | `(batch_id, seq_no)` 唯一 |
| `mill_saw_output` | 逐片产出：FINISHED 成品 / REMNANT 可返切余料 / SAW_LOSS 锯耗 | `client_token` 唯一（重试幂等）；生成列 `live_slab_id` 全局唯一（一板只属一批） |
| `mill_saw_diff` | 差异待核：缺号/重复号/尺寸超差/数量不符/未解释锯耗 | 生成列 `open_key` 唯一；OPEN 阻断收口 |
| `mill_saw_change` | 逐片更正审计：登记/确认/改派/作废，前后 JSON + 原因 | 只追加 |
| `mill_saw_event` | 批次生命周期：CREATE/HANDOVER/CLOSE | 只追加 |

生成列在数据库层兜底并发，不靠「先查后插」：

- `live_slab_id = CASE WHEN status='LIVE' AND slab_id IS NOT NULL THEN slab_id ELSE NULL END`
  + 唯一索引 ⇒ 同一块大板的有效产出只能属于一个批次；作废（status=VOID）后让出归属。
- `open_key` ⇒ 同一未核差异不重复挂；核清后置 NULL，已核历史保留。

## 二、业务流转

```
开锯 create ──OPEN──▶ 逐片登记 outputs（可断网重试/重复扫码）
      │                     │ client_token 唯一 → 幂等，不生成两份
      │                     │ live_slab_id 唯一 → 跨批次先到先得，后到方 409 看当前归属
      ▼                     ▼
  handover 交接 ──WAIT_CHECK──▶ 计划 vs 实产比对，差异进 mill_saw_diff（现场记录不丢弃）
                                    │
              负责人 resolveDiff（CONFIRM/VOID，必填原因）
              correctOutput（改尺寸/顺序/锯耗原因，自动核清，留前后值）
              reassignOutput（跨批改派，双方差异重算）/ voidOutput（被后续批次引用禁止作废）
                                    ▼  open_diff_count = 0
                               close 收口 ──CLOSED
                                    │ 单事务：行锁批次 + 锁工单
                                    │ 固化 closed_finished/remnant/loss/void
                                    ▼
                  工单 done_count = 该工单所有 CLOSED 批次的 closed_finished 之和；达计划才 DONE
```

### 差异识别口径（容差取批次冻结值，默认 ±2cm）

- **缺号 MISSING_NO**：冻结顺序在 LIVE 产出中找不到任何片（成品/余料/锯耗都没有）。
- **重复号 DUP_NO**：同一顺序出现两片及以上挂大板的产出。
- **尺寸超差 SIZE_TOL**：实际长/宽与计划任一偏差 > 容差。
- **数量不符 COUNT_MISMATCH**：LIVE 成品数 ≠ 冻结顺序数（汇总一条）。
- **未解释锯耗 SAW_LOSS_UNEXPLAINED**：登记了 SAW_LOSS 但 `note` 为空。

差异只追加、可核清不复活；班组可以先交接，但未核清前批次不能收口、工单完成数不增加。

### 收口一致性（并发 / 重复点击 / 中途失败）

`close` 在**单事务**内：`SELECT … FOR UPDATE` 锁批次行 → 复核 0 条 OPEN 差异 →
写 CLOSED 与固化计数（`@Version` 乐观锁）→ 重算工单完成数与状态 → 追加 CLOSE 事件。
两人同时收口在锁上串行，第二个读到 CLOSED 直接返回 409 + 已收口结果；任一异常整事务回滚，
不会出现「批次 CLOSED 但产出/工单没跟上」或完成数半成品。

### 与旧工单/旧入口的关系

- 已有批次的工单：禁用手工「完成+N / 完工」，完成数只能由收口按合格实产推进。
- 无批次的旧工单：`batchManaged=false`，前端明确标「待补交接」，旧手工进度与查看保留。
- 余料、锯耗、作废片**不计入**工单完成数；只有未作废 FINISHED 计入。

## 三、接口（前缀 /api）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/saw-batches` | 批次分页（status/keyword/orderId） |
| GET | `/saw-batches/{id}` | 批次详情：冻结顺序 + 逐片产出 + 差异 + 更正 + 留痕 |
| GET | `/saw-batches/by-order/{orderId}` | 工单下各批次 |
| GET | `/saw-batches/lineage-by-slab?slabId=` | 大板反查所属批次与历次更正 |
| POST | `/saw-batches` | 开锯建批，冻结排板顺序 |
| POST | `/saw-batches/{id}/outputs` | 逐片登记（必带 clientToken） |
| POST | `/saw-batches/{id}/handover` | 交接，进入差异待核 |
| POST | `/saw-batches/diffs/{diffId}/resolve` | 差异核清（必填原因） |
| POST | `/saw-batches/outputs/{outputId}/correct` | 更正尺寸/顺序/原因 |
| POST | `/saw-batches/outputs/{outputId}/reassign` | 跨批改派 |
| POST | `/saw-batches/outputs/{outputId}/void` | 作废（被引用拒绝） |
| POST | `/saw-batches/{id}/close` | 收口固化并推进工单 |

409 响应体固定为 `{ ok:false, message, data:{ conflictType, current } }`，
`current.idempotent=true` 表示断网重试命中首次结果；否则 `current` 为先到归属（批次号/顺序/登记人）。

## 四、验收

种子数据（schema.sql 末尾）：

- `SB-2026-0001` 计划吻合已收口，4 片，工单 11 完成数 4。
- `SB-2026-0002` 差异待核：缺号(8)/超差(顺序6 -5cm)/未解释锯耗(顺序7)/数量不符，共 4 条 OPEN，收口被阻断。
- `SB-2026-0003` 零差异已交接待收口，用于两人同时收口演示。
- 工单 1~10 无批次，标记「待补交接」，保留旧口径。

一键验收脚本（需要先 `./start.sh` 起服务）：

```bash
BASE=http://localhost:8080 ./acceptance-saw.sh
```

覆盖：计划吻合、数量/差异阻断、跨批次重复（409 当前归属）、离线重试幂等、
差异更正（空原因被拒/自动核清/留痕）、并发收口（恰一成一冲突）、旧工单兼容。
脚本会推进批次 2、3 并新建 1~2 个演示产出，重跑前先 `./nuke.sh && ./start.sh` 重建。
