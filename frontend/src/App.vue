<template>
  <div class="shell">
    <!-- 顶部：品牌 + 全站筛选（石种 / 等级 / 状态）+ 计数器 -->
    <header class="topbar">
      <div class="brand">
        <span class="brand-mark">▦</span>
        <div>
          石材加工厂 · 大板加工生产管理系统
          <div class="brand-sub">
            {{ todayText }} · 在库大板 {{ overview.instockSlab || 0 }} 片 / {{ overview.slabArea || 0 }} m²
          </div>
        </div>
      </div>

      <div class="global-filter">
        <el-select v-model="filters.stoneType" clearable placeholder="石种" @change="onFilterChange">
          <el-option v-for="s in stoneTypes" :key="s.value" :label="s.label" :value="s.value" />
        </el-select>
        <el-select v-model="filters.grade" clearable placeholder="等级" @change="onFilterChange">
          <el-option v-for="g in grades" :key="g.value" :label="g.label" :value="g.value" />
        </el-select>
        <el-select v-model="filters.status" clearable placeholder="状态" @change="onFilterChange">
          <el-option v-for="s in slabStatuses" :key="s.value" :label="s.label" :value="s.value" />
        </el-select>
      </div>

      <div class="top-stats">
        <div class="stat-chip"><b>{{ overview.blockCount || 0 }}</b><span>荒料</span></div>
        <div class="stat-chip"><b>{{ overview.blockVolume || 0 }}</b><span>体积 m³</span></div>
        <div class="stat-chip"><b>{{ overview.slabCount || 0 }}</b><span>大板</span></div>
        <div class="stat-chip"><b>{{ overview.orderProcessing || 0 }}</b><span>加工中工单</span></div>
        <div class="stat-chip">
          <b>{{ overview.avgWasteRate || 0 }}%</b><span>平均损耗</span></div>
        <div class="stat-chip"><b>{{ money(overview.deliveryAmount) }}</b><span>发货金额</span></div>
      </div>
    </header>

    <nav class="mod-nav">
      <router-link v-for="n in navItems" :key="n.key" :to="n.path" class="mod-nav-item">
        <span class="mod-icon">{{ n.icon }}</span>
        <span class="mod-label">{{ n.label }}</span>
      </router-link>
    </nav>

    <div class="body">
      <!-- 左侧树：荒料批次 → 大板 -->
      <aside class="side">
        <div class="side-title">
          <span>荒料批次 / 大板</span>
          <el-button link type="primary" @click="loadTree">刷新</el-button>
        </div>
        <div class="side-hint">点荒料看排版图，点大板看详情</div>

        <el-tree
          v-loading="treeLoading"
          :data="tree"
          node-key="key"
          :props="{ children: 'children', label: 'label' }"
          :default-expanded-keys="expandedKeys"
          :expand-on-click-node="false"
          highlight-current
          @node-click="onNodeClick"
        >
          <template #default="{ data }">
            <span class="tree-node">
              <span v-if="data.type === 'block'" class="tree-dot">▣</span>
              <span v-else class="tree-dot" :style="{ background: gradeColor(data.grade) }"></span>
              <span>{{ data.label }}</span>
            </span>
          </template>
        </el-tree>
      </aside>

      <main class="stage">
        <router-view />
      </main>
    </div>

    <!-- 点树上大板节点：抽屉看大板详情 -->
    <el-drawer v-model="slabVisible" title="大板详情" size="360px">
      <div v-if="slab.id">
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="大板编号">{{ slab.slabCode }}</el-descriptions-item>
          <el-descriptions-item label="所属荒料">{{ slab.blockCode }}</el-descriptions-item>
          <el-descriptions-item label="石种">{{ slab.stoneTypeName }}</el-descriptions-item>
          <el-descriptions-item label="尺寸">
            {{ slab.lengthCm }} × {{ slab.widthCm }} × {{ slab.thicknessCm }} cm
          </el-descriptions-item>
          <el-descriptions-item label="面积">{{ slab.areaM2 }} m²</el-descriptions-item>
          <el-descriptions-item label="表面处理">{{ slab.finishName }}</el-descriptions-item>
          <el-descriptions-item label="质量等级">
            <span class="grade-tag" :style="{ background: gradeColor(slab.grade) }">{{ slab.gradeName }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="库位">{{ slab.location }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ slab.statusName }}</el-descriptions-item>
          <el-descriptions-item v-if="slab.qualityHold" label="质量隔离">
            <el-tag size="small" type="danger">{{ slab.qualityHoldStatusName }} · {{ slab.qualityDefectName }}</el-tag>
            <div class="panel-sub">{{ slab.qualityHoldNo }} · 发货冻结中</div>
          </el-descriptions-item>
          <el-descriptions-item v-if="slab.sawTracked" label="锯切谱系">
            <el-tag size="small" :type="slab.sawBatchClosed ? 'success' : 'warning'">
              {{ slab.sawBatchNo }} · {{ slab.sawOutputTypeName }} · {{ slab.sawBatchClosed ? '已收口' : '未收口' }}
            </el-tag>
            <div class="panel-sub">产出唯一归属；<el-button link type="primary" @click="loadLineage">历次更正</el-button></div>
          </el-descriptions-item>
          <el-descriptions-item label="备注">{{ slab.remark || '—' }}</el-descriptions-item>
        </el-descriptions>

        <div v-if="lineage && lineage.changes.length" class="lineage-box">
          <div class="lineage-title">所属批次与历次更正</div>
          <div class="panel-sub" style="margin-bottom:6px">
            当前：{{ lineage.currentBatchNo }} · {{ lineage.outputTypeName }}
            · {{ lineage.batchStatusName }}
          </div>
          <div v-for="c in lineage.changes" :key="c.id" class="lineage-item">
            <b>{{ c.changeTypeName }}</b>
            <span v-if="c.fromBatchNo" class="panel-sub">（{{ c.fromBatchNo }} → {{ c.toBatchNo }}）</span>
            <span class="panel-sub"> · {{ String(c.createdAt).replace('T',' ').slice(0,16) }} · {{ c.operator }}</span>
            <div class="panel-sub">{{ c.reason }}</div>
            <div v-if="c.beforeJson" class="json-line">前：{{ c.beforeJson }}</div>
            <div v-if="c.afterJson" class="json-line">后：{{ c.afterJson }}</div>
          </div>
        </div>

        <div style="margin-top: 14px">
          <el-button type="warning" @click="gotoQuality">质量处置</el-button>
          <el-button type="primary" @click="gotoSlab">到大板库存页</el-button>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<script setup>
import { computed, getCurrentInstance, onMounted, provide, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import router, { navItems } from './router'
import { millApi, slabApi, sawApi } from './api'

/**
 * 模板自带的 main.js 只装了 Element Plus，没装 router（那份文件不动它），
 * 所以在这里把 router 装到根组件所属的 app 实例上。
 * vue-router 的 install 自带幂等保护，重复调用无副作用。
 */
getCurrentInstance().appContext.app.use(router)

const route = useRoute()
const nav = useRouter()

/** 词典：与后端 StoneDict 保持一致 */
const stoneTypes = [
  { value: 'MARBLE', label: '大理石' },
  { value: 'GRANITE', label: '花岗岩' },
  { value: 'JADE', label: '玉石' },
  { value: 'QUARTZ', label: '石英石' },
  { value: 'TRAVERTINE', label: '洞石' }
]
const grades = [
  { value: 'A', label: 'A 级' },
  { value: 'B', label: 'B 级' },
  { value: 'C', label: 'C 级' },
  { value: 'DEFECT', label: '次品' }
]
const slabStatuses = [
  { value: 'INSTOCK', label: '在库' },
  { value: 'RESERVED', label: '已预定' },
  { value: 'OUTBOUND', label: '已出库' },
  { value: 'SCRAP', label: '报废' }
]

const filters = reactive({ stoneType: '', grade: '', status: '' })
const overview = ref({})
const tree = ref([])
const treeLoading = ref(false)
const selectedBlockId = ref(null)
const slabVisible = ref(false)
const slab = ref({})
const lineage = ref(null)

/** 全局上下文：所有页面 inject('mill') */
const mill = { filters, overview, tree, selectedBlockId, refreshOverview: loadOverview, refreshTree: loadTree }
provide('mill', mill)

const todayText = computed(() => {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
})

const expandedKeys = computed(() => tree.value.slice(0, 3).map((n) => n.key))

function gradeColor(grade) {
  return { A: '#43a047', B: '#f9a825', C: '#90a4ae', DEFECT: '#e53935' }[grade] || '#90a4ae'
}

function money(v) {
  const n = Number(v || 0)
  return n >= 10000 ? (n / 10000).toFixed(2) + ' 万' : n.toFixed(0)
}

function onFilterChange() {
  loadTree()
}

async function loadOverview() {
  overview.value = await millApi.overview()
}

async function loadTree() {
  treeLoading.value = true
  try {
    const params = {}
    if (filters.stoneType) params.stoneType = filters.stoneType
    if (filters.grade) params.grade = filters.grade
    if (filters.status) params.status = filters.status
    tree.value = await millApi.tree(params)
  } finally {
    treeLoading.value = false
  }
}

async function onNodeClick(data) {
  if (data.type === 'block') {
    selectedBlockId.value = data.id
    if (route.path !== '/layout') nav.push('/layout')
    return
  }
  const detail = await slabApi.detail(data.id)
  slab.value = detail
  lineage.value = null
  slabVisible.value = true
}

async function loadLineage() {
  lineage.value = await sawApi.lineageBySlab(slab.value.id)
}

function gotoSlab() {
  slabVisible.value = false
  nav.push('/slabs')
}

function gotoQuality() {
  slabVisible.value = false
  nav.push('/quality')
}

onMounted(async () => {
  await loadOverview()
  await loadTree()
})
</script>

<style scoped>
.tree-node {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
}

.tree-dot {
  display: inline-block;
  width: 9px;
  height: 9px;
  border-radius: 2px;
  background: var(--mill-primary);
  font-size: 10px;
  line-height: 9px;
  color: var(--mill-primary);
}

.lineage-box {
  margin-top: 14px;
  padding: 10px;
  border: 1px dashed var(--mill-border, #dcdfe6);
  border-radius: 6px;
}
.lineage-title { font-weight: 600; margin-bottom: 8px; }
.lineage-item { padding: 6px 0; border-bottom: 1px dashed #eee; font-size: 13px; }
.lineage-item:last-child { border-bottom: none; }
.json-line { font-size: 12px; color: #607d8b; word-break: break-all; }
</style>
