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
      <el-table-column label="操作" width="230" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="doStart(row)" v-if="row.status === 'PENDING'">开工</el-button>
          <el-button link type="success" @click="doProgress(row)"
                     v-if="row.status === 'PENDING' || row.status === 'PROCESSING'">完成+1</el-button>
          <el-button link type="warning" @click="doFinish(row)"
                     v-if="row.status === 'PENDING' || row.status === 'PROCESSING'">完工</el-button>
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
</template>

<script setup>
import { computed, inject, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { blockApi, orderApi } from '../api'

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

watch(() => mill.selectedBlockId.value, load)

onMounted(async () => {
  blocks.value = await blockApi.list()
  await load()
})
</script>
