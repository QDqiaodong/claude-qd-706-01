<template>
  <section class="panel">
    <div class="panel-head">
      <h3>加工工单</h3>
      <span class="panel-sub">锯切方式 · 计划板数 / 已完成板数 · 进度条</span>
      <div class="panel-extra">
        <el-select v-model="sawType" clearable placeholder="锯切方式" style="width: 130px" @change="load">
          <el-option label="框架锯" value="FRAME" />
          <el-option label="圆盘锯" value="DISC" />
          <el-option label="绳锯" value="WIRE" />
          <el-option label="组合锯" value="COMBO" />
        </el-select>
        <el-select v-model="status" clearable placeholder="工单状态" style="width: 130px" @change="load">
          <el-option label="待排产" value="PENDING" />
          <el-option label="加工中" value="PROCESSING" />
          <el-option label="已完工" value="DONE" />
          <el-option label="已结算" value="SETTLED" />
        </el-select>
        <el-button @click="load">刷新</el-button>
        <el-button type="primary" @click="openCreate">新建工单</el-button>
      </div>
    </div>

    <el-table :data="rows" border stripe size="small">
      <el-table-column prop="orderNo" label="工单号" width="140" />
      <el-table-column prop="blockCode" label="荒料" width="130" />
      <el-table-column label="石种" width="90">
        <template #default="{ row }">
          <el-tag size="small" effect="plain">{{ row.stoneTypeName }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="sawTypeName" label="锯切方式" width="100" />
      <el-table-column label="板数（已完成/计划）" width="150">
        <template #default="{ row }">
          <b>{{ row.doneCount }}</b> / {{ row.planCount }}
          <el-tooltip v-if="row.batchManaged" content="完成数由已收口批次的合格实产推进，未核差异不计数" placement="top">
            <el-tag size="small" type="success" effect="plain" style="margin-left:4px">批次实产</el-tag>
          </el-tooltip>
          <el-tooltip v-else content="该工单尚无锯切批次记录，可按旧方式登记；请补做逐片交接" placement="top">
            <el-tag size="small" type="warning" effect="plain" style="margin-left:4px">待补交接</el-tag>
          </el-tooltip>
        </template>
      </el-table-column>
      <el-table-column label="批次谱系" width="150">
        <template #default="{ row }">
          <template v-if="row.batchManaged">
            <el-button link type="primary" @click="openBatches(row)">{{ row.batchCount }} 个批次</el-button>
            <el-tag v-if="row.openDiffCount > 0" size="small" type="danger">{{ row.openDiffCount }} 差异</el-tag>
            <el-tag v-else size="small" type="success">已核 {{ row.closedBatchCount }}</el-tag>
          </template>
          <span v-else class="panel-sub">无批次（旧工单）</span>
        </template>
      </el-table-column>
      <el-table-column label="进度" min-width="200">
        <template #default="{ row }">
          <el-progress :percentage="row.progress" :stroke-width="14" :color="barColor(row.progress)"
                       :format="() => row.progress + '%'" />
        </template>
      </el-table-column>
      <el-table-column prop="startDate" label="开工日期" width="110" />
      <el-table-column prop="finishDate" label="完工日期" width="110">
        <template #default="{ row }">{{ row.finishDate || '—' }}</template>
      </el-table-column>
      <el-table-column prop="team" label="班组" width="80" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="tagType(row.status)">{{ row.statusName }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="260" fixed="right">
        <template #default="{ row }">
          <template v-if="!row.batchManaged">
            <el-button link type="primary" @click="doStart(row)" v-if="row.status === 'PENDING'">开工</el-button>
            <el-button link type="success" @click="doProgress(row)"
                       v-if="row.status === 'PENDING' || row.status === 'PROCESSING'">完成+1</el-button>
            <el-button link type="warning" @click="doFinish(row)"
                       v-if="row.status === 'PENDING' || row.status === 'PROCESSING'">完工</el-button>
          </template>
          <el-button v-else link type="primary" @click="openBatches(row)">批次交接</el-button>
          <el-button link @click="doSettle(row)" v-if="row.status === 'DONE'">结算</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="table-footer">
      <span>工单 <b>{{ total }}</b> 张</span>
      <span>已完工板数 <b>{{ doneTotal }}</b> 片</span>
    </div>

    <div class="pager">
      <el-pagination background layout="total, sizes, prev, pager, next"
                     :total="total" :current-page="page" :page-size="size"
                     :page-sizes="[10, 20, 50]" @current-change="onPage" @size-change="onSize" />
    </div>
  </section>

  <el-dialog v-model="createVisible" title="新建加工工单" width="500px">
    <el-form :model="form" label-width="100px">
      <el-form-item label="工单号"><el-input v-model="form.orderNo" placeholder="如 CO-2026-0013" /></el-form-item>
      <el-form-item label="荒料">
        <el-select v-model="form.blockId" filterable style="width: 100%">
          <el-option v-for="b in blocks" :key="b.id" :label="b.blockCode + ' · ' + b.stoneTypeName" :value="b.id" />
        </el-select>
      </el-form-item>
      <el-form-item label="锯切方式">
        <el-select v-model="form.sawType" style="width: 100%">
          <el-option label="框架锯" value="FRAME" />
          <el-option label="圆盘锯" value="DISC" />
          <el-option label="绳锯" value="WIRE" />
          <el-option label="组合锯" value="COMBO" />
        </el-select>
      </el-form-item>
      <el-form-item label="计划板数"><el-input-number v-model="form.planCount" :min="1" :step="1" /></el-form-item>
      <el-form-item label="班组">
        <el-select v-model="form.team" style="width: 100%">
          <el-option label="甲班" value="甲班" />
          <el-option label="乙班" value="乙班" />
          <el-option label="丙班" value="丙班" />
        </el-select>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="createVisible = false">取消</el-button>
      <el-button type="primary" @click="doCreate">创建</el-button>
    </template>
  </el-dialog>

  <!-- 工单的批次谱系：各批次 / 待核差异 / 逐片来源 -->
  <el-drawer v-model="batchDrawerVisible" size="70%"
             :title="batchOrder.id ? '工单 ' + batchOrder.orderNo + ' · 批次谱系' : '批次谱系'">
    <div v-if="batchOrder.id">
      <el-alert
        :title="batchOrder.batchManaged
          ? `已按锯切批次交接：${batchOrder.batchCount} 个批次，已收口 ${batchOrder.closedBatchCount} 个，固化合格实产 ${batchOrder.closedFinishedTotal} 片；待核差异 ${batchOrder.openDiffCount} 条，核清前不计完成数。`
          : '该工单尚无任何锯切批次记录（旧工单），仍可按原方式查看与手工登记；建议补做逐片交接后再收口。'"
        :type="batchOrder.openDiffCount > 0 ? 'warning' : (batchOrder.batchManaged ? 'success' : 'info')"
        :closable="false" show-icon style="margin-bottom: 12px" />

      <div v-if="!orderBatches.length" class="panel-sub">该工单暂无批次，可到「锯切批次」页开锯建批。</div>

      <el-collapse v-model="activeBatchIds" v-for="b in orderBatches" :key="b.id" style="margin-bottom: 10px">
        <el-collapse-item :name="b.id">
          <template #title>
            <span style="margin-right: 10px"><b>{{ b.batchNo }}</b></span>
            <el-tag size="small" :type="batchTagType(b.status)" style="margin-right: 8px">{{ b.statusName }}</el-tag>
            <span class="panel-sub" style="margin-right: 12px">
              冻结 {{ b.planCount }} 顺序 · LIVE 成品 {{ b.liveFinished }}/余料 {{ b.liveRemnant }}/锯耗 {{ b.liveLoss }}
            </span>
            <el-tag v-if="b.openDiffCount > 0" size="small" type="danger">{{ b.openDiffCount }} 待核</el-tag>
            <el-tag v-if="b.status === 'CLOSED'" size="small" type="success">固化成品 {{ b.closedFinished }}</el-tag>
          </template>

          <div class="panel-sub" style="margin: 4px 0 6px">
            荒料 {{ b.blockCode }} · {{ b.sawTypeName }} · {{ b.team }} · 开锯 {{ b.createdAt?.slice(0, 16) }}
          </div>

          <el-table :data="batchOutputs(b)" border size="small">
            <el-table-column label="顺序" width="70">
              <template #default="{ row: o }">{{ o.seqNo == null ? '计划外' : o.seqNo }}</template>
            </el-table-column>
            <el-table-column prop="slabCode" label="大板编号" width="130" />
            <el-table-column label="类型" width="90">
              <template #default="{ row: o }">
                <el-tag size="small" :type="o.outputType === 'FINISHED' ? 'success' : (o.outputType === 'REMNANT' ? 'warning' : 'info')">
                  {{ o.outputTypeName }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="实际尺寸" width="120">
              <template #default="{ row: o }">
                {{ o.actualLengthCm ? o.actualLengthCm + '×' + o.actualWidthCm + 'cm' : '—' }}
                <el-tag v-if="o.sizeOutOfTolerance" size="small" type="danger">超差</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="operator" label="操作人" width="90" />
            <el-table-column label="时间" width="150">
              <template #default="{ row: o }">{{ (o.producedAt || '').slice(0, 19).replace('T', ' ') }}</template>
            </el-table-column>
            <el-table-column label="状态 / 备注" min-width="140">
              <template #default="{ row: o }">
                <el-tag v-if="o.status === 'VOID'" size="small" type="danger">已作废</el-tag>
                <span v-else>{{ o.note || '有效' }}</span>
              </template>
            </el-table-column>
          </el-table>

          <div v-if="b.diffs?.length" style="margin-top: 8px">
            <div class="panel-sub">计划 vs 实产差异：</div>
            <el-table :data="b.diffs" border size="small" style="margin-top: 4px">
              <el-table-column label="类型" width="110">
                <template #default="{ row: d }">
                  <el-tag size="small" :type="d.status === 'OPEN' ? 'danger' : 'info'">{{ d.diffTypeName }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="detail" label="描述" min-width="300" />
              <el-table-column label="核清" width="220">
                <template #default="{ row: d }">
                  <span v-if="d.status === 'OPEN'" class="text-danger">待负责人核清（阻断收口）</span>
                  <span v-else class="panel-sub">{{ d.resolutionName }}：{{ d.resolveReason }}</span>
                </template>
              </el-table-column>
            </el-table>
          </div>

          <div style="margin-top: 8px">
            <el-button size="small" type="primary" @click="goBatchWorkbench(b)">到批次工作台处理</el-button>
          </div>
        </el-collapse-item>
      </el-collapse>
    </div>
  </el-drawer>
</template>

<script setup>
import { computed, inject, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { blockApi, orderApi, sawApi } from '../api'

const router = useRouter()
const mill = inject('mill')

const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const sawType = ref('')
const status = ref('')
const blocks = ref([])
const createVisible = ref(false)
const form = ref({ orderNo: '', blockId: null, sawType: 'FRAME', planCount: 4, team: '甲班' })

const doneTotal = computed(() => rows.value.reduce((sum, r) => sum + (r.doneCount || 0), 0))

function tagType(code) {
  return { PENDING: 'info', PROCESSING: 'warning', DONE: 'success', SETTLED: 'primary' }[code] || 'info'
}

function barColor(p) {
  if (p >= 100) return '#43a047'
  if (p >= 50) return '#455a64'
  return '#f9a825'
}

async function load() {
  const params = { page: page.value, size: size.value, orderBy: 'date', desc: true }
  if (sawType.value) params.sawType = sawType.value
  if (status.value) params.status = status.value
  if (mill.selectedBlockId && mill.selectedBlockId.value) params.blockId = mill.selectedBlockId.value
  const result = await orderApi.page(params)
  rows.value = result.rows
  total.value = result.total
}

function onPage(p) {
  page.value = p
  load()
}

function onSize(s) {
  size.value = s
  page.value = 1
  load()
}

function openCreate() {
  form.value = {
    orderNo: '', blockId: blocks.value[0] ? blocks.value[0].id : null,
    sawType: 'FRAME', planCount: 4, team: '甲班'
  }
  createVisible.value = true
}

async function doCreate() {
  await orderApi.create(form.value)
  createVisible.value = false
  ElMessage.success('加工工单已创建')
  load()
  mill.refreshOverview()
}

async function doStart(row) {
  await orderApi.start(row.id)
  ElMessage.success('工单已开工，荒料置为加工中')
  load()
  mill.refreshOverview()
}

async function doProgress(row) {
  await orderApi.progress(row.id, 1)
  ElMessage.success('已完成板数 +1')
  load()
}

async function doFinish(row) {
  await orderApi.finish(row.id)
  ElMessage.success('工单已完工，荒料置为已完料')
  load()
  mill.refreshOverview()
}

async function doSettle(row) {
  await orderApi.settle(row.id)
  ElMessage.success('工单已结算')
  load()
}

// ---------------- 模块八：批次谱系抽屉 ----------------
const batchDrawerVisible = ref(false)
const batchOrder = ref({})
const orderBatches = ref([])
const activeBatchIds = ref([])

function batchTagType(s) {
  return { OPEN: 'info', WAIT_CHECK: 'danger', CLOSED: 'success' }[s] || 'info'
}

async function openBatches(row) {
  batchOrder.value = row
  orderBatches.value = await sawApi.byOrder(row.id)
  activeBatchIds.value = orderBatches.value
    .filter(b => b.status !== 'CLOSED' || b.openDiffCount > 0).map(b => b.id)
  batchDrawerVisible.value = true
}

function batchOutputs(b) {
  return [...(b.outputs || [])].sort((a, z) => {
    const sa = a.seqNo == null ? 999999 : a.seqNo
    const sz = z.seqNo == null ? 999999 : z.seqNo
    return sa - sz || a.id - z.id
  })
}

function goBatchWorkbench(b) {
  batchDrawerVisible.value = false
  router.push({ path: '/saw', query: { batchId: b.id } })
}

watch(() => mill.selectedBlockId.value, load)

onMounted(async () => {
  blocks.value = await blockApi.list()
  await load()
})
</script>

<style scoped>
.text-danger { color: #e53935; }
</style>
