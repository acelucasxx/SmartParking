<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { get } from '@/api/http'
import { AdminApiError, AdminCode } from '@/auth/errors'
import LabelTag from '@/components/LabelTag.vue'
import { usePaged } from '@/composables/usePaged'
import {
  ALARM_LEVEL,
  ALARM_STATUS,
  ALARM_TYPE,
  BIND_STATUS,
  CHANNEL,
  COMMAND_ACTION,
  COMMAND_STATUS,
  GATE_STATUS,
  MEMBER_STATUS,
  ONLINE_STATE,
  OPERATOR_ROLE,
  RESULT_CODE,
  TRIGGER_SOURCE,
  WORK_MODE,
  batteryText,
  durationText,
  label,
  occupiedText,
  signalText,
  text,
} from '@/devices/labels'
import type { AdminDeviceDetail, Alarm, CommandAudit, DeviceEvent, Paged } from '@/devices/types'
import { useAuthStore } from '@/stores/auth'
import { dayRange, formatDateTime } from '@/utils/format'

/**
 * 设备管理 · 详情（ADM-03）：实时状态、主人、被授权人与有效期、指令、告警、事件流。
 * 运维操作（强制解绑、禁用、换件、自检）在 ADM-06。
 */
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const id = computed(() => Number(route.params.id))

const device = ref<AdminDeviceDetail | null>(null)
const loading = ref(false)
const notFound = ref(false)
const refreshedAt = ref<string | null>(null)
const tab = ref('overview')
// 告警列表只开放给超级管理员与运维（契约 x-roles），客服看不到这个页签
const canAlarms = computed(() => auth.can('alarm:view'))

async function loadDevice() {
  if (!Number.isInteger(id.value) || id.value <= 0) {
    notFound.value = true
    return
  }
  loading.value = true
  try {
    device.value = await get<AdminDeviceDetail>(`/devices/${id.value}`, undefined, { silent: true })
    notFound.value = false
    refreshedAt.value = new Date().toISOString()
  } catch (e) {
    if (e instanceof AdminApiError && e.code === AdminCode.NOT_FOUND) notFound.value = true
  } finally {
    loading.value = false
  }
}

const SIZE = 20
const commands = usePaged((page) =>
  get<Paged<CommandAudit>>('/commands', { deviceId: id.value, page, size: SIZE }),
)
const alarms = usePaged((page) =>
  get<Paged<Alarm>>('/alarms', { deviceId: id.value, page, size: SIZE }),
)
const eventRange = ref<[string, string] | null>(null)
const events = usePaged((page) =>
  get<Paged<DeviceEvent>>(`/devices/${id.value}/events`, {
    ...dayRange(eventRange.value),
    page,
    size: SIZE,
  }),
)

// 页签第一次打开时才加载
watch(tab, (t) => {
  if (t === 'commands' && !commands.loaded.value) void commands.load(1)
  if (t === 'alarms' && !alarms.loaded.value) void alarms.load(1)
  if (t === 'events' && !events.loaded.value) void events.load(1)
})

watch(
  id,
  () => {
    tab.value = 'overview'
    commands.loaded.value = alarms.loaded.value = events.loaded.value = false
    void loadDevice()
  },
  { immediate: true },
)

const title = computed(() => {
  const d = device.value
  if (!d) return ''
  return d.name ? `${d.name}（${d.sn}）` : d.sn
})
</script>

<template>
  <div v-loading="loading && !device">
    <el-page-header class="head" @back="router.back()">
      <template #content>
        <span class="title">{{ title || '设备详情' }}</span>
        <template v-if="device">
          <LabelTag :value="label(BIND_STATUS, device.bindStatus)" />
          <LabelTag :value="label(ONLINE_STATE, device.onlineState)" />
          <el-tag v-if="!device.enabled" type="danger" size="small">已禁用</el-tag>
        </template>
      </template>
    </el-page-header>

    <el-result
      v-if="notFound"
      icon="warning"
      title="设备不存在"
      sub-title="请确认链接，或回到列表重新查找。"
    >
      <template #extra>
        <el-button type="primary" @click="router.push({ name: 'devices' })">返回设备列表</el-button>
      </template>
    </el-result>

    <el-tabs v-else-if="device" v-model="tab">
      <!-- 概览：实时状态与设备信息 -->
      <el-tab-pane label="概览" name="overview">
        <div class="bar">
          <span class="muted"
            >查询于 {{ formatDateTime(refreshedAt, true) }}，在线状态按查询时刻判定</span
          >
          <el-button size="small" :loading="loading" @click="loadDevice">刷新</el-button>
        </div>
        <el-descriptions title="实时状态" :column="3" border>
          <el-descriptions-item label="在线状态"
            ><LabelTag :value="label(ONLINE_STATE, device.onlineState)"
          /></el-descriptions-item>
          <el-descriptions-item label="闸杆"
            ><LabelTag :value="label(GATE_STATUS, device.gateStatus)"
          /></el-descriptions-item>
          <el-descriptions-item label="车位">{{
            occupiedText(device.occupied)
          }}</el-descriptions-item>
          <el-descriptions-item label="电量">{{
            batteryText(device.batteryPct)
          }}</el-descriptions-item>
          <el-descriptions-item label="信号">{{
            signalText(device.signalLevel)
          }}</el-descriptions-item>
          <el-descriptions-item label="工作模式">{{
            text(WORK_MODE, device.workMode)
          }}</el-descriptions-item>
          <el-descriptions-item label="状态刷新">{{
            formatDateTime(device.statusRefreshedAt, true)
          }}</el-descriptions-item>
          <el-descriptions-item label="最近上行" :span="2">{{
            formatDateTime(device.lastSeenAt, true)
          }}</el-descriptions-item>
        </el-descriptions>
        <el-descriptions title="设备信息" :column="3" border class="block">
          <el-descriptions-item label="SN">{{ device.sn }}</el-descriptions-item>
          <el-descriptions-item label="IMEI">{{ device.imei }}</el-descriptions-item>
          <el-descriptions-item label="蓝牙 MAC">{{ device.bleMac || '—' }}</el-descriptions-item>
          <el-descriptions-item label="ICCID">{{ device.iccid || '—' }}</el-descriptions-item>
          <el-descriptions-item label="型号">{{ device.model }}</el-descriptions-item>
          <el-descriptions-item label="批次">{{ device.batchNo }}</el-descriptions-item>
          <el-descriptions-item label="固件版本">{{
            device.fwVersion || '—'
          }}</el-descriptions-item>
          <el-descriptions-item label="名称 / 车位号"
            >{{ device.name || '—' }} / {{ device.spaceNo || '—' }}</el-descriptions-item
          >
          <el-descriptions-item label="入库时间">{{
            formatDateTime(device.createdAt)
          }}</el-descriptions-item>
        </el-descriptions>
      </el-tab-pane>

      <!-- 主人与被授权人 -->
      <el-tab-pane :label="`主人与授权（${device.members.length}）`" name="members">
        <el-descriptions title="主人" :column="3" border>
          <template v-if="device.owner">
            <el-descriptions-item label="昵称">{{ device.owner.nickname }}</el-descriptions-item>
            <el-descriptions-item label="手机号">{{
              device.owner.phoneMasked || '—'
            }}</el-descriptions-item>
            <el-descriptions-item label="成为主人">{{
              formatDateTime(device.owner.since)
            }}</el-descriptions-item>
          </template>
          <el-descriptions-item v-else label="状态">待激活，尚无主人</el-descriptions-item>
        </el-descriptions>
        <div class="section-title">被授权人（生效中，含尚未开始的）</div>
        <el-table :data="device.members" empty-text="没有生效中的授权">
          <el-table-column prop="nickname" label="昵称" min-width="120" />
          <el-table-column label="手机号" width="130">
            <template #default="{ row }">{{ row.phoneMasked || '—' }}</template>
          </el-table-column>
          <el-table-column label="备注" min-width="100">
            <template #default="{ row }">{{ row.remark || '—' }}</template>
          </el-table-column>
          <el-table-column label="有效期" min-width="260">
            <template #default="{ row }">
              {{ formatDateTime(row.validFrom) }} 至
              {{ row.validTo ? formatDateTime(row.validTo) : '永久' }}
            </template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }"
              ><LabelTag :value="label(MEMBER_STATUS, row.status)"
            /></template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- 指令 -->
      <el-tab-pane label="指令" name="commands">
        <el-table
          v-loading="commands.loading.value"
          :data="commands.items.value"
          empty-text="没有指令记录"
        >
          <el-table-column type="expand">
            <template #default="{ row }">
              <div class="frames">
                <div>
                  下行帧：<code>{{ row.downFrame || '—' }}</code>
                </div>
                <div>
                  判定帧：<code>{{ row.rawFrame || '—' }}</code>
                </div>
                <div v-if="row.fwVersion">固件版本：{{ row.fwVersion }}</div>
                <div>完成时间：{{ formatDateTime(row.finishedAt, true) }}</div>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="下发时间" width="170">
            <template #default="{ row }">{{ formatDateTime(row.issuedAt, true) }}</template>
          </el-table-column>
          <el-table-column label="操作人" min-width="160">
            <template #default="{ row }">
              {{ row.operator.name }}
              <span class="muted"
                >{{ text(OPERATOR_ROLE, row.role)
                }}{{ row.operator.phoneMasked ? ` · ${row.operator.phoneMasked}` : '' }}</span
              >
            </template>
          </el-table-column>
          <el-table-column label="动作" width="90">
            <template #default="{ row }">{{ text(COMMAND_ACTION, row.action) }}</template>
          </el-table-column>
          <el-table-column label="通道" width="70">
            <template #default="{ row }">{{ text(CHANNEL, row.channel) }}</template>
          </el-table-column>
          <el-table-column label="结果" width="150">
            <template #default="{ row }">
              <LabelTag :value="label(COMMAND_STATUS, row.status)" />
              <span v-if="row.resultCode" class="muted">
                {{ text(RESULT_CODE, row.resultCode) }}</span
              >
            </template>
          </el-table-column>
          <el-table-column label="耗时" width="100">
            <template #default="{ row }">{{ durationText(row.durationMs) }}</template>
          </el-table-column>
        </el-table>
        <el-pagination
          class="pager"
          layout="total, prev, pager, next"
          :current-page="commands.page.value"
          :page-size="SIZE"
          :total="commands.total.value"
          hide-on-single-page
          @current-change="commands.load"
        />
      </el-tab-pane>

      <!-- 告警：客服无权查看 -->
      <el-tab-pane v-if="canAlarms" label="告警" name="alarms">
        <el-table v-loading="alarms.loading.value" :data="alarms.items.value" empty-text="没有告警">
          <el-table-column label="类型" width="130">
            <template #default="{ row }">{{ text(ALARM_TYPE, row.type) }}</template>
          </el-table-column>
          <el-table-column label="级别" width="80">
            <template #default="{ row }"
              ><LabelTag :value="label(ALARM_LEVEL, row.level)"
            /></template>
          </el-table-column>
          <el-table-column label="首次 / 最近" width="300">
            <template #default="{ row }"
              >{{ formatDateTime(row.firstAt) }} / {{ formatDateTime(row.lastAt) }}</template
            >
          </el-table-column>
          <el-table-column prop="count" label="合并次数" width="90" />
          <el-table-column label="状态" width="90">
            <template #default="{ row }"
              ><LabelTag :value="label(ALARM_STATUS, row.status)"
            /></template>
          </el-table-column>
          <el-table-column label="处理" min-width="180">
            <template #default="{ row }">
              <template v-if="row.status === 'HANDLED'">
                {{ row.handlerName }} · {{ formatDateTime(row.handledAt) }}
                <div v-if="row.note" class="muted">{{ row.note }}</div>
              </template>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination
          class="pager"
          layout="total, prev, pager, next"
          :current-page="alarms.page.value"
          :page-size="SIZE"
          :total="alarms.total.value"
          hide-on-single-page
          @current-change="alarms.load"
        />
      </el-tab-pane>

      <!-- 事件流 -->
      <el-tab-pane label="事件流" name="events">
        <div class="bar">
          <el-date-picker
            v-model="eventRange"
            type="daterange"
            value-format="YYYY-MM-DD"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
            size="small"
            @change="events.load(1)"
          />
          <span class="muted">带“约”的时刻为定时查询发现的时间</span>
        </div>
        <el-table v-loading="events.loading.value" :data="events.items.value" empty-text="没有事件">
          <el-table-column label="时间" width="190">
            <template #default="{ row }"
              >{{ row.approx ? '约 ' : '' }}{{ formatDateTime(row.at, true) }}</template
            >
          </el-table-column>
          <el-table-column label="闸杆" width="100">
            <template #default="{ row }"
              ><LabelTag :value="label(GATE_STATUS, row.gateStatus)"
            /></template>
          </el-table-column>
          <el-table-column label="触发方式" width="120">
            <template #default="{ row }">{{ text(TRIGGER_SOURCE, row.source) }}</template>
          </el-table-column>
          <el-table-column label="车位" width="70">
            <template #default="{ row }">{{ occupiedText(row.occupied) }}</template>
          </el-table-column>
          <el-table-column label="状态码 / 原因码" width="130">
            <template #default="{ row }"
              >{{ row.statusCode || '—' }} / {{ row.reasonCode || '—' }}</template
            >
          </el-table-column>
          <el-table-column label="原始帧" min-width="200">
            <template #default="{ row }"
              ><code>{{ row.rawFrame }}</code></template
            >
          </el-table-column>
        </el-table>
        <el-pagination
          class="pager"
          layout="total, prev, pager, next"
          :current-page="events.page.value"
          :page-size="SIZE"
          :total="events.total.value"
          hide-on-single-page
          @current-change="events.load"
        />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<style scoped>
.head {
  margin-bottom: 16px;
}
.title {
  margin-right: 8px;
  font-weight: 600;
}
.head :deep(.el-tag) {
  margin-right: 6px;
}
.bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}
.block {
  margin-top: 20px;
}
.section-title {
  margin: 20px 0 8px;
  font-weight: 600;
}
.frames {
  padding: 4px 48px;
  line-height: 1.9;
}
code {
  font-family: Menlo, Consolas, monospace;
}
.pager {
  justify-content: flex-end;
  margin-top: 12px;
}
.muted {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
</style>
