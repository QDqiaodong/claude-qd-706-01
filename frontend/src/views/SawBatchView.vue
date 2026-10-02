<template>
  <section class="panel">
    <div class="panel-head">
      <h3>锯切批次交接与产出谱系</h3>
      <span class="panel-sub">开锯冻结 · 逐片扫码交接 · 差异待核 · 收口按合格实产推进工单</span>
      <div class="panel-extra">
        <el-select v-model="status" clearable placeholder="批次状态" style="width: 140px" @change="reload">
          <el-option label="开锯中" value="OPEN" />
          <el-option label="差异待核" value="WAIT_CHECK" />
          <el-option label="已收口" value="CLOSED" />
        </el-select>
        <el-input v-model="keyword" clearable placeholder="批次号/工单/荒料" style="width: 190px"
                  @keyup.enter="reload" @clear="reload" />
        <el-button @click="reload">刷新</el-button>
        <el-button type="primary" @click="openCreate">开锯建批</el-button>
      </div>
    </div>

    <el-table :data="rows" border stripe size="small">
      <el-table-column prop="batchNo" label="批次号" width="140" />
      <el-table-column label="工单 / 荒料" min-width="210">
        <template #default="{ row }">
          <div>{{ row.orderNo }}</div>
          <div class="panel-sub">{{ row.blockCode }} · {{ row.sawTypeName }}</div>
        </template>
      </el-table-column>
      <el-table-column prop="team" label="班组" width="70" />
      <el-table-column label="冻结顺序" width="80">
        <template #default="{ row }">{{ row.planCount }} 片</template>
      </el-table-column>
      <el-table-column label="实产（成品/余料/锯耗）" width="160">
        <template #default="{ row }">
          <b :class="{ 'text-danger': row.liveFinished !== row.planCount }">{{ row.liveFinished }}</b>
          / {{ row.liveRemnant }} / {{ row.liveLoss }}
        </template>
      </el-table-column>
      <el-table-column label="待核差异" width="90">
        <template #default="{ row }">
          <el-badge v-if="row.openDiffCount > 0" :value="row.openDiffCount" type="danger">
            <el-tag size="small" type="danger">待核</el-tag>
          </el-badge>
          <span v-else-if="row.status === 'CLOSED'" class="panel-sub">已核清固化</span>
          <el-tag v-else size="small" type="success">无差异</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="batchTag(row.status)">{{ row.statusName }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="收口固化" width="150">
        <template #default="{ row }">
          <span v-if="row.status === 'CLOSED'" class="panel-sub">
            成品 {{ row.closedFinished }} · {{ row.closedBy }}
          </span>
          <span v-else class="panel-sub">未收口不计数</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDetail(row)">批次详情</el-button>
          <el-button v-if="row.status === 'OPEN'" link type="success" @click="openDetail(row)">交接</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="pager">
      <el-pagination background layout="total, sizes, prev, pager, next"
                     :total="total" :current-page="page" :page-size="size"
                     :page-sizes="[10, 20, 50]" @current-change="onPage" @size-change="onSize" />
    </div>

    <!-- ============ 开锯建批 ============ -->
    <el-dialog v-model="createVisible" title="开锯建立锯切批次" width="520px">
      <el-alert type="info" :closable="false" show-icon style="margin-bottom: 12px"
                title="建立瞬间将冻结当时的荒料、工单、锯切方式、班组与排板顺序，后续基础资料变化不改写。" />
      <el-form :model="createForm" label-width="110px">
        <el-form-item label="加工工单" required>
          <el-select v-model="createForm.orderId" filterable style="width: 100%" placeholder="选择待开锯工单">
            <el-option v-for="o in openableOrders" :key="o.id"
                       :label="o.orderNo + ' · ' + o.blockCode + ' · ' + o.sawTypeName + ' · 计划' + o.planCount"
                       :value="o.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="尺寸容差(cm)">
          <el-input-number v-model="createForm.toleranceCm" :min="0" :max="20" />
          <span class="panel-sub" style="margin-left: 8px">实际长宽与计划偏差超此值即差异</span>
        </el-form-item>
        <el-form-item label="开锯操作人">
          <el-input v-model="createForm.operator" placeholder="如 锯工-赵大锤" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="doCreate">冻结并建批</el-button>
      </template>
    </el-dialog>

    <!-- ============ 批次详情工作台 ============ -->
    <el-drawer v-model="detailVisible" size="86%" :title="detail.batchNo ? '批次 ' + detail.batchNo : '批次详情'">
      <div v-if="detail.id" class="batch-work">
        <!-- 冻结快照 + 收口结论 -->
        <el-descriptions :column="4" border size="small" class="freeze-box">
          <el-descriptions-item label="批次状态">
            <el-tag size="small" :type="batchTag(detail.status)">{{ detail.statusName }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="工单（冻结）">{{ detail.snapOrderNo }} · 计划 {{ detail.snapPlanCount }} 片</el-descriptions-item>
          <el-descriptions-item label="荒料（冻结）">{{ detail.snapBlockCode }}</el-descriptions-item>
          <el-descriptions-item label="锯切方式 / 班组">{{ detail.sawTypeName }} · {{ detail.team || '—' }}</el-descriptions-item>
          <el-descriptions-item label="冻结排板顺序">{{ detail.planCount }} 个顺序 · 容差 ±{{ detail.sizeToleranceCm }}cm</el-descriptions-item>
          <el-descriptions-item label="交接">{{ detail.handedOverBy || '—' }} · {{ fmt(detail.handedOverAt) }}</el-descriptions-item>
          <el-descriptions-item label="开锯 / 更新">{{ fmt(detail.createdAt) }} / {{ fmt(detail.updatedAt) }}</el-descriptions-item>
          <el-descriptions-item label="待核差异">
            <el-tag size="small" :type="detail.openDiffCount > 0 ? 'danger' : 'success'">
              {{ detail.openDiffCount }} 条待核 / {{ detail.resolvedDiffCount }} 条已核清
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item v-if="detail.status === 'CLOSED'" label="收口固化" :span="4">
            成品 <b>{{ detail.closedFinished }}</b> · 余料 {{ detail.closedRemnant }} · 锯耗 {{ detail.closedLoss }}
            · 作废 {{ detail.closedVoid }} 片｜{{ detail.closedBy }} · {{ fmt(detail.closedAt) }}
            <span v-if="detail.closedRemark">｜{{ detail.closedRemark }}</span>
          </el-descriptions-item>
        </el-descriptions>

        <!-- 操作条 -->
        <div class="work-actions">
          <el-button type="primary" plain :disabled="detail.status === 'CLOSED'"
                     @click="openRegister">逐片登记产出</el-button>
          <el-button type="success" plain :disabled="detail.status !== 'OPEN'"
                     @click="doHandover">完成交接 / 比对差异</el-button>
          <el-button type="warning" :disabled="detail.status !== 'WAIT_CHECK' || detail.openDiffCount > 0"
                     @click="doClose">收口固化谱系</el-button>
          <el-tag v-if="detail.status === 'WAIT_CHECK' && detail.openDiffCount > 0" type="danger" effect="plain">
            差异未核清，禁止收口；现场产出已保留，工单完成数暂不推进
          </el-tag>
          <el-tag v-if="detail.status === 'OPEN'" type="info" effect="plain">开锯登记中：逐片扫码后完成交接</el-tag>
          <el-tag v-if="detail.status === 'CLOSED'" type="success" effect="plain">谱系已固化，只可查看不可改</el-tag>
        </div>

        <el-tabs v-model="tab">
          <!-- 逐片谱系：计划顺序 → 实产 -->
          <el-tab-pane name="outputs">
            <template #label>逐片谱系（{{ detail.outputs.length }}）</template>
            <div class="panel-sub" style="margin: 4px 0 8px">
              LIVE 成品 {{ detail.liveFinished }} · 余料 {{ detail.liveRemnant }} · 锯耗 {{ detail.liveLoss }}
              · 作废 {{ detail.liveVoid }}；同一大板全局唯一归属，余料/锯耗/成品都回到本批核对。
            </div>
            <el-table :data="outputRows" border size="small">
              <el-table-column label="顺序" width="70">
                <template #default="{ row: o }">{{ o.seqNo == null ? '计划外' : o.seqNo }}</template>
              </el-table-column>
              <el-table-column label="计划尺寸" width="110">
                <template #default="{ row: o }">{{ planSize(o.seqNo) }}</template>
              </el-table-column>
              <el-table-column label="大板编号" width="130">
                <template #default="{ row: o }">
                  <b>{{ o.slabCode || '—' }}</b>
                  <el-tag v-if="o.referencedByLaterBatch" size="small" type="warning">返切来源</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="类型" width="100">
                <template #default="{ row: o }">
                  <el-tag size="small" :type="outputTag(o.outputType)">{{ o.outputTypeName }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="实际尺寸" width="120">
                <template #default="{ row: o }">
                  <span v-if="o.actualLengthCm">{{ o.actualLengthCm }}×{{ o.actualWidthCm }}cm</span>
                  <span v-else class="panel-sub">{{ o.note || '无尺寸' }}</span>
                  <el-tag v-if="o.sizeOutOfTolerance" size="small" type="danger" style="margin-left:4px">
                    超差 {{ o.sizeDeviation }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="operator" label="操作人" width="100" />
              <el-table-column label="产出/登记时间" width="150">
                <template #default="{ row: o }">{{ fmt(o.producedAt) }}</template>
              </el-table-column>
              <el-table-column label="扫码令牌 / 备注" min-width="180">
                <template #default="{ row: o }">
                  <span class="panel-sub">{{ o.clientToken }}</span>
                  <div v-if="o.note">{{ o.note }}</div>
                  <div v-if="o.status === 'VOID'" class="text-danger">已作废：{{ o.voidReason }}（{{ o.voidBy }}）</div>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="190" fixed="right">
                <template #default="{ row: o }">
                  <template v-if="detail.status !== 'CLOSED' && o.status === 'LIVE'">
                    <el-button link type="primary" @click="openCorrect(o)">更正</el-button>
                    <el-button link type="warning" @click="openReassign(o)">改派</el-button>
                    <el-button link type="danger" @click="openVoid(o)">作废</el-button>
                  </template>
                  <span v-else class="panel-sub">—</span>
                </template>
              </el-table-column>
            </el-table>

            <div class="panel-sub" style="margin-top: 12px">冻结排板顺序（建立后不可变）：</div>
            <el-table :data="detail.plans" border size="small" style="margin-top: 6px">
              <el-table-column prop="seqNo" label="顺序" width="80" />
              <el-table-column label="计划尺寸" width="140">
                <template #default="{ row: p }">{{ p.planLengthCm }}×{{ p.planWidthCm }}cm</template>
              </el-table-column>
              <el-table-column prop="slabCode" label="排板预占大板" width="140" />
              <el-table-column label="实产片数">
                <template #default="{ row: p }">
                  <el-tag size="small" :type="p.outputCount === 1 ? 'success' : (p.outputCount === 0 ? 'danger' : 'warning')">
                    {{ p.outputCount }} 片
                  </el-tag>
                </template>
              </el-table-column>
            </el-table>
          </el-tab-pane>

          <!-- 差异待核 -->
          <el-tab-pane name="diffs">
            <template #label>
              差异待核
              <el-badge v-if="detail.openDiffCount" :value="detail.openDiffCount" type="danger" style="margin-left:6px" />
            </template>
            <el-alert type="warning" :closable="false" show-icon style="margin-bottom: 8px"
                      title="差异进入待核而不是丢弃现场记录：允许班组先完成交接，但差异核清前批次不能收口、工单完成数不增加。" />
            <el-table :data="detail.diffs" border size="small">
              <el-table-column label="类型" width="130">
                <template #default="{ row: d }">
                  <el-tag size="small" :type="d.status === 'OPEN' ? 'danger' : 'info'">{{ d.diffTypeName }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="顺序" width="70">
                <template #default="{ row: d }">{{ d.seqNo || '汇总' }}</template>
              </el-table-column>
              <el-table-column prop="slabCode" label="大板" width="120" />
              <el-table-column prop="detail" label="差异描述（计划 vs 实产）" min-width="320" />
              <el-table-column label="核清" width="220">
                <template #default="{ row: d }">
                  <template v-if="d.status === 'OPEN'">
                    <el-button link type="primary" @click="openResolve(d, 'CONFIRM')">确认实际</el-button>
                    <el-button v-if="d.slabId" link type="danger" @click="openResolve(d, 'VOID')">作废误登</el-button>
                  </template>
                  <div v-else class="panel-sub">
                    {{ d.resolutionName }} · {{ d.resolvedBy }}<br />{{ d.resolveReason }}
                  </div>
                </template>
              </el-table-column>
            </el-table>
          </el-tab-pane>

          <!-- 更正留痕 -->
          <el-tab-pane name="changes" :label="'更正留痕（' + detail.changes.length + '）'">
            <el-timeline>
              <el-timeline-item v-for="c in detail.changes" :key="c.id"
                                :timestamp="fmt(c.createdAt) + ' · ' + (c.operator || '')" placement="top">
                <el-tag size="small" :type="changeTag(c.changeType)">{{ c.changeTypeName }}</el-tag>
                <span v-if="c.fromBatchNo" style="margin-left:8px">
                  {{ c.fromBatchNo }} → {{ c.toBatchNo }}
                </span>
                <div class="panel-sub" style="margin-top: 2px">产出#{{ c.outputId }}｜{{ c.reason }}</div>
                <div v-if="c.beforeJson || c.afterJson" class="change-json">
                  <span v-if="c.beforeJson" class="json-before">前：{{ c.beforeJson }}</span>
                  <span v-if="c.afterJson" class="json-after">后：{{ c.afterJson }}</span>
                </div>
              </el-timeline-item>
            </el-timeline>
          </el-tab-pane>

          <!-- 生命周期 -->
          <el-tab-pane name="events" :label="'生命周期（' + detail.events.length + '）'">
            <el-timeline>
              <el-timeline-item v-for="e in detail.events" :key="e.id"
                                :timestamp="fmt(e.createdAt) + ' · ' + (e.operator || '')" placement="top">
                <el-tag size="small">{{ e.eventTypeName }}</el-tag>
                <span class="panel-sub" style="margin-left: 8px">{{ e.fromStatusName || '—' }} → {{ e.toStatusName }}</span>
                <div style="margin-top: 2px">{{ e.content }}</div>
              </el-timeline-item>
            </el-timeline>
          </el-tab-pane>
        </el-tabs>
      </div>
    </el-drawer>

    <!-- ============ 逐片登记产出 ============ -->
    <el-dialog v-model="registerVisible" title="逐片登记产出" width="560px">
      <el-alert type="info" :closable="false" show-icon style="margin-bottom: 10px"
                title="断网重试请用同一扫码令牌（自动幂等，不会生成两份）；同一大板已在其它批次登记时会提示当前归属，不覆盖。" />
      <el-form :model="reg" label-width="110px">
        <el-form-item label="扫码令牌" required>
          <el-input v-model="reg.clientToken" placeholder="PDA 设备号+流水，如 DEV07-20261002-0007" />
        </el-form-item>
        <el-form-item label="产出类型" required>
          <el-radio-group v-model="reg.outputType">
            <el-radio label="FINISHED">成品</el-radio>
            <el-radio label="REMNANT">可返切余料</el-radio>
            <el-radio label="SAW_LOSS">锯耗</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="大板编号" :required="reg.outputType !== 'SAW_LOSS'">
          <el-input v-model="reg.slabCode" :disabled="reg.outputType === 'SAW_LOSS'"
                    placeholder="新编号自动建档；已有编号必须属于本批荒料" />
        </el-form-item>
        <el-form-item label="对应顺序">
          <el-select v-model="reg.seqNo" clearable style="width: 100%" placeholder="计划外余料可留空">
            <el-option v-for="p in (detail.plans || [])" :key="p.id"
                       :label="'顺序 ' + p.seqNo + '（计划 ' + p.planLengthCm + '×' + p.planWidthCm + 'cm）'"
                       :value="p.seqNo" />
          </el-select>
        </el-form-item>
        <el-form-item label="实际长/宽/厚">
          <el-input-number v-model="reg.actualLengthCm" :min="0" :step="5" />
          <el-input-number v-model="reg.actualWidthCm" :min="0" :step="5" style="margin-left:8px" />
          <el-input-number v-model="reg.thicknessCm" :min="0.5" :step="0.5" :precision="1" style="margin-left:8px" />
        </el-form-item>
        <el-form-item label="操作人"><el-input v-model="reg.operator" /></el-form-item>
        <el-form-item label="产出时间">
          <el-input v-model="reg.producedAt" placeholder="留空取当前；格式 2026-10-02 10:00:00" />
        </el-form-item>
        <el-form-item label="锯耗/备注">
          <el-input v-model="reg.note" type="textarea" :rows="2"
                    :placeholder="reg.outputType === 'SAW_LOSS'
                      ? '建议填损耗原因（暗裂避让/崩板）；留空交接会挂「未解释锯耗」差异'
                      : '现场备注（可空）'" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="registerVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="doRegister">登记</el-button>
      </template>
    </el-dialog>

    <!-- ============ 差异核清 ============ -->
    <el-dialog v-model="resolveVisible" title="差异逐项核清" width="520px">
      <el-alert :title="activeDiff.detail" type="warning" :closable="false" style="margin-bottom: 10px" />
      <el-form label-width="90px">
        <el-form-item label="核清方式">
          <el-tag>{{ resolveForm.resolution === 'CONFIRM' ? '确认实际结果' : '作废误登产出' }}</el-tag>
        </el-form-item>
        <el-form-item label="原因" required>
          <el-input v-model="resolveForm.reason" type="textarea" :rows="3"
                    placeholder="必须填写原因；变更前后内容会一并留痕" />
        </el-form-item>
        <el-form-item label="负责人"><el-input v-model="resolveForm.operator" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="resolveVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="doResolve">确认核清</el-button>
      </template>
    </el-dialog>

    <!-- ============ 更正登记 ============ -->
    <el-dialog v-model="correctVisible" title="更正登记内容（保留前后值）" width="520px">
      <el-form label-width="100px">
        <el-form-item label="大板">{{ activeOutput.slabCode }}</el-form-item>
        <el-form-item label="顺序">
          <el-select v-model="correctForm.seqNo" clearable style="width: 100%">
            <el-option v-for="p in (detail.plans || [])" :key="p.id" :label="'顺序 ' + p.seqNo" :value="p.seqNo" />
          </el-select>
        </el-form-item>
        <el-form-item label="实际长/宽">
          <el-input-number v-model="correctForm.actualLengthCm" :min="0" :step="1" />
          <el-input-number v-model="correctForm.actualWidthCm" :min="0" :step="1" style="margin-left:8px" />
        </el-form-item>
        <el-form-item label="备注/锯耗原因"><el-input v-model="correctForm.note" type="textarea" :rows="2" /></el-form-item>
        <el-form-item label="更正原因" required>
          <el-input v-model="correctForm.reason" type="textarea" :rows="2" placeholder="超差改尺、顺序错登等" />
        </el-form-item>
        <el-form-item label="操作人"><el-input v-model="correctForm.operator" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="correctVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="doCorrect">保存更正</el-button>
      </template>
    </el-dialog>

    <!-- ============ 跨批改派 ============ -->
    <el-dialog v-model="reassignVisible" title="跨批次更正归属" width="520px">
      <el-alert type="warning" :closable="false" show-icon style="margin-bottom: 10px"
                title="先到记录不会被静默覆盖：只有负责人显式改派并填写原因，产出才转到真正归属批次，双方差异重新比对。" />
      <el-form label-width="100px">
        <el-form-item label="产出大板">{{ activeOutput.slabCode }}（当前 {{ detail.batchNo }} 顺序{{ activeOutput.seqNo }}）</el-form-item>
        <el-form-item label="目标批次" required>
          <el-select v-model="reassignForm.targetBatchId" style="width: 100%">
            <el-option v-for="b in reassignTargets" :key="b.id"
                       :label="b.batchNo + ' · ' + b.orderNo + ' · ' + b.statusName" :value="b.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="目标顺序">
          <el-select v-model="reassignForm.targetSeqNo" clearable style="width: 100%">
            <el-option v-for="p in targetPlans" :key="p.id" :label="'顺序 ' + p.seqNo" :value="p.seqNo" />
          </el-select>
        </el-form-item>
        <el-form-item label="改派原因" required>
          <el-input v-model="reassignForm.reason" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="操作人"><el-input v-model="reassignForm.operator" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="reassignVisible = false">取消</el-button>
        <el-button type="warning" :loading="saving" @click="doReassign">确认改派</el-button>
      </template>
    </el-dialog>

    <!-- ============ 作废 ============ -->
    <el-dialog v-model="voidVisible" title="作废误登产出" width="480px">
      <el-alert type="error" :closable="false" show-icon style="margin-bottom: 10px"
                title="作废只置位不物理删除；已被后续批次作为返切来源引用的产出禁止作废。" />
      <el-form label-width="90px">
        <el-form-item label="产出">{{ activeOutput.slabCode || activeOutput.outputTypeName }} · 顺序{{ activeOutput.seqNo }}</el-form-item>
        <el-form-item label="作废原因" required>
          <el-input v-model="voidForm.reason" type="textarea" :rows="3" />
        </el-form-item>
        <el-form-item label="操作人"><el-input v-model="voidForm.operator" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="voidVisible = false">取消</el-button>
        <el-button type="danger" :loading="saving" @click="doVoid">确认作废</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { orderApi, sawApi } from '../api'

const route = useRoute()

const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const status = ref('')
const keyword = ref('')
const saving = ref(false)

const createVisible = ref(false)
const createForm = ref({ orderId: null, toleranceCm: 2, operator: '' })
const openableOrders = ref([])

const detailVisible = ref(false)
const detail = ref({})
const tab = ref('outputs')

const registerVisible = ref(false)
const reg = ref({})
const resolveVisible = ref(false)
const activeDiff = ref({})
const resolveForm = ref({})
const correctVisible = ref(false)
const activeOutput = ref({})
const correctForm = ref({})
const reassignVisible = ref(false)
const reassignForm = ref({})
const allBatches = ref([])
const voidVisible = ref(false)
const voidForm = ref({})

const outputRows = computed(() =>
  [...(detail.value.outputs || [])].sort((a, b) => {
    const sa = a.seqNo == null ? 999999 : a.seqNo
    const sb = b.seqNo == null ? 999999 : b.seqNo
    return sa - sb || a.id - b.id
  }))

const reassignTargets = computed(() =>
  allBatches.value.filter(b => b.id !== detail.value.id && b.status !== 'CLOSED'))

const targetPlans = computed(() => {
  const b = allBatches.value.find(x => x.id === reassignForm.value.targetBatchId)
  return b && b.plans ? b.plans : []
})

function batchTag(s) {
  return { OPEN: 'info', WAIT_CHECK: 'danger', CLOSED: 'success' }[s] || 'info'
}
function outputTag(t) {
  return { FINISHED: 'success', REMNANT: 'warning', SAW_LOSS: 'info' }[t] || ''
}
function changeTag(t) {
  return { REGISTER: 'info', CONFIRM: 'success', REASSIGN: 'warning', VOID: 'danger', FIX_CORRECT: 'primary' }[t] || ''
}
function fmt(t) {
  return t ? String(t).replace('T', ' ').slice(0, 19) : '—'
}
function planSize(seq) {
  if (seq == null) return '—'
  const p = (detail.value.plans || []).find(x => x.seqNo === seq)
  return p ? `${p.planLengthCm}×${p.planWidthCm}cm` : '—'
}

async function reload() {
  const params = { page: page.value, size: size.value, orderBy: 'time', desc: true }
  if (status.value) params.status = status.value
  if (keyword.value) params.keyword = keyword.value
  const res = await sawApi.page(params)
  rows.value = res.rows
  total.value = res.total
}
function onPage(p) { page.value = p; reload() }
function onSize(s) { size.value = s; page.value = 1; reload() }

async function refreshDetail() {
  if (!detail.value.id) return
  detail.value = await sawApi.detail(detail.value.id)
}

async function openCreate() {
  openableOrders.value = await orderApi.list()
  createForm.value = {
    orderId: openableOrders.value[0] ? openableOrders.value[0].id : null,
    toleranceCm: 2, operator: ''
  }
  createVisible.value = true
}

/** 统一把 409 冲突弹成「当前归属」而不是静默错误；幂等命中只提示不报错。 */
async function runWithConflict(action, okMsg) {
  saving.value = true
  try {
    await action()
    ElMessage.success(okMsg)
    await reload()
    await refreshDetail()
    return true
  } catch (e) {
    if (e.isConflict) {
      const c = e.conflict?.current || {}
      if (c.idempotent) {
        ElMessageBox.alert(
          `该扫码此前已登记：批次 ${c.batchNo || ''}，大板 ${c.slabCode || ''}。
断网重试未重复生成产出，已为你定位到首次登记。`,
          '重复扫码 / 断网重试（幂等）', { type: 'info' })
      } else {
        ElMessageBox.alert(
          e.message + `\n\n当前归属：批次 ${c.batchNo || ''}，状态 ${c.batchStatus || ''}，顺序 ${c.seqNo ?? ''}，登记人 ${c.operator || ''}`,
          '跨批次占用，保留先到记录', { type: 'warning' })
      }
      await reload()
      await refreshDetail()
    }
    return false
  } finally {
    saving.value = false
  }
}

async function doCreate() {
  if (!createForm.value.orderId) { ElMessage.warning('请选择工单'); return }
  saving.value = true
  try {
    const b = await sawApi.create(createForm.value)
    ElMessage.success('批次已建立，排板顺序已冻结')
    createVisible.value = false
    await reload()
    openDetail({ id: b.id })
  } finally { saving.value = false }
}

async function openDetail(row) {
  detail.value = await sawApi.detail(row.id)
  allBatches.value = await sawApi.page({ page: 1, size: 200, orderBy: 'time', desc: true }).then(r => r.rows)
  detailVisible.value = true
  tab.value = detail.value.openDiffCount > 0 && detail.value.status === 'WAIT_CHECK' ? 'diffs' : 'outputs'
}

function openRegister() {
  reg.value = {
    clientToken: 'DEV07-' + new Date().toISOString().slice(0, 10).replace(/-/g, '') + '-' + String(Date.now()).slice(-6),
    outputType: 'FINISHED', slabCode: '', seqNo: null,
    actualLengthCm: null, actualWidthCm: null, thicknessCm: 2.0,
    operator: '', producedAt: '', note: ''
  }
  registerVisible.value = true
}

function doRegister() {
  runWithConflict(() => sawApi.registerOutput(detail.value.id, reg.value), '产出已逐片登记')
    .then(ok => { if (ok) registerVisible.value = false })
}

function doHandover() {
  ElMessageBox.prompt('交接操作人', '完成交接并比对计划与实产', {
    confirmButtonText: '完成交接', cancelButtonText: '取消', inputPattern: /\S+/, inputErrorMessage: '请填写操作人'
  }).then(({ value }) => runWithConflict(() => sawApi.handover(detail.value.id, { operator: value }),
      '交接完成，差异已识别')).catch(() => {})
}

function doClose() {
  ElMessageBox.prompt('收口负责人 / 结论（可留空）', '差异已核清，收口将固化谱系并推进工单', {
    confirmButtonText: '确认收口', cancelButtonText: '取消',
    inputValue: '负责人：'
  }).then(({ value }) => runWithConflict(
    () => sawApi.close(detail.value.id, { operator: value || '负责人', remark: '' }),
    '已收口：谱系固化，工单按合格实产推进')).catch(() => {})
}

function openResolve(d, resolution) {
  activeDiff.value = d
  resolveForm.value = { resolution, reason: '', operator: '' }
  resolveVisible.value = true
}
function doResolve() {
  if (!resolveForm.value.reason.trim()) { ElMessage.warning('必须填写核清原因'); return }
  runWithConflict(() => sawApi.resolveDiff(activeDiff.value.id, resolveForm.value), '差异已核清')
    .then(ok => { if (ok) resolveVisible.value = false })
}

function openCorrect(o) {
  activeOutput.value = o
  correctForm.value = {
    seqNo: o.seqNo, actualLengthCm: o.actualLengthCm, actualWidthCm: o.actualWidthCm,
    note: o.note || '', reason: '', operator: ''
  }
  correctVisible.value = true
}
function doCorrect() {
  if (!correctForm.value.reason.trim()) { ElMessage.warning('必须填写更正原因'); return }
  runWithConflict(() => sawApi.correctOutput(activeOutput.value.id, correctForm.value), '已更正并留痕')
    .then(ok => { if (ok) correctVisible.value = false })
}

function openReassign(o) {
  activeOutput.value = o
  reassignForm.value = { targetBatchId: null, targetSeqNo: null, reason: '', operator: '' }
  reassignVisible.value = true
}
function doReassign() {
  if (!reassignForm.value.targetBatchId) { ElMessage.warning('请选择目标批次'); return }
  if (!reassignForm.value.reason.trim()) { ElMessage.warning('必须填写改派原因'); return }
  runWithConflict(() => sawApi.reassignOutput(activeOutput.value.id, reassignForm.value), '已跨批改派，双方差异重新比对')
    .then(ok => { if (ok) reassignVisible.value = false })
}

function openVoid(o) {
  activeOutput.value = o
  voidForm.value = { reason: '', operator: '' }
  voidVisible.value = true
}
function doVoid() {
  if (!voidForm.value.reason.trim()) { ElMessage.warning('必须填写作废原因'); return }
  runWithConflict(() => sawApi.voidOutput(activeOutput.value.id, voidForm.value), '误登产出已作废')
    .then(ok => { if (ok) voidVisible.value = false })
}

onMounted(async () => {
  await reload()
  if (route.query.batchId) {
    openDetail({ id: Number(route.query.batchId) })
  }
})
</script>

<style scoped>
.batch-work { padding: 0 4px; }
.freeze-box { margin-bottom: 10px; }
.work-actions { display: flex; align-items: center; gap: 10px; margin: 8px 0 12px; flex-wrap: wrap; }
.text-danger { color: #e53935; }
.change-json { display: flex; flex-direction: column; font-size: 12px; margin-top: 2px; }
.json-before { color: #90a4ae; }
.json-after { color: #455a64; }
</style>
