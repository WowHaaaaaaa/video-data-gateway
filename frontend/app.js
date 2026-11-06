/* =========================================================================
 * 视频数据网关 · 前端（Vue3 global + Element Plus，无构建）
 * 鉴权：首次输入 api-key，存 sessionStorage，所有请求自动带上
 * ========================================================================= */
const { createApp, ref, reactive, computed, watch, onMounted, onBeforeUnmount, nextTick } = Vue;
const { ElMessage, ElMessageBox } = ElementPlus;

const KEY_STORE = 'vgw_api_key';

/* ---------------- 请求封装 ---------------- */
function getKey() {
    let k = sessionStorage.getItem(KEY_STORE) || '';
    // 兼容：旧版本前端存的是业务演示凭证，自动升级为控制台凭证
    if (k === 'vwg_demo_app') {
        k = 'vwg_console';
        sessionStorage.setItem(KEY_STORE, k);
    }
    return k;
}
function setKey(k) {
    if (k) sessionStorage.setItem(KEY_STORE, k);
    else sessionStorage.removeItem(KEY_STORE);
}

async function api(path, options = {}) {
    const headers = Object.assign({ 'Content-Type': 'application/json' }, options.headers || {});
    const k = getKey();
    if (k) headers['api-key'] = k;
    // 标记"来自 Web 控制台"，后端据此把台账来源记为 MANUAL（人工操作）而非 API
    headers['X-Request-From'] = 'ui';
    let resp;
    try {
        resp = await fetch(path, Object.assign({}, options, { headers }));
    } catch (e) {
        throw new Error('无法连接网关服务，请确认服务在运行');
    }
    if (resp.status === 401) {
        // 保留后端给出的具体原因（凭证已过期 / 已被停用 / 无效），不要覆盖成笼统提示
        const body = await resp.json().catch(() => null);
        const reason = (body && body.msg) ? body.msg : '调用凭证缺失或已失效，请重新输入';
        setKey('');
        window.dispatchEvent(new CustomEvent('vgw-unauthorized', { detail: reason }));
        throw new Error(reason);
    }
    const json = await resp.json().catch(() => null);
    if (!json) throw new Error('服务返回的内容无法识别（HTTP ' + resp.status + '），请稍后重试');
    if (json.code !== 0) {
        // traceId 只打到控制台（排查用），界面上不打扰用户
        if (json.traceId) console.warn('[vgw] ' + (json.msg || '请求失败') + '（traceId: ' + json.traceId + '）');
        throw new Error(json.msg || '请求失败');
    }
    return json.data;
}

/* ---------------- 公共格式化 ---------------- */
function fmtTime(s) {
    if (!s) return '-';
    return String(s).replace('T', ' ').slice(0, 19);
}
function fmtDur(sec) {
    if (sec == null) return '-';
    const h = Math.floor(sec / 3600), m = Math.floor((sec % 3600) / 60), s = sec % 60;
    if (h) return h + '小时' + m + '分';
    if (m) return m + '分' + s + '秒';
    return s + '秒';
}
function fmtSize(bytes) {
    if (bytes == null) return '-';
    return (bytes / 1048576).toFixed(2) + ' MB';
}

const EVENT_MAP = {
    PUBLISH_START: ['推流开始', 'success'],
    PUBLISH_STOP: ['推流结束', 'info'],
    PUBLISH_REJECT: ['推流被拒', 'danger'],
    PLAY_START: ['播放开始', 'primary'],
    PLAY_STOP: ['播放结束', 'info'],
    RECORD_START: ['开始录像', 'warning'],
    RECORD_STOP: ['停止录像', 'warning'],
    RECORD_FINISH: ['录像完成', 'success'],
    API_CALL: ['接口调用', 'primary'],
    SERVER_STARTED: ['服务器启动', 'info'],
    DEVICE_ONLINE: ['设备上线', 'success'],
    DEVICE_OFFLINE: ['设备离线', 'danger'],
    STATE_RECONCILE: ['对账纠正', 'warning'],
    AUTH_CHANGED: ['授权变更', 'warning'],
    RECORD_PURGED: ['录像清理', 'info'],
    STREAM_PULL_START: ['发起拉流', 'primary'],
    STREAM_PULL_STOP: ['停止拉流', 'info'],
    MANUAL: ['手工操作', 'warning']
};
/**
 * 事件分组：筛选下拉框按业务动作分组，并支持"整组筛选"。
 * value 用 GROUP:<组名> 表示选中整组，前端会展开成该组全部事件类型。
 */
const EVENT_GROUPS = [
    { label: '推流', types: ['PUBLISH_START', 'PUBLISH_STOP', 'PUBLISH_REJECT'] },
    { label: '拉流', types: ['STREAM_PULL_START', 'STREAM_PULL_STOP'] },
    { label: '播放', types: ['PLAY_START', 'PLAY_STOP'] },
    { label: '录像', types: ['RECORD_START', 'RECORD_STOP', 'RECORD_FINISH', 'RECORD_PURGED'] },
    { label: '接口调用', types: ['API_CALL', 'AUTH_CHANGED'] },
    { label: '设备 / 系统', types: ['DEVICE_ONLINE', 'DEVICE_OFFLINE', 'SERVER_STARTED', 'STATE_RECONCILE'] }
];

const evLabel = (t) => (EVENT_MAP[t] || [t || '-', 'info'])[0];
const evType = (t) => (EVENT_MAP[t] || [t, 'info'])[1];

/** 全局轻量状态 */
const store = reactive({ zlmOk: true, channelCount: 0 });

/* ========================= 1. 概览 ========================= */
const Overview = {
    template: `
    <div>
        <div class="stat-row">
            <div class="stat green"><div class="label">在线通道</div>
                <div class="value">{{ s.channelOnline || 0 }}<span class="unit">路</span></div></div>
            <div class="stat"><div class="label">进行中会话</div>
                <div class="value">{{ s.runningSessions || 0 }}<span class="unit">路</span></div></div>
            <div class="stat orange"><div class="label">今日推流次数</div>
                <div class="value">{{ s.todayPublishCount || 0 }}<span class="unit">次</span></div></div>
            <div class="stat red"><div class="label">今日拦截次数</div>
                <div class="value">{{ s.todayRejectCount || 0 }}<span class="unit">次</span></div></div>
        </div>

        <div class="page-card">
            <div class="page-head">
                <div>
                    <h3>最近事件</h3>
                    <div class="sub">业务台账最新 10 条（共 {{ s.ledgerTotal || 0 }} 条）</div>
                </div>
                <el-button size="small" @click="load">刷新</el-button>
            </div>
            <el-table :data="rows" size="small" v-loading="loading">
                <el-table-column label="时间" width="170">
                    <template #default="sc">{{ fmtTime(sc.row.eventTime) }}</template>
                </el-table-column>
                <el-table-column label="事件" width="110">
                    <template #default="sc">
                        <el-tag size="small" :type="evType(sc.row.eventType)">{{ evLabel(sc.row.eventType) }}</el-tag>
                    </template>
                </el-table-column>
                <el-table-column label="通道" width="140">
                    <template #default="sc">
                        <span v-if="sc.row.channelCode">{{ sc.row.channelCode }}</span>
                        <span v-else-if="sc.row.channelId">#{{ sc.row.channelId }}</span>
                        <span v-else>-</span>
                    </template>
                </el-table-column>
                <el-table-column label="客户端" width="90">
                    <template #default="sc">{{ sc.row.clientId || '-' }}</template>
                </el-table-column>
                <el-table-column prop="source" label="来源" width="90" />
                <el-table-column label="结果" width="90">
                    <template #default="sc">
                        <el-tag v-if="sc.row.resultCode === 0" size="small" type="success">成功</el-tag>
                        <el-tag v-else size="small" type="danger">{{ sc.row.resultCode }}</el-tag>
                    </template>
                </el-table-column>
                <el-table-column prop="resultMsg" label="说明" show-overflow-tooltip />
            </el-table>
        </div>
    </div>`,
    setup() {
        const s = reactive({});
        const rows = ref([]);
        const loading = ref(false);
        async function load() {
            loading.value = true;
            try {
                const stat = await api('/api/v1/stats/overview');
                Object.assign(s, stat);
                store.zlmOk = !!stat.zlmAvailable;
                store.channelCount = stat.channelTotal || 0;
                const page = await api('/api/v1/ledger?page=1&size=10');
                rows.value = page.list || [];
            } catch (e) {
                ElMessage.error(e.message);
            } finally {
                loading.value = false;
            }
        }
        onMounted(load);
        return { s, rows, loading, load, fmtTime, evLabel, evType };
    }
};

/* ========================= 2. 设备管理 ========================= */
const Devices = {
    template: `
    <div class="page-card">
        <div class="page-head">
            <div><h3>设备管理</h3><div class="sub">摄像头 / 屏幕源等设备的主数据</div></div>
            <el-button type="primary" size="small" @click="openCreate">+ 新增设备</el-button>
        </div>
        <div class="filter-row">
            <el-input v-model="q.keyword" placeholder="设备名称关键字" clearable style="width:200px"
                @keyup.enter="search" @clear="search" />
            <el-select v-model="q.status" placeholder="全部状态" clearable style="width:140px" @change="search">
                <el-option label="在线" :value="1" />
                <el-option label="离线" :value="0" />
            </el-select>
            <el-button type="primary" @click="search">查询</el-button>
            <el-button @click="resetQuery">重置</el-button>
        </div>
        <el-table :data="rows" size="small" v-loading="loading">
            <el-table-column prop="deviceCode" label="设备编码" width="140" />
            <el-table-column prop="deviceName" label="设备名称" min-width="160" show-overflow-tooltip />
            <el-table-column prop="deviceType" label="接入类型" width="100" />
            <el-table-column prop="manufacturer" label="厂商" min-width="90" />
            <el-table-column prop="ipAddress" label="IP 地址" min-width="130" />
            <el-table-column label="状态" width="80">
                <template #default="sc">
                    <el-tag size="small" :type="sc.row.status === 1 ? 'success' : 'info'">
                        {{ sc.row.status === 1 ? '在线' : '离线' }}
                    </el-tag>
                </template>
            </el-table-column>
            <el-table-column label="启用" width="62">
                <template #default="sc">
                    <el-switch :model-value="sc.row.enabled === 1" @change="v => toggleDevice(sc.row, v)" />
                </template>
            </el-table-column>
            <el-table-column label="操作" width="215">
                <template #default="sc">
                    <el-button link type="primary" size="small" @click="openEdit(sc.row)">编辑</el-button>
                    <el-button link size="small" :type="sc.row.enabled === 1 ? 'warning' : 'success'"
                        @click="toggleDevice(sc.row, sc.row.enabled !== 1)">
                        {{ sc.row.enabled === 1 ? '停用' : '启用' }}
                    </el-button>
                    <el-button link type="danger" size="small" @click="remove(sc.row)">删除</el-button>
                </template>
            </el-table-column>
        </el-table>
        <el-pagination class="pager" background layout="total, prev, pager, next"
            :total="total" :page-size="size" :current-page="page"
            @current-change="p => { page = p; load(); }" />

        <el-dialog v-model="dlg" :title="form.id ? '编辑设备' : '新增设备'" width="520px">
            <el-form :model="form" label-width="90px">
                <el-form-item label="设备编码" required>
                    <el-input v-model="form.deviceCode" :disabled="!!form.id" placeholder="唯一编码，如 DEV-CAM-003" />
                </el-form-item>
                <el-form-item label="设备名称" required>
                    <el-input v-model="form.deviceName" placeholder="如：门口枪机" />
                </el-form-item>
                <el-form-item label="接入类型">
                    <el-select v-model="form.deviceType" style="width:100%">
                        <el-option label="RTMP" value="RTMP" />
                        <el-option label="RTSP" value="RTSP" />
                        <el-option label="GB28181" value="GB28181" />
                    </el-select>
                </el-form-item>
                <el-form-item label="厂商"><el-input v-model="form.manufacturer" /></el-form-item>
                <el-form-item label="IP 地址"><el-input v-model="form.ipAddress" /></el-form-item>
                <el-form-item label="启用"><el-switch v-model="form.enabled" :active-value="1" :inactive-value="0" /></el-form-item>
                <el-form-item label="备注"><el-input v-model="form.remark" type="textarea" :rows="2" /></el-form-item>
            </el-form>
            <template #footer>
                <el-button @click="dlg = false">取消</el-button>
                <el-button type="primary" :loading="saving" @click="save">保存</el-button>
            </template>
        </el-dialog>
    </div>`,
    setup() {
        const rows = ref([]), total = ref(0), page = ref(1), size = 10;
        const loading = ref(false), saving = ref(false), dlg = ref(false);
        const form = reactive({});
        // 设备列表筛选（keyword / status 走后端）
        const q = reactive({ keyword: '', status: null });

        function buildUrl() {
            let url = '/api/v1/devices?page=' + page.value + '&size=' + size;
            if (q.keyword) url += '&keyword=' + encodeURIComponent(q.keyword);
            if (q.status !== null && q.status !== '' && q.status !== undefined) url += '&status=' + q.status;
            return url;
        }
        function search() { page.value = 1; load(); }
        function resetQuery() {
            q.keyword = ''; q.status = null;
            page.value = 1; load();
        }

        async function load() {
            loading.value = true;
            try {
                const d = await api(buildUrl());
                rows.value = d.list || [];
                total.value = d.total || 0;
            } catch (e) { ElMessage.error(e.message); }
            finally { loading.value = false; }
        }
        /** 设备名兜底：接口返回 deviceName 就直接用；老版本后端只给 deviceId 时用已加载的设备表映射 */
        function deviceNameOf(deviceId) {
            if (!deviceId) return '-';
            const d = devices.value.find(x => x.id === deviceId);
            return d ? d.deviceName : ('#' + deviceId);
        }
        function reset() {
            Object.assign(form, {
                id: null, deviceCode: '', deviceName: '', deviceType: 'RTMP',
                manufacturer: '', ipAddress: '', enabled: 1, remark: ''
            });
        }
        function openCreate() { reset(); dlg.value = true; }
        function openEdit(row) {
            reset();
            Object.assign(form, row);
            form.accessType = row.pullUrl ? 'PULL' : 'PUSH';
            dlg.value = true;
        }
        async function save() {
            if (!form.deviceCode || !form.deviceName) { ElMessage.warning('设备编码和名称必填'); return; }
            saving.value = true;
            try {
                const body = JSON.stringify(form);
                if (form.id) await api('/api/v1/devices/' + form.id, { method: 'PUT', body });
                else await api('/api/v1/devices', { method: 'POST', body });
                ElMessage.success('已保存');
                dlg.value = false;
                load();
            } catch (e) { ElMessage.error(e.message); }
            finally { saving.value = false; }
        }
        async function toggleDevice(row, val) {
            try {
                await api('/api/v1/devices/' + row.id, {
                    method: 'PUT',
                    body: JSON.stringify({
                        deviceCode: row.deviceCode, deviceName: row.deviceName,
                        deviceType: row.deviceType, manufacturer: row.manufacturer,
                        ipAddress: row.ipAddress, enabled: val ? 1 : 0, remark: row.remark
                    })
                });
                ElMessage.success(val ? '已启用' : '已停用');
                load();
            } catch (e) { ElMessage.error(e.message); load(); }
        }
        async function remove(row) {
            try {
                await ElMessageBox.confirm(
                    '确认删除设备「' + row.deviceName + '」？\n\n'
                    + '· 这是逻辑删除，历史台账不受影响\n'
                    + '· 该设备编码会被保留占用，再次登记同编码会自动复用这条记录\n'
                    + '· 如果只是想暂停使用，建议点【停用】而不是删除',
                    '删除设备', { type: 'warning', confirmButtonText: '仍要删除', cancelButtonText: '取消' });
                await api('/api/v1/devices/' + row.id, { method: 'DELETE' });
                ElMessage.success('已删除');
                load();
            } catch (e) { if (e !== 'cancel') ElMessage.error(e.message || ''); }
        }
        onMounted(load);
        return { rows, total, page, size, loading, saving, dlg, form,
            q, search, resetQuery,
            load, openCreate, openEdit, save, toggleDevice, remove };
    }
};

/* ========================= 3. 通道管理 ========================= */
const Channels = {
    template: `
    <div class="page-card">
        <div class="page-head">
            <div>
                <h3>通道管理</h3>
            </div>
            <el-button type="primary" size="small" @click="openCreate">+ 新增通道</el-button>
        </div>
        <div class="filter-row">
            <el-input v-model="q.keyword" placeholder="通道名称关键字" clearable style="width:180px"
                @keyup.enter="search" @clear="search" />
            <el-select v-model="q.deviceId" placeholder="全部设备" clearable filterable style="width:220px" @change="search">
                <el-option v-for="d in devices" :key="d.id"
                    :label="d.deviceName + '（' + d.deviceCode + '）'" :value="d.id" />
            </el-select>
            <el-select v-model="q.status" placeholder="全部状态" clearable style="width:130px" @change="search">
                <el-option label="在线" :value="2" />
                <el-option label="离线" :value="0" />
            </el-select>
            <el-select v-model="q.access" placeholder="全部接入方式" clearable style="width:140px" @change="search">
                <el-option label="推流型" value="PUSH" />
                <el-option label="拉流型" value="PULL" />
            </el-select>
            <el-button type="primary" @click="search">查询</el-button>
            <el-button @click="resetQuery">重置</el-button>
        </div>
        <el-table :data="visibleRows" size="small" v-loading="loading">
            <el-table-column prop="channelCode" label="通道编码" width="110" />
            <el-table-column prop="channelName" label="通道名称" min-width="130" show-overflow-tooltip />
            <el-table-column label="所属设备" min-width="135" show-overflow-tooltip>
                <template #default="sc">{{ sc.row.deviceName || deviceNameOf(sc.row.deviceId) }}</template>
            </el-table-column>
            <el-table-column label="接入" width="76">
                <template #default="sc">
                    <el-tag v-if="sc.row.pullUrl" size="small" type="warning">拉流</el-tag>
                    <el-tag v-else size="small">推流</el-tag>
                </template>
            </el-table-column>
            <el-table-column label="流标识" width="185">
                <template #default="sc">
                    <code>{{ sc.row.streamApp }}/{{ sc.row.streamId }}</code>
                    <el-button link type="primary" size="small" style="margin-left:6px"
                        @click="probe(sc.row)">探测</el-button>
                </template>
            </el-table-column>
            <el-table-column label="状态" width="84">
                <template #default="sc">
                    <el-tag size="small" :type="streamState(sc.row)[1]">{{ streamState(sc.row)[0] }}</el-tag>
                </template>
            </el-table-column>
            <el-table-column label="启用" width="62">
                <template #default="sc">
                    <el-switch :model-value="sc.row.enabled === 1" @change="v => toggle(sc.row, v)" />
                </template>
            </el-table-column>
            <el-table-column label="最近上线" width="145">
                <template #default="sc">{{ fmtTime(sc.row.lastOnlineTime) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="280">
                <template #default="sc">
                    <el-button link type="primary" size="small" @click="openEdit(sc.row)">编辑</el-button>
                    <el-button v-if="sc.row.pullUrl" link type="success" size="small" @click="pull(sc.row)">发起拉流</el-button>
                    <el-button v-if="sc.row.pullUrl" link type="warning" size="small" @click="unpull(sc.row)">停止拉流</el-button>
                    <el-button link size="small" :type="sc.row.enabled === 1 ? 'warning' : 'success'"
                        @click="toggle(sc.row, sc.row.enabled !== 1)">
                        {{ sc.row.enabled === 1 ? '停用' : '启用' }}
                    </el-button>
                    <el-button link type="danger" size="small" @click="remove(sc.row)">删除</el-button>
                </template>
            </el-table-column>
        </el-table>
        <el-pagination class="pager" background layout="total, prev, pager, next"
            :total="total" :page-size="size" :current-page="page"
            @current-change="p => { page = p; load(); }" />

        <el-dialog v-model="dlg" :title="form.id ? '编辑通道' : '新增通道'" width="540px">
            <el-form :model="form" label-width="100px">
                <el-form-item label="所属设备" required>
                    <el-select v-model="form.deviceId" style="width:100%" placeholder="选择设备">
                        <el-option v-for="d in devices" :key="d.id" :label="d.deviceName + '（' + d.deviceCode + '）'" :value="d.id" />
                    </el-select>
                </el-form-item>
                <el-form-item label="通道编码" required>
                    <el-input v-model="form.channelCode" :disabled="!!form.id" />
                </el-form-item>
                <el-form-item label="通道名称" required><el-input v-model="form.channelName" /></el-form-item>
                <el-form-item label="app" required>
                    <el-input v-model="form.streamApp" placeholder="默认 live" />
                </el-form-item>
                <el-form-item label="stream_id" required>
                    <el-input v-model="form.streamId" :disabled="!!form.id" placeholder="推流地址里的流 ID" />
                    <div class="form-tip">推流地址：rtmp://127.0.0.1/{{ form.streamApp || 'live' }}/{{ form.streamId || 'cam01' }}</div>
                </el-form-item>
                <el-form-item label="接入方式" required>
                    <el-radio-group v-model="form.accessType">
                        <el-radio-button value="PUSH">推流（设备主动推）</el-radio-button>
                        <el-radio-button value="PULL">拉流（平台主动拉）</el-radio-button>
                    </el-radio-group>
                </el-form-item>
                <el-form-item v-if="form.accessType === 'PULL'" label="拉流地址" required>
                    <el-input v-model="form.pullUrl"
                        placeholder="rtsp://admin:pass@192.168.1.64:554/Streaming/Channels/101" />
                    <div class="form-tip">拉流型：设备不会主动连我们，保存后要在列表里点【发起拉流】，由媒体服务器去拉这个地址</div>
                </el-form-item>
                <div v-else class="form-tip" style="margin:-6px 0 14px 100px">
                    推流型：不用填地址，设备推 rtmp://127.0.0.1/{{ form.streamApp || 'live' }}/{{ form.streamId || 'cam01' }} 即上线
                </div>
                <el-form-item label="启用">
                    <el-switch v-model="form.enabled" :active-value="1" :inactive-value="0" />
                </el-form-item>
            </el-form>
            <template #footer>
                <el-button @click="dlg = false">取消</el-button>
                <el-button type="primary" :loading="saving" @click="save">保存</el-button>
            </template>
        </el-dialog>

        <el-dialog v-model="probeDlg" title="流标识探测" width="640px">
            <el-descriptions v-if="probeData" :column="1" border size="small">
                <el-descriptions-item label="通道">
                    {{ probeData.channelName }}（{{ probeData.channelCode }}）· 通道状态 {{ probeData.channelStatus > 0 ? '在线' : '离线' }}
                </el-descriptions-item>
                <el-descriptions-item label="流标识"><code>{{ probeData.streamKey }}</code></el-descriptions-item>
                <el-descriptions-item label="媒体服务器">
                    <el-tag size="small" :type="probeData.online ? 'success' : 'info'">{{ probeData.online ? '在线' : '不在线' }}</el-tag>
                    <span v-if="probeData.online" style="margin-left:8px">{{ probeData.originName }} · 观看 {{ probeData.readerCount }} 人 · {{ probeData.schemas }}</span>
                </el-descriptions-item>
                <el-descriptions-item label="拉流代理">
                    <el-tag size="small" :type="probeData.proxyRegistered ? 'warning' : 'info'">{{ probeData.proxyRegistered ? '已注册（未上线）' : '无' }}</el-tag>
                </el-descriptions-item>
                <el-descriptions-item label="拉流地址">{{ probeData.pullUrl || '未配置（该通道为推流型）' }}</el-descriptions-item>
                <el-descriptions-item label="推流地址"><code>{{ probeData.pushUrl }}</code></el-descriptions-item>
                <el-descriptions-item label="播放地址"><code>{{ probeData.httpFlvUrl }}</code></el-descriptions-item>
                <el-descriptions-item label="结论">{{ probeData.conclusion }}</el-descriptions-item>
            </el-descriptions>
            <template #footer><el-button type="primary" @click="probeDlg = false">知道了</el-button></template>
        </el-dialog>
    </div>`,
    setup() {
        const rows = ref([]), devices = ref([]), total = ref(0), page = ref(1), size = 10;
        const loading = ref(false), saving = ref(false), dlg = ref(false);
        const form = reactive({});
        // 流标识探测：直接向媒体服务器问真实状态，排查"通道 ↔ 流标识"对不上
        const probeDlg = ref(false);
        const probeData = ref(null);
        // 列表筛选条件（keyword / deviceId / status 走后端；access 走前端当前页过滤）
        const q = reactive({ keyword: '', deviceId: null, status: null, access: null });

        function buildUrl() {
            let url = '/api/v1/channels?page=' + page.value + '&size=' + size;
            if (q.keyword) url += '&keyword=' + encodeURIComponent(q.keyword);
            if (q.deviceId !== null && q.deviceId !== '' && q.deviceId !== undefined) url += '&deviceId=' + q.deviceId;
            if (q.status !== null && q.status !== '' && q.status !== undefined) url += '&status=' + q.status;
            return url;
        }
        const visibleRows = computed(() => {
            if (!q.access) return rows.value;
            return rows.value.filter(r => (r.pullUrl ? 'PULL' : 'PUSH') === q.access);
        });
        function search() { page.value = 1; load(); }
        function resetQuery() {
            q.keyword = ''; q.deviceId = null; q.status = null; q.access = null;
            page.value = 1; load();
        }

        async function load() {
            loading.value = true;
            try {
                const d = await api(buildUrl());
                rows.value = d.list || [];
                total.value = d.total || 0;
                if (!devices.value.length) {
                    const dv = await api('/api/v1/devices?page=1&size=200');
                    devices.value = dv.list || [];
                }
            } catch (e) { ElMessage.error(e.message); }
            finally { loading.value = false; }
        }
        /**
         * 通道状态文案：只区分「在线 / 离线」。
         *
         * <p>在线 = 媒体服务器上确实有这路流，点开就能看到画面；离线 = 没流，播不了。
         * "推还是拉"已经由左侧「接入」列表达，不必在状态里重复一遍 ——
         * 三种文案（推流中/拉流中/待拉流）既啰嗦又容易让人误以为是三个不同状态。
         *
         * <p>注意：接口返回的 status 仍是 0 离线 / 1 在线 / 2 有流，这里统一折叠成两态。
         */
        function streamState(row) {
            return row.status > 0 ? ['在线', 'success'] : ['离线', 'info'];
        }
        /** 设备名兜底：接口返回 deviceName 就直接用；老版本后端只给 deviceId 时用已加载的设备表映射 */
        function deviceNameOf(deviceId) {
            if (!deviceId) return '-';
            const d = devices.value.find(x => x.id === deviceId);
            return d ? d.deviceName : ('#' + deviceId);
        }
        function reset() {
            Object.assign(form, {
                id: null, deviceId: null, channelCode: '', channelName: '',
                streamApp: 'live', streamId: '', pullUrl: '', enabled: 1, accessType: 'PUSH'
            });
        }
        function openCreate() { reset(); dlg.value = true; }
        function openEdit(row) {
            reset();
            Object.assign(form, row);
            form.accessType = row.pullUrl ? 'PULL' : 'PUSH';
            dlg.value = true;
        }
        async function save() {
            if (!form.deviceId || !form.channelCode || !form.channelName || !form.streamId) {
                ElMessage.warning('设备、编码、名称、stream_id 均为必填'); return;
            }
            if (form.accessType === 'PULL' && !(form.pullUrl || '').trim()) {
                ElMessage.warning('选了拉流，必须填写拉流地址（rtsp://...）'); return;
            }
            saving.value = true;
            try {
                // 只提交后端认识的字段；非拉流型一律把 pullUrl 清空
                const body = JSON.stringify({
                    deviceId: form.deviceId, channelCode: form.channelCode, channelName: form.channelName,
                    streamApp: form.streamApp, streamId: form.streamId,
                    pullUrl: form.accessType === 'PULL' ? (form.pullUrl || '').trim() : '',
                    enabled: form.enabled
                });
                if (form.id) await api('/api/v1/channels/' + form.id, { method: 'PUT', body });
                else await api('/api/v1/channels', { method: 'POST', body });
                ElMessage.success('已保存');
                dlg.value = false;
                load();
            } catch (e) { ElMessage.error(e.message); }
            finally { saving.value = false; }
        }
        async function toggle(row, val) {
            try {
                const body = JSON.stringify({
                    deviceId: row.deviceId, channelCode: row.channelCode, channelName: row.channelName,
                    streamApp: row.streamApp, streamId: row.streamId, pullUrl: row.pullUrl,
                    enabled: val ? 1 : 0
                });
                await api('/api/v1/channels/' + row.id, { method: 'PUT', body });
                ElMessage.success(val ? '已启用' : '已停用（该路推流将被拒绝）');
                load();
            } catch (e) { ElMessage.error(e.message); load(); }
        }
        async function remove(row) {
            try {
                await ElMessageBox.confirm(
                    '确认删除通道「' + row.channelName + '」？\n\n'
                    + '· 这是逻辑删除，历史台账不受影响\n'
                    + '· 流标识 ' + row.streamApp + '/' + row.streamId + ' 会被保留占用\n'
                    + '   （再次登记同一流标识会自动复用这条记录）\n'
                    + '· 如果只是想停止使用，建议点【停用】—— 停用后该路推流会被拒绝',
                    '删除通道', { type: 'warning', confirmButtonText: '仍要删除', cancelButtonText: '取消' });
                await api('/api/v1/channels/' + row.id, { method: 'DELETE' });
                ElMessage.success('已删除');
                load();
            } catch (e) { if (e !== 'cancel') ElMessage.error(e.message || ''); }
        }
        /** 探测流标识：媒体服务器上到底有没有这路流、是推还是拉、有没有残留代理 */
        async function probe(row) {
            try {
                probeData.value = await api('/api/v1/channels/' + row.id + '/probe');
                probeDlg.value = true;
            } catch (e) { ElMessage.error(e.message); }
        }
        /** 平台发起拉流：让媒体服务器去拉该通道配置的拉流地址 */
        async function pull(row) {
            try {
                const d = await api('/api/v1/channels/' + row.id + '/pull', { method: 'POST' });
                ElMessage.success(d.tip || '拉流任务已下发');
                setTimeout(load, 1200);
            } catch (e) { ElMessage.error(e.message); }
        }
        /** 停止拉流：删除媒体服务器上的拉流代理 */
        async function unpull(row) {
            try {
                await ElMessageBox.confirm('停止拉流后该通道会变回离线，确认继续？', '停止拉流', { type: 'warning' });
                const d = await api('/api/v1/channels/' + row.id + '/unpull', { method: 'POST' });
                ElMessage.success(d.tip || '已停止拉流');
                setTimeout(load, 1200);
            } catch (e) { if (e !== 'cancel') ElMessage.error(e.message || ''); }
        }
        onMounted(load);
        return { rows, devices, total, page, size, loading, saving, dlg, form,
            load, openCreate, openEdit, save, toggle, remove, pull, unpull, probe, deviceNameOf,
            q, search, resetQuery, visibleRows, streamState,
            probeDlg, probeData, fmtTime };
    }
};

/* ========================= 4. 实时视频 ========================= */
const LiveVideo = {
    template: `
    <div>
        <div class="page-head">
            <div><h3>实时视频</h3><div class="sub">只显示当前有流在推的通道；点击卡片播放</div></div>
            <el-button size="small" @click="load">刷新</el-button>
        </div>
        <div v-if="online.length === 0" class="page-card">
            <div class="empty">
                当前没有在线流
            </div>
        </div>
        <div class="stream-grid">
            <div class="stream-card" v-for="s in online" :key="s.streamKey" @click="play(s)">
                <div class="name"><span class="online-dot"></span>{{ s.channelName }}</div>
                <div class="key">{{ s.streamKey }}</div>
                <div class="meta">观看人数：{{ s.readerCount }} · 协议：{{ (s.schemas || []).length }} 种</div>
                <div class="rec-row">
                    <el-tag v-if="s.recording" size="small" type="danger">● 录制中</el-tag>
                    <el-tag v-else size="small" type="info">未录制</el-tag>
                    <el-button size="small" plain :type="s.recording ? 'danger' : 'primary'"
                        :loading="recBusy === s.channelId" @click.stop="toggleRecord(s)">
                        {{ s.recording ? '停止录像' : '开始录像' }}
                    </el-button>
                </div>
            </div>
        </div>

        <el-dialog v-model="dlg" :title="current.channelName || '播放'" width="860px" @closed="stopPlay">
            <div class="filter-row" style="margin-bottom:10px;align-items:center">
                <span style="font-size:13px;color:#606266">播放协议</span>
                <el-select v-model="protocol" size="small" style="width:190px" @change="switchProtocol">
                    <el-option v-for="p in protocols" :key="p.value" :label="p.label"
                        :value="p.value" :disabled="p.disabled" />
                </el-select>
                <span style="font-size:12px;color:#909399">{{ protocolTip }}</span>
            </div>
            <div class="player-box"><video ref="videoEl" muted playsinline controls></video></div>
            <div class="form-tip" style="margin-top:8px;word-break:break-all">{{ address }}</div>
            <el-alert v-if="notice" :title="notice" type="warning" :closable="false" show-icon style="margin-top:8px" />
            <template #footer><el-button @click="dlg = false">关闭</el-button></template>
        </el-dialog>
    </div>`,
    setup() {
        const all = ref([]), loading = ref(false), dlg = ref(false);
        const current = reactive({ channelName: '', channelId: null });
        const address = ref('');
        const videoEl = ref(null);
        let player = null;
        let hls = null;
        const protocol = ref('flv');
        const notice = ref('');
        // 当前协议的一句话说明（延迟 / 是否需要外部播放器）
        const protocolTip = computed(() => {
            const m = protocols.find(x => x.value === protocol.value);
            return m ? m.tip : '';
        });
        // 协议转换全部由媒体服务器完成，网关只负责签发出不同协议的地址
        const protocols = [
            { value: 'flv', label: 'HTTP-FLV', tip: '浏览器 + flv.js，延迟 1~3 秒' },
            { value: 'ws-flv', label: 'WS-FLV', tip: '浏览器 + flv.js(WebSocket)，延迟 1~3 秒' },
            { value: 'hls', label: 'HLS', tip: '浏览器 + hls.js，延迟 10~30 秒' },
            { value: 'webrtc', label: 'WebRTC', tip: '需媒体服务器编译 WebRTC（当前 ZLM 不支持）', disabled: true },
            { value: 'rtsp', label: 'RTSP', tip: '浏览器不支持，需 VLC/PotPlayer/ffmpeg' },
            { value: 'rtmp', label: 'RTMP', tip: '浏览器不支持，需 VLC/ffmpeg' }
        ];
        const online = computed(() => all.value.filter(s => s.online));
        const recBusy = ref(null);

        /** 开始 / 停止录像（对应 ZLM 的 startRecord / stopRecord） */
        async function toggleRecord(s) {
            if (recBusy.value) return;
            recBusy.value = s.channelId;
            try {
                const act = s.recording ? 'stop' : 'start';
                await api('/api/v1/channels/' + s.channelId + '/record/' + act, { method: 'POST' });
                ElMessage.success(act === 'start'
                    ? '已开始录像（切片完成后会写入录像台账）'
                    : '已停止录像');
                await load();
            } catch (e) {
                ElMessage.error(e.message);
            } finally {
                recBusy.value = null;
            }
        }

        async function load() {
            loading.value = true;
            try { all.value = await api('/api/v1/streams'); }
            catch (e) { ElMessage.error(e.message); }
            finally { loading.value = false; }
        }
        function stopPlay() {
            if (player) { try { player.destroy(); } catch (e) {} player = null; }
            if (hls) { try { hls.destroy(); } catch (e) {} hls = null; }
            const v = videoEl.value;
            if (v) { try { v.pause(); } catch (e) {} v.removeAttribute('src'); try { v.load(); } catch (e) {} }
        }
        async function tryPlay() {
            const v = videoEl.value;
            for (let i = 0; i < 4; i++) {
                try { await v.play(); return; }
                catch (e) { if (/interrupted/i.test(e.message)) { await new Promise(r => setTimeout(r, 200)); continue; } return; }
            }
        }
        /** 按当前协议取地址并起播（协议转换由 ZLM 完成，我们只是换一个出口地址） */
        async function startPlay() {
            stopPlay();
            notice.value = '';
            const p = protocol.value;
            const meta = protocols.find(x => x.value === p);
            let addr;
            try {
                addr = await api('/api/v1/channels/' + current.channelId + '/play-address?protocol=' + p);
            } catch (e) { ElMessage.error(e.message); return; }
            address.value = addr.url;
            const v = videoEl.value;
            if (!v) return;
            if (p === 'flv' || p === 'ws-flv') {
                if (typeof flvjs === 'undefined') { ElMessage.error('flv.js 未加载'); return; }
                player = flvjs.createPlayer({ type: 'flv', url: addr.url, isLive: true, hasAudio: false, hasVideo: true });
                player.attachMediaElement(v);
                player.load();
                await tryPlay();
            } else if (p === 'hls') {
                if (window.Hls && window.Hls.isSupported()) {
                    hls = new window.Hls({ lowLatencyMode: true, liveSyncDurationCount: 3 });
                    hls.loadSource(addr.url);
                    hls.attachMedia(v);
                    hls.on(window.Hls.Events.MANIFEST_PARSED, () => tryPlay());
                } else if (v.canPlayType('application/vnd.apple.mpegurl')) {
                    v.src = addr.url;
                    await tryPlay();
                } else {
                    notice.value = '当前浏览器不支持 HLS（缺少 hls.js 且无原生支持），可复制上方地址用 VLC 打开';
                }
            } else if (p === 'webrtc') {
                notice.value = 'WebRTC 需要媒体服务器编译 WebRTC 模块，当前 ZLM 构建不含该能力：地址会返回但连不上。请改用 HTTP-FLV / HLS，或用 VLC 打开 RTSP 地址';
            } else {
                notice.value = (meta ? meta.tip : '浏览器不支持该协议') + '。地址已显示在上方，可复制到外部播放器打开';
            }
        }
        async function switchProtocol() {
            if (dlg.value) await startPlay();
        }
        async function play(s) {
            Object.assign(current, { channelName: s.channelName, channelId: s.channelId });
            dlg.value = true;
            await nextTick();
            await startPlay();
        }
        onMounted(load);
        onBeforeUnmount(stopPlay);
        return { all, online, loading, dlg, current, address, videoEl, recBusy,
            protocol, protocols, protocolTip, notice, switchProtocol, load, play, startPlay, stopPlay, toggleRecord };
    }
};

/* ========================= 5. 业务台账 · 事件流水 ========================= */
const LedgerEvents = {
    template: `
    <div class="page-card">
        <div class="page-head">
            <div><h3>事件流水</h3></div>
        </div>
        <div class="filter-row">
            <el-select v-model="q.channelId" placeholder="全部通道" clearable style="width:180px">
                <el-option v-for="c in channels" :key="c.id" :label="c.channelName" :value="c.id" />
            </el-select>
            <el-select v-model="q.eventType" placeholder="全部类型" clearable style="width:200px">
                <el-option label="全部类型" value="" />
                <el-option-group v-for="g in eventGroups" :key="g.label" :label="g.label">
                    <el-option :label="'全部' + g.label" :value="'GROUP:' + g.label" />
                    <el-option v-for="t in g.types" :key="t" :label="eventMap[t][0]" :value="t" />
                </el-option-group>
            </el-select>
            <el-select v-model="q.clientId" placeholder="全部客户端" clearable style="width:160px">
                <el-option v-for="c in clients" :key="c.id" :label="c.clientName" :value="c.id" />
            </el-select>
            <el-date-picker v-model="range" type="datetimerange" start-placeholder="开始时间" end-placeholder="结束时间"
                value-format="YYYY-MM-DD HH:mm:ss" style="width:340px" />
            <el-button type="primary" @click="search">查询</el-button>
            <el-button @click="reset">重置</el-button>
        </div>
        <el-table :data="rows" size="small" v-loading="loading">
            <el-table-column label="时间" width="170">
                <template #default="sc">{{ fmtTime(sc.row.eventTime) }}</template>
            </el-table-column>
            <el-table-column label="事件" width="110">
                <template #default="sc">
                    <el-tag size="small" :type="evType(sc.row.eventType)">{{ evLabel(sc.row.eventType) }}</el-tag>
                </template>
            </el-table-column>
            <el-table-column label="通道" width="180">
                <template #default="sc">
                    <div v-if="sc.row.channelCode || sc.row.streamKey">
                        <code>{{ sc.row.channelCode || ('#' + sc.row.channelId) }}</code>
                        <div style="font-size:11px;color:#909399">{{ sc.row.streamKey }}</div>
                    </div>
                    <span v-else-if="sc.row.channelId">{{ channelName(sc.row.channelId) }}</span>
                    <span v-else>-</span>
                </template>
            </el-table-column>
            <el-table-column label="客户端" width="140" show-overflow-tooltip>
                <template #default="sc">{{ clientName(sc.row.clientId) }}</template>
            </el-table-column>
            <el-table-column prop="source" label="来源" width="80" />
            <el-table-column label="结果" width="80">
                <template #default="sc">
                    <el-tag v-if="sc.row.resultCode === 0" size="small" type="success">成功</el-tag>
                    <el-tag v-else size="small" type="danger">{{ sc.row.resultCode }}</el-tag>
                </template>
            </el-table-column>
            <el-table-column prop="resultMsg" label="说明" width="180" show-overflow-tooltip />
            <el-table-column label="原始报文" min-width="240">
                <template #default="sc">
                    <div v-if="sc.row.detail" class="json-cell">{{ pretty(sc.row.detail) }}</div>
                    <span v-else>-</span>
                </template>
            </el-table-column>
        </el-table>
        <el-pagination class="pager" background layout="total, sizes, prev, pager, next"
            :total="total" :page-size="size" :current-page="page" :page-sizes="[10, 20, 50]"
            @current-change="p => { page = p; load(); }"
            @size-change="s => { size = s; page = 1; load(); }" />
    </div>`,
    setup() {
        const rows = ref([]), total = ref(0), page = ref(1), size = ref(20);
        const loading = ref(false), channels = ref([]), clients = ref([]), range = ref(null);
        const q = reactive({ channelId: null, eventType: null, clientId: null });

        async function loadMeta() {
            try {
                const c = await api('/api/v1/channels?page=1&size=200');
                channels.value = c.list || [];
                const cl = await api('/api/v1/api-clients?page=1&size=100');
                clients.value = cl.list || [];
            } catch (e) { /* 元数据失败不阻塞 */ }
        }
        async function load() {
            loading.value = true;
            try {
                let url = '/api/v1/ledger?page=' + page.value + '&size=' + size.value;
                if (q.channelId) url += '&channelId=' + q.channelId;
                if (q.eventType) {
                    if (q.eventType.startsWith('GROUP:')) {
                        const gname = q.eventType.slice(6);
                        const g = EVENT_GROUPS.find(x => x.label === gname);
                        if (g) url += '&eventTypes=' + encodeURIComponent(g.types.join(','));
                    } else {
                        url += '&eventType=' + encodeURIComponent(q.eventType);
                    }
                }
                if (q.clientId) url += '&clientId=' + q.clientId;
                if (range.value && range.value.length === 2) {
                    url += '&beginTime=' + encodeURIComponent(range.value[0]);
                    url += '&endTime=' + encodeURIComponent(range.value[1]);
                }
                const d = await api(url);
                rows.value = d.list || [];
                total.value = d.total || 0;
            } catch (e) { ElMessage.error(e.message); }
            finally { loading.value = false; }
        }
        function search() { page.value = 1; load(); }
        /** 设备名兜底：接口返回 deviceName 就直接用；老版本后端只给 deviceId 时用已加载的设备表映射 */
        function deviceNameOf(deviceId) {
            if (!deviceId) return '-';
            const d = devices.value.find(x => x.id === deviceId);
            return d ? d.deviceName : ('#' + deviceId);
        }
        function reset() {
            q.channelId = q.eventType = q.clientId = null;
            range.value = null;
            search();
        }
        const channelName = (id) => {
            if (!id) return '-';
            const c = channels.value.find(x => x.id === id);
            return c ? c.channelName : ('#' + id);
        };
        const clientName = (id) => {
            if (!id) return '-';
            const c = clients.value.find(x => x.id === id);
            return c ? c.clientName : ('#' + id);
        };
        const pretty = (s) => {
            try { return JSON.stringify(JSON.parse(s), null, 1); } catch (e) { return s; }
        };
        onMounted(async () => { await loadMeta(); await load(); });
        return { rows, total, page, size, loading, q, range, channels, clients, eventMap: EVENT_MAP, eventGroups: EVENT_GROUPS,
            load, search, reset, channelName, clientName, pretty, fmtTime, evLabel, evType };
    }
};

/* ========================= 6. （旧）流会话 + 录像合并页签，已被下方独立页面取代 ========================= */
const SessionsRecordings = {
    template: `
    <div class="page-card">
        <div class="page-head">
            <div><h3>流会话 / 录像</h3><div class="sub">聚合视图：一次推流一行，含时长与结束原因</div></div>
        </div>
        <el-tabs v-model="tab">
            <el-tab-pane label="流会话" name="sessions">
                <el-table :data="sessions" size="small" v-loading="loading1">
                    <el-table-column prop="sessionNo" label="会话单号" width="190" />
                    <el-table-column prop="streamKey" label="流标识" width="150" />
                    <el-table-column label="开始时间" width="165">
                        <template #default="sc">{{ fmtTime(sc.row.startTime) }}</template>
                    </el-table-column>
                    <el-table-column label="结束时间" width="165">
                        <template #default="sc">{{ fmtTime(sc.row.endTime) }}</template>
                    </el-table-column>
                    <el-table-column label="时长" width="110">
                        <template #default="sc">{{ fmtDur(sc.row.durationSec) }}</template>
                    </el-table-column>
                    <el-table-column label="结束方式" width="110">
                        <template #default="sc">
                            <el-tag v-if="sc.row.endReason" size="small"
                                :type="sc.row.endReason === 'NORMAL' ? 'success' : 'warning'">
                                {{ sc.row.endReason }}
                            </el-tag>
                            <span v-else>-</span>
                        </template>
                    </el-table-column>
                    <el-table-column label="状态" width="84">
                        <template #default="sc">
                            <el-tag size="small" :type="sc.row.status === 1 ? 'warning' : (sc.row.status === 2 ? 'success' : 'danger')">
                                {{ sc.row.status === 1 ? '进行中' : (sc.row.status === 2 ? '正常结束' : '异常结束') }}
                            </el-tag>
                        </template>
                    </el-table-column>
                </el-table>
                <el-pagination class="pager" background layout="total, prev, pager, next"
                    :total="total1" :page-size="size1" :current-page="page1"
                    @current-change="p => { page1 = p; loadSessions(); }" />
            </el-tab-pane>

            <el-tab-pane label="录像记录" name="records">
                <el-table :data="records" size="small" v-loading="loading2">
                    <el-table-column prop="streamKey" label="流标识" width="150" />
                    <el-table-column prop="fileName" label="文件名" width="180" />
                    <el-table-column label="大小" width="110">
                        <template #default="sc">{{ fmtSize(sc.row.fileSize) }}</template>
                    </el-table-column>
                    <el-table-column label="时长" width="100">
                        <template #default="sc">{{ fmtDur(sc.row.durationSec) }}</template>
                    </el-table-column>
                    <el-table-column label="视频时间段" min-width="220">
                        <template #default="sc">
                            {{ fmtTime(sc.row.videoStartTime) }} ~ {{ fmtTime(sc.row.videoEndTime) }}
                        </template>
                    </el-table-column>
                    <el-table-column label="回放" width="90">
                        <template #default="sc">
                            <el-button v-if="sc.row.filePath" link type="primary" size="small"
                                @click="openVideo(sc.row.filePath)">播放</el-button>
                            <span v-else>-</span>
                        </template>
                    </el-table-column>
                </el-table>
                <el-pagination class="pager" background layout="total, prev, pager, next"
                    :total="total2" :page-size="size2" :current-page="page2"
                    @current-change="p => { page2 = p; loadRecords(); }" />
            </el-tab-pane>
        </el-tabs>
    </div>`,
    setup() {
        const tab = ref('sessions');
        const sessions = ref([]), total1 = ref(0), page1 = ref(1), size1 = 10, loading1 = ref(false);
        const records = ref([]), total2 = ref(0), page2 = ref(1), size2 = 10, loading2 = ref(false);

        async function loadSessions() {
            loading1.value = true;
            try {
                const d = await api('/api/v1/sessions?page=' + page1.value + '&size=' + size1);
                sessions.value = d.list || [];
                total1.value = d.total || 0;
            } catch (e) { ElMessage.error(e.message); }
            finally { loading1.value = false; }
        }
        async function loadRecords() {
            loading2.value = true;
            try {
                const d = await api('/api/v1/recordings?page=' + page2.value + '&size=' + size2);
                records.value = d.list || [];
                total2.value = d.total || 0;
            } catch (e) { ElMessage.error(e.message); }
            finally { loading2.value = false; }
        }
        function openVideo(url) { window.open(url, '_blank'); }

        onMounted(() => { loadSessions(); loadRecords(); });
        return { tab, sessions, total1, page1, size1, loading1,
            records, total2, page2, size2, loading2,
            loadSessions, loadRecords, openVideo, fmtTime, fmtDur, fmtSize };
    }
};

/* ========================= 6.1 业务台账 · 流会话 ========================= */
const Sessions = {
    template: `
    <div class="page-card">
        <div class="page-head">
            <div>
                <h3>流会话</h3>
            </div>
            <el-button size="small" @click="load">刷新</el-button>
        </div>
        <div class="filter-row">
            <el-select v-model="q.channelId" placeholder="全部通道" clearable filterable style="width:200px" @change="search">
                <el-option v-for="c in channels" :key="c.id"
                    :label="c.channelName + '（' + c.streamApp + '/' + c.streamId + '）'" :value="c.id" />
            </el-select>
            <el-select v-model="q.status" placeholder="全部状态" clearable style="width:130px" @change="search">
                <el-option label="进行中" :value="1" />
                <el-option label="正常结束" :value="2" />
                <el-option label="异常结束" :value="3" />
            </el-select>
            <el-date-picker v-model="range" type="datetimerange" start-placeholder="开始时间" end-placeholder="结束时间"
                value-format="YYYY-MM-DD HH:mm:ss" style="width:340px" @change="search" />
            <el-button type="primary" @click="search">查询</el-button>
            <el-button @click="resetQuery">重置</el-button>
        </div>
        <el-table :data="rows" size="small" v-loading="loading">
            <el-table-column prop="sessionNo" label="会话单号" width="190" />
            <el-table-column prop="streamKey" label="流标识" width="150" />
            <el-table-column label="开始时间" width="165">
                <template #default="sc">{{ fmtTime(sc.row.startTime) }}</template>
            </el-table-column>
            <el-table-column label="结束时间" width="165">
                <template #default="sc">{{ fmtTime(sc.row.endTime) }}</template>
            </el-table-column>
            <el-table-column label="时长" width="110">
                <template #default="sc">{{ fmtDur(sc.row.durationSec) }}</template>
            </el-table-column>
            <el-table-column label="结束方式" width="110">
                <template #default="sc">
                    <el-tag v-if="sc.row.endReason" size="small"
                        :type="sc.row.endReason === 'NORMAL' ? 'success' : 'warning'">
                        {{ sc.row.endReason }}
                    </el-tag>
                    <span v-else>-</span>
                </template>
            </el-table-column>
            <el-table-column label="状态" width="84">
                <template #default="sc">
                    <el-tag size="small"
                        :type="sc.row.status === 1 ? 'warning' : (sc.row.status === 2 ? 'success' : 'danger')">
                        {{ sc.row.status === 1 ? '进行中' : (sc.row.status === 2 ? '正常结束' : '异常结束') }}
                    </el-tag>
                </template>
            </el-table-column>
            <el-table-column prop="clientIp" label="推流方 IP" min-width="120" />
        </el-table>
        <el-pagination class="pager" background layout="total, prev, pager, next"
            :total="total" :page-size="size" :current-page="page"
            @current-change="p => { page = p; load(); }" />
    </div>`,
    setup() {
        const rows = ref([]), total = ref(0), page = ref(1), size = 10, loading = ref(false);
        const q = reactive({ channelId: null, status: null });
        const range = ref(null);
        const channels = ref([]);
        function buildUrl() {
            let url = '/api/v1/sessions?page=' + page.value + '&size=' + size;
            if (q.channelId) url += '&channelId=' + q.channelId;
            if (q.status !== null && q.status !== '' && q.status !== undefined) url += '&status=' + q.status;
            if (range.value && range.value.length === 2) {
                url += '&beginTime=' + encodeURIComponent(range.value[0]);
                url += '&endTime=' + encodeURIComponent(range.value[1]);
            }
            return url;
        }
        function search() { page.value = 1; load(); }
        function resetQuery() {
            q.channelId = null; q.status = null; range.value = null;
            page.value = 1; load();
        }
        async function loadChannels() {
            try { const d = await api('/api/v1/channels?page=1&size=200'); channels.value = d.list || []; } catch (e) {}
        }
        async function load() {
            loading.value = true;
            try {
                const d = await api(buildUrl());
                rows.value = d.list || [];
                total.value = d.total || 0;
            } catch (e) { ElMessage.error(e.message); }
            finally { loading.value = false; }
        }
        onMounted(load);
        onMounted(loadChannels);
        return { rows, total, page, size, loading, q, range, channels, search, resetQuery,
            load, fmtTime, fmtDur };
    }
};

/* ========================= 6.2 业务台账 · 录像 ========================= */
const Records = {
    template: `
    <div class="page-card">
        <div class="page-head">
            <div>
                <h3>录像</h3>
            </div>
            <el-button size="small" @click="load">刷新</el-button>
        </div>
        <div class="filter-row">
            <el-select v-model="q.channelId" placeholder="全部通道" clearable filterable style="width:220px" @change="search">
                <el-option v-for="c in channels" :key="c.id"
                    :label="c.channelName + '（' + c.streamApp + '/' + c.streamId + '）'" :value="c.id" />
            </el-select>
            <el-select v-model="q.fileState" placeholder="全部文件状态" clearable style="width:150px">
                <el-option label="文件存在" value="EXIST" />
                <el-option label="已清理" value="PURGED" />
            </el-select>
            <el-button type="primary" @click="search">查询</el-button>
            <el-button @click="resetQuery">重置</el-button>
        </div>
        <el-table :data="visibleRows" size="small" v-loading="loading">
            <el-table-column prop="streamKey" label="流标识" width="150" />
            <el-table-column prop="fileName" label="文件名" width="180" />
            <el-table-column label="大小" width="110">
                <template #default="sc">{{ fmtSize(sc.row.fileSize) }}</template>
            </el-table-column>
            <el-table-column label="时长" width="100">
                <template #default="sc">{{ fmtDur(sc.row.durationSec) }}</template>
            </el-table-column>
            <el-table-column label="视频时间段" min-width="240">
                <template #default="sc">
                    {{ fmtTime(sc.row.videoStartTime) }} ~ {{ fmtTime(sc.row.videoEndTime) }}
                </template>
            </el-table-column>
            <el-table-column label="来源" width="90">
                <template #default="sc">
                    <el-tag size="small" :type="sc.row.source === 'AUTO' ? 'primary' : 'info'">
                        {{ sc.row.source === 'AUTO' ? '自动' : '手动' }}
                    </el-tag>
                </template>
            </el-table-column>
            <el-table-column label="文件" width="100">
                <template #default="sc">
                    <el-tag v-if="sc.row.fileExists === 0" size="small" type="info">已清理</el-tag>
                    <el-tag v-else size="small" type="success">存在</el-tag>
                </template>
            </el-table-column>
            <el-table-column label="操作" width="240">
                <template #default="sc">
                    <el-button v-if="sc.row.fileExists !== 0 && sc.row.filePath" link type="primary" size="small"
                        @click="openVideo(sc.row.filePath)">回放</el-button>
                    <el-button v-if="sc.row.fileExists !== 0" link type="success" size="small"
                        :loading="downloading === sc.row.id" @click="download(sc.row)">下载</el-button>
                    <el-button v-if="sc.row.fileExists !== 0" link type="danger" size="small"
                        :loading="purging === sc.row.id" @click="purge(sc.row)">清理文件</el-button>
                    <span v-if="sc.row.fileExists === 0" style="color:#909399;font-size:12px">
                        {{ fmtTime(sc.row.purgedTime) }} 已清理
                    </span>
                </template>
            </el-table-column>
        </el-table>
        <el-pagination class="pager" background layout="total, prev, pager, next"
            :total="total" :page-size="size" :current-page="page"
            @current-change="p => { page = p; load(); }" />
    </div>`,
    setup() {
        const rows = ref([]), total = ref(0), page = ref(1), size = 10, loading = ref(false);
        const q = reactive({ channelId: null, fileState: null });
        const channels = ref([]);
        function buildUrl() {
            let url = '/api/v1/recordings?page=' + page.value + '&size=' + size;
            if (q.channelId) url += '&channelId=' + q.channelId;
            return url;
        }
        // 文件状态后端不支持过滤，前端按当前页筛（列表页分页小，够用）
        const visibleRows = computed(() => {
            if (!q.fileState) return rows.value;
            return rows.value.filter(r => (r.fileExists === 0 ? 'PURGED' : 'EXIST') === q.fileState);
        });
        function search() { page.value = 1; load(); }
        function resetQuery() {
            q.channelId = null; q.fileState = null;
            page.value = 1; load();
        }
        async function loadChannels() {
            try { const d = await api('/api/v1/channels?page=1&size=200'); channels.value = d.list || []; } catch (e) {}
        }
        async function load() {
            loading.value = true;
            try {
                const d = await api(buildUrl());
                rows.value = d.list || [];
                total.value = d.total || 0;
            } catch (e) { ElMessage.error(e.message); }
            finally { loading.value = false; }
        }
        function openVideo(url) { window.open(url, '_blank'); }

        const downloading = ref(null);
        /**
         * 下载录像文件（走网关接口，不是在浏览器里内联播放）。
         * 为什么不直接 window.open(filePath)：那样是直连 ZLM 的静态目录，
         * 绕过了 api-key 鉴权和通道级隔离；而且浏览器对 mp4 是内联播放，不是保存。
         * 这里用 fetch 带鉴权头拿到二进制，再用 <a download> 触发另存为。
         */
        async function download(row) {
            downloading.value = row.id;
            try {
                const headers = { 'X-Request-From': 'ui' };
                const k = getKey();
                if (k) headers['api-key'] = k;
                const resp = await fetch('/api/v1/recordings/' + row.id + '/download', { headers });
                if (resp.status === 401) {
                    const body = await resp.json().catch(() => null);
                    const reason = (body && body.msg) ? body.msg : '调用凭证缺失或已失效，请重新输入';
                    setKey('');
                    window.dispatchEvent(new CustomEvent('vgw-unauthorized', { detail: reason }));
                    throw new Error(reason);
                }
                if (!resp.ok) {
                    let msg = '下载失败（HTTP ' + resp.status + '）';
                    try { const j = await resp.json(); if (j && j.msg) msg = j.msg; } catch (e) {}
                    throw new Error(msg);
                }
                // 文件名优先后端 Content-Disposition 里的（RFC 5987 编码，含中文）
                let name = row.fileName || ('recording-' + row.id + '.mp4');
                const cd = resp.headers.get('Content-Disposition') || '';
                const m = /filename\*=UTF-8''([^;]+)/i.exec(cd);
                if (m) { try { name = decodeURIComponent(m[1]); } catch (e) {} }
                const blob = await resp.blob();
                const url = URL.createObjectURL(blob);
                const a = document.createElement('a');
                a.href = url;
                a.download = name;
                document.body.appendChild(a);
                a.click();
                a.remove();
                setTimeout(() => URL.revokeObjectURL(url), 30000);
                ElMessage.success('已开始下载：' + name);
            } catch (e) {
                ElMessage.error(e.message || '下载失败');
            } finally {
                downloading.value = null;
            }
        }

        const purging = ref(null);
        /** 清理磁盘上的录像文件（台账记录保留） */
        async function purge(row) {
            try {
                await ElMessageBox.confirm(
                    '确认清理这段录像的文件？\n\n'
                    + '· 磁盘上的 mp4 会被永久删除（约 ' + fmtSize(row.fileSize) + '）\n'
                    + '· 台账记录会保留，仍可追溯"曾录过这段"\n'
                    + '· 此操作不可撤销',
                    '清理录像文件', { type: 'warning', confirmButtonText: '确认清理', cancelButtonText: '取消' });
                purging.value = row.id;
                const r = await api('/api/v1/recordings/' + row.id + '/purge', { method: 'POST' });
                ElMessage.success('已清理，释放 ' + (r.releasedMb || 0) + ' MB');
                await load();
            } catch (e) {
                if (e !== 'cancel') ElMessage.error(e.message || '');
            } finally {
                purging.value = null;
            }
        }
        onMounted(load);
        onMounted(loadChannels);
        return { rows, total, page, size, loading, purging, downloading, q, channels, visibleRows, search, resetQuery,
            load, openVideo, download, purge, fmtTime, fmtDur, fmtSize };
    }
};

/* ========================= 6.3 接口文档 ========================= */
const ApiDoc = {
    template: `
    <div class="page-card">
        <div class="page-head">
            <div>
                <h3>接口文档</h3>
                <div class="sub">
                    Knife4j 自动生成（来自后端 /v3/api-docs）。
                    调用任意接口需在文档页的「全局参数」里添加 <code>api-key</code>
                </div>
            </div>
            <el-button size="small" type="primary" @click="openNew">在新窗口打开</el-button>
        </div>
        <div class="doc-frame">
            <iframe src="/doc.html" title="接口文档"></iframe>
        </div>
        <div class="form-tip" style="margin-top:10px">
            如果下方内嵌页空白，说明浏览器或后端禁止 iframe 嵌入 —— 点右上角「在新窗口打开」即可。
        </div>
    </div>`,
    setup() {
        function openNew() { window.open('/doc.html', '_blank'); }
        return { openNew };
    }
};

/* ========================= 6.4 授权范围树（可复用组件）========================= */
/**
 * 「设备 → 通道」两层树形授权选择器。
 *
 * 用法：<ScopeTree v-model="scope" :channels="channels" />
 *   scope    = { scopeMode: 'ALL' | 'SCOPE', devices: [设备id], channels: [通道id] }
 *   channels = 通道列表（/api/v1/channels 返回，带 deviceName）
 *
 * 为什么抽成组件：创建客户端、修改授权两个弹窗都要用它，
 * 而 el-tree 的 ref 在同一页面不能重复（两个实例会抢同一个 ref），
 * 封装后各自维护自己的勾选状态。
 */
const ScopeTree = {
    props: {
        modelValue: { type: Object, required: true },
        channels: { type: Array, default: () => [] },
        /** true 时整棵树只读（用于"全部通道"模式） */
        readonly: { type: Boolean, default: false }
    },
    emits: ['update:modelValue'],
    template: `
    <div>
        <div class="filter-row" style="margin-bottom:8px;align-items:center">
            <el-input v-model="filter" placeholder="搜索 设备名 / 通道名 / 编码 / 流标识"
                clearable size="small" style="width:280px" @input="onFilter" />
            <span style="flex:1"></span>
            <el-button size="small" @click="checkAll">全部授权</el-button>
            <el-button size="small" @click="clearAll">清空</el-button>
        </div>
        <div :style="readonly ? 'border:1px solid #e4e7ed;border-radius:6px;max-height:300px;overflow:auto;padding:8px;opacity:.45;pointer-events:none' : 'border:1px solid #e4e7ed;border-radius:6px;max-height:300px;overflow:auto;padding:8px'">
            <el-tree ref="treeRef" :data="treeData" show-checkbox node-key="key" default-expand-all
                :props="{ label: 'label', children: 'children' }"
                :filter-node-method="filterNode" :expand-on-click-node="false"
                @check="onCheck">
                <template #default="{ data }">
                    <span style="display:flex;align-items:center;gap:6px">
                        <span>{{ data.label }}</span>
                        <el-tag v-if="data.type === 'device'" size="small" type="info">{{ data.children.length }} 个通道</el-tag>
                        <el-tag v-if="data.pull" size="small" type="warning">拉流</el-tag>
                    </span>
                </template>
            </el-tree>
        </div>
    </div>`,
    setup(props, { emit }) {
        const treeRef = ref(null);
        const treeData = ref([]);
        const filter = ref('');
        const deviceOfChannel = {};

        const devices = ref([]);
        const channelsSel = ref([]);


        function buildTree() {
            const map = new Map();
            props.channels.forEach(c => {
                deviceOfChannel[c.id] = c.deviceId;
                const k = c.deviceId || 0;
                if (!map.has(k)) {
                    map.set(k, {
                        key: 'D' + k, type: 'device', deviceId: c.deviceId,
                        label: c.deviceName || ('设备#' + k), children: []
                    });
                }
                map.get(k).children.push({
                    key: 'C' + c.id, type: 'channel', channelId: c.id,
                    label: c.channelName + '（' + c.channelCode + '）· ' + c.streamApp + '/' + c.streamId,
                    pull: !!c.pullUrl
                });
            });
            treeData.value = Array.from(map.values());
        }
        /** 把外部 modelValue 回填到树的勾选态 */
        function fillChecked() {
            if (!treeRef.value) return;
            const keys = [];
            devices.value.forEach(id => keys.push('D' + id));
            channelsSel.value.forEach(id => keys.push('C' + id));
            treeRef.value.setCheckedKeys(keys, false);
        }
        /** 从树里读勾选：父节点=设备级授权，被子节点带上的通道不重复计入 */
        function readChecked() {
            if (!treeRef.value) return;
            const keys = treeRef.value.getCheckedKeys(false) || [];
            const d = keys.filter(k => k.charAt(0) === 'D').map(k => Number(k.slice(1)));
            const c = keys.filter(k => k.charAt(0) === 'C').map(k => Number(k.slice(1)))
                .filter(id => d.indexOf(deviceOfChannel[id]) < 0);
            devices.value = d;
            channelsSel.value = c;
        }
        function emitChange() {
            // 这个组件只负责"按范围授权"；不限的状态由调用方决定（不再提供 ALL 单选）
            emit('update:modelValue', {
                scopeMode: 'SCOPE',
                devices: devices.value.slice(),
                channels: channelsSel.value.slice()
            });
        }
        function onCheck() { readChecked(); emitChange(); }
        function checkAll() {
            if (!treeRef.value) return;
            // deep=true 才会级联勾上子通道（false 时只勾父节点，看着像没生效）
            treeData.value.forEach(n => treeRef.value.setChecked(n.key, true, true));
            readChecked();
            emitChange();
        }
        function clearAll() {
            if (treeRef.value) treeRef.value.setCheckedKeys([], false);
            devices.value = [];
            channelsSel.value = [];
            emitChange();
        }
        function onFilter(kw) { if (treeRef.value) treeRef.value.filter(kw || ''); }
        function filterNode(value, data) {
            if (!value) return true;
            const v = String(value).toLowerCase();
            if (String(data.label).toLowerCase().indexOf(v) >= 0) return true;
            if (data.children) {
                return data.children.some(c => String(c.label).toLowerCase().indexOf(v) >= 0);
            }
            return false;
        }

        watch(() => props.channels, () => { buildTree(); nextTick(fillChecked); }, { immediate: true });
        watch(() => props.modelValue, (v) => {
            devices.value = (v && v.devices ? v.devices : []).slice();
            channelsSel.value = (v && v.channels ? v.channels : []).slice();
            nextTick(fillChecked);
        }, { deep: true });

        return { treeRef, treeData, filter,
            onCheck, checkAll, clearAll, onFilter, filterNode };
    }
};

/* ========================= 7. API 客户端 ========================= */
const ApiClients = {
    components: { ScopeTree },
    template: `
    <div class="page-card">
        <div class="page-head">
            <div>
                <h3>API 客户端</h3>
            </div>
            <el-button type="primary" size="small" @click="openCreate">+ 创建客户端</el-button>
        </div>
        <div class="filter-row">
            <el-input v-model="q.keyword" placeholder="客户端名称 / appKey / 联系人" clearable style="width:260px"
                @keyup.enter="search" @clear="search" />
            <el-button type="primary" @click="search">查询</el-button>
            <el-button @click="resetQuery">重置</el-button>
        </div>
        <!-- fit=false：列宽就是设定的宽度，表格不再被拉伸填满容器，
             否则剩余宽度会被塞进某一列，造成"客户端名称和联系人之间一大片空白" -->
        <el-table :data="rows" size="small" v-loading="loading" :fit="false">
            <el-table-column prop="clientName" label="客户端名称" width="176" show-overflow-tooltip />
            <el-table-column label="联系人" width="96">
                <template #default="sc">{{ sc.row.contact || '-' }}</template>
            </el-table-column>
            <el-table-column label="权限范围" width="186">
                <template #default="sc">
                    <el-tag v-if="sc.row.isAdmin === 1" size="small" type="danger"
                        style="margin-right:6px">超管</el-tag>
                    <el-tooltip :content="scopeDetail(sc.row)" placement="top">
                        <el-tag size="small" :type="scopeTag(sc.row)[1]">{{ scopeTag(sc.row)[0] }}</el-tag>
                    </el-tooltip>
                </template>
            </el-table-column>
            <el-table-column prop="appKey" label="appKey" width="186" show-overflow-tooltip>
                <template #default="sc"><code>{{ sc.row.appKey }}</code></template>
            </el-table-column>
            <el-table-column label="状态" width="76">
                <template #default="sc">
                    <el-tag size="small" :type="clientState(sc.row)[1]">{{ clientState(sc.row)[0] }}</el-tag>
                </template>
            </el-table-column>
            <el-table-column label="失效时间" width="148">
                <template #default="sc">
                    <span v-if="!sc.row.expireTime">长期有效</span>
                    <span v-else :style="{ color: isExpired(sc.row) ? '#f56c6c' : '#303133' }">
                        {{ fmtTime(sc.row.expireTime) }}
                    </span>
                </template>
            </el-table-column>
            <el-table-column label="操作" width="300">
                <template #default="sc">
                    <el-button link size="small" :type="sc.row.enabled === 1 ? 'warning' : 'success'"
                        @click="toggle(sc.row)">{{ sc.row.enabled === 1 ? '停用' : '启用' }}</el-button>
                    <el-button link type="primary" size="small" @click="openChannels(sc.row)">通道授权</el-button>
                    <el-button link type="warning" size="small" @click="openExpire(sc.row)">续期</el-button>
                    <el-button link type="primary" size="small" @click="resetKey(sc.row)">重置密钥</el-button>
                    <el-button link type="danger" size="small" @click="remove(sc.row)">删除</el-button>
                </template>
            </el-table-column>
        </el-table>
        <el-pagination class="pager" background layout="total, prev, pager, next"
            :total="total" :page-size="size" :current-page="page"
            @current-change="p => { page = p; load(); }" />

        <el-dialog v-model="dlg" title="创建 API 客户端" width="560px">
            <el-form :model="form" label-width="auto">
                <el-form-item label="客户端名称" required>
                    <el-input v-model="form.clientName" placeholder="如：XX业务平台" />
                </el-form-item>
                <el-form-item label="联系人">
                    <el-input v-model="form.contact" placeholder="对接人姓名/工号，出问题好找人" />
                </el-form-item>
                <el-form-item label="备注"><el-input v-model="form.remark" /></el-form-item>
                <el-form-item label="权限范围">
                    <span style="font-size:13px;color:#e6a23c">创建后默认<b>无任何权限</b>，请在列表里点【通道授权】分配设备或通道</span>
                </el-form-item>
                <el-form-item label="失效时间">
                    <el-date-picker v-model="form.expireTime" type="datetime" placeholder="留空表示长期有效"
                        value-format="YYYY-MM-DD HH:mm:ss" style="width:100%" />
                </el-form-item>
            </el-form>
            <template #footer>
                <el-button @click="dlg = false">取消</el-button>
                <el-button type="primary" :loading="saving" @click="create">创建</el-button>
            </template>
        </el-dialog>

        <el-dialog v-model="chDlg" title="权限范围" width="700px">
            <div class="filter-row" style="margin-bottom:10px;align-items:center">
                <span style="font-size:13px;color:#606266">客户端</span>
                <strong>{{ chForm.clientName }}</strong>
            </div>
            <el-alert v-if="chWasUnlimited" type="warning" :closable="false" show-icon
                title="该客户端当前是「不限」状态。保存后会按下面的选择生效。"
                style="margin-bottom:10px" />
            <div class="filter-row" style="margin-bottom:10px;align-items:center">
                <span style="font-size:13px;color:#606266;width:124px">管理权限（超管）</span>
                <el-switch v-model="chScope.admin" :disabled="chIsConsole" />
            </div>
            <div class="filter-row" style="margin-bottom:10px;align-items:center">
                <span style="font-size:13px;color:#606266;width:124px">授权方式</span>
                <el-radio-group v-model="chScope.scopeMode" size="small" :disabled="chIsConsole"
                    @change="onScopeModeChange">
                    <el-radio-button value="ALL">不限</el-radio-button>
                    <el-radio-button value="SCOPE">指定通道</el-radio-button>
                </el-radio-group>
            </div>
            <ScopeTree v-model="chScope" :channels="channels" :readonly="chScope.scopeMode === 'ALL'" />
            <template #footer>
                <el-button @click="chDlg = false">取消</el-button>
                <el-button type="primary" :loading="saving" @click="saveChannels">保存</el-button>
            </template>
        </el-dialog>

        <el-dialog v-model="expireDlg" title="修改失效时间（续期）" width="440px">
            <el-form label-width="90px">
                <el-form-item label="客户端">
                    <span>{{ expireForm.clientName }}</span>
                </el-form-item>
                <el-form-item label="失效时间">
                    <el-date-picker v-model="expireForm.expireTime" type="datetime"
                        placeholder="留空表示永久有效" value-format="YYYY-MM-DD HH:mm:ss" style="width:100%" />
                </el-form-item>
            </el-form>
            <div class="form-tip">留空并保存 = 改为永久有效；设置过去的时间 = 立即失效（可用于做失效演示）</div>
            <template #footer>
                <el-button @click="expireDlg = false">取消</el-button>
                <el-button type="primary" :loading="saving" @click="saveExpire">保存</el-button>
            </template>
        </el-dialog>

        <el-dialog v-model="secretDlg" title="凭据已生成" width="540px">
            <div class="secret-warn">⚠️ appSecret 仅此一次可见，请立即复制保存，关闭后无法再次查看</div>
            <div style="margin:12px 0 6px;font-size:12px;color:#909399">appKey</div>
            <div class="secret-box">{{ newKey.appKey }}</div>
            <div style="margin:6px 0;font-size:12px;color:#909399">appSecret</div>
            <div class="secret-box">{{ newKey.appSecret }}</div>
            <div class="form-tip">调用示例：curl -H "api-key: {{ newKey.appKey }}" http://127.0.0.1:8088/api/v1/streams</div>
            <div class="form-tip" style="color:#e6a23c;margin-top:6px">⚠️ 该客户端当前<b>没有任何授权</b>，请到列表里点【通道授权】分配设备或通道；
                在此之前它查什么都是空的，操作通道会被拒（1001）。</div>
            <template #footer>
                <el-button type="primary" @click="copySecret">复制凭据</el-button>
                <el-button @click="secretDlg = false">我已保存</el-button>
            </template>
        </el-dialog>
    </div>`,
    setup() {
        const rows = ref([]), total = ref(0), page = ref(1), size = 10;
        const loading = ref(false), saving = ref(false), dlg = ref(false), secretDlg = ref(false);
        const form = reactive({ clientName: '', contact: '', remark: '', expireTime: null });
        const newKey = reactive({ appKey: '', appSecret: '' });
        const expireDlg = ref(false);
        const chDlg = ref(false);
        const channels = ref([]);
        const chForm = reactive({ id: null, clientName: '' });
        const emptyScope = () => ({ scopeMode: 'ALL', devices: [], channels: [], admin: false });
        const chScope = ref(emptyScope());
        /** 打开时该客户端是否是"不限"状态（保存后会变成按勾选生效，给用户一个提示） */
        const chWasUnlimited = ref(false);
        /** 内置控制台凭证：管理权限开关要锁住（后端也会拒绝改） */
        const chIsConsole = ref(false);
        const expireForm = reactive({ id: null, clientName: '', expireTime: null });
        const q = reactive({ keyword: '' });
        function search() { page.value = 1; load(); }
        function resetQuery() { q.keyword = ''; page.value = 1; load(); }

        /** 把 "yyyy-MM-dd HH:mm:ss" 转成时间戳 */
        function parseTime(s) {
            if (!s) return null;
            const t = new Date(String(s).replace(' ', 'T')).getTime();
            return isNaN(t) ? null : t;
        }
        function isExpired(row) {
            const t = parseTime(row.expireTime);
            return t != null && t < Date.now();
        }
        /** 客户端状态：已停用 > 已过期 > 有效 */
        function clientState(row) {
            if (row.enabled !== 1) return ['已停用', 'info'];
            if (isExpired(row)) return ['已过期', 'danger'];
            return ['有效', 'success'];
        }
        function openExpire(row) {
            expireForm.id = row.id;
            expireForm.clientName = row.clientName;
            expireForm.expireTime = row.expireTime || null;
            expireDlg.value = true;
        }
        async function saveExpire() {
            saving.value = true;
            try {
                await api('/api/v1/api-clients/' + expireForm.id + '/expire', {
                    method: 'POST',
                    body: JSON.stringify({ expireTime: expireForm.expireTime || null })
                });
                ElMessage.success(expireForm.expireTime ? '失效时间已更新' : '已改为永久有效');
                expireDlg.value = false;
                load();
            } catch (e) {
                ElMessage.error(e.message);
            } finally {
                saving.value = false;
            }
        }

        async function load() {
            loading.value = true;
            try {
                let url = '/api/v1/api-clients?page=' + page.value + '&size=' + size;
                if (q.keyword) url += '&keyword=' + encodeURIComponent(q.keyword);
                const d = await api(url);
                rows.value = d.list || [];
                total.value = d.total || 0;
            } catch (e) { ElMessage.error(e.message); }
            finally { loading.value = false; }
        }
        function openCreate() {
            form.clientName = ''; form.contact = ''; form.remark = ''; form.expireTime = null;
            dlg.value = true;
        }
        async function create() {
            if (!form.clientName) { ElMessage.warning('请填写客户端名称'); return; }
            saving.value = true;
            try {
                // 新建的客户端默认<b>无任何权限</b>（SCOPE + 空列表 = 全拒），
                // 由管理员在列表里点【通道授权】按设备/通道分配，符合"最小权限"原则
                const body = JSON.stringify({
                    clientName: form.clientName, contact: form.contact, remark: form.remark,
                    expireTime: form.expireTime,
                    scopeMode: 'SCOPE',
                    allowedDevices: [],
                    allowedChannels: []
                });
                const d = await api('/api/v1/api-clients', { method: 'POST', body });
                newKey.appKey = d.appKey;
                newKey.appSecret = d.appSecret;
                dlg.value = false;
                secretDlg.value = true;
                load();
            } catch (e) { ElMessage.error(e.message); }
            finally { saving.value = false; }
        }
        async function toggle(row) {
            try {
                const act = row.enabled === 1 ? 'disable' : 'enable';
                await api('/api/v1/api-clients/' + row.id + '/' + act, { method: 'POST' });
                ElMessage.success(act === 'enable' ? '已启用' : '已停用（该客户端的请求会立即被拒）');
                load();
            } catch (e) { ElMessage.error(e.message); }
        }
        async function resetKey(row) {
            try {
                await ElMessageBox.confirm('重置后旧密钥立即失效，确认继续？', '重置密钥', { type: 'warning' });
                const d = await api('/api/v1/api-clients/' + row.id + '/reset', { method: 'POST' });
                newKey.appKey = row.appKey;
                newKey.appSecret = d.appSecret;
                secretDlg.value = true;
            } catch (e) { if (e !== 'cancel') ElMessage.error(e.message || ''); }
        }
        async function remove(row) {
            try {
                await ElMessageBox.confirm('确认删除客户端「' + row.clientName + '」？', '提示', { type: 'warning' });
                await api('/api/v1/api-clients/' + row.id, { method: 'DELETE' });
                ElMessage.success('已删除');
                load();
            } catch (e) { if (e !== 'cancel') ElMessage.error(e.message || ''); }
        }
        /** 通道列表（树用，只加载一次） */
        async function loadChannels() {
            try {
                const d = await api('/api/v1/channels?page=1&size=500');
                channels.value = d.list || [];
            } catch (e) { /* 通道页会自行提示 */ }
        }
        /** 把 CSV 拆成 id 数组 */
        function splitIds(csv) {
            return csv ? String(csv).split(',').map(s => s.trim()).filter(Boolean).map(Number) : [];
        }
        /**
         * 这个客户端实际能访问多少个通道。
         *
         * <p>把"设备级授权"展开成其名下的通道，再和"单通道授权"合并去重 ——
         * 所以它算的是<b>通道数</b>，不再单独提设备（设备只是授权手段）。
         */
        function grantedChannelIds(row) {
            const ids = new Set(splitIds(row.allowedChannels));
            splitIds(row.allowedDevices).forEach(devId => {
                channels.value.forEach(c => { if (c.deviceId === devId) ids.add(c.id); });
            });
            return ids;
        }
        const totalChannelCount = computed(() => channels.value.length);

        /** 通道范围标签：[文案, tag 类型] —— 不限 / 无授权 / N 个通道 */
        function scopeTag(row) {
            if (row.scopeMode !== 'SCOPE') return ['不限', 'success'];
            const n = grantedChannelIds(row).size;
            if (n === 0) return ['无授权', 'danger'];
            return [n + ' 个通道', 'warning'];
        }
        /** 通道明细（悬浮提示）：只列通道名 */
        function scopeDetail(row) {
            if (row.scopeMode !== 'SCOPE') return '不限：可访问所有通道';
            const names = [];
            grantedChannelIds(row).forEach(id => {
                const c = channels.value.find(x => x.id === id);
                names.push(c ? c.channelName : ('通道#' + id + '（已删除）'));
            });
            return names.length ? names.join('、') : '无授权';
        }

        /** 切换授权方式：选"全部通道"时清掉勾选（保存时也不带列表，避免两套语义混在一起） */
        function onScopeModeChange(mode) {
            if (mode === 'ALL') {
                chScope.value.devices = [];
                chScope.value.channels = [];
            }
        }

        function openChannels(row) {
            chForm.id = row.id;
            chForm.clientName = row.clientName;
            chWasUnlimited.value = row.scopeMode !== 'SCOPE';
            chIsConsole.value = row.appKey === 'vwg_console';
            chScope.value = {
                scopeMode: 'SCOPE',
                devices: splitIds(row.allowedDevices),
                channels: splitIds(row.allowedChannels),
                admin: row.isAdmin === 1
            };
            chDlg.value = true;
            loadChannels();
        }
        async function saveChannels() {
            saving.value = true;
            try {
                await api('/api/v1/api-clients/' + chForm.id + '/channels', {
                    method: 'POST',
                    body: JSON.stringify({
                        scopeMode: chScope.value.scopeMode,
                        allowedDevices: chScope.value.scopeMode === 'SCOPE' ? chScope.value.devices : [],
                        allowedChannels: chScope.value.scopeMode === 'SCOPE' ? chScope.value.channels : [],
                        isAdmin: chScope.value.admin ? 1 : 0
                    })
                });
                ElMessage.success('权限范围已更新');
                chDlg.value = false;
                load();
            } catch (e) { ElMessage.error(e.message); }
            finally { saving.value = false; }
        }
        async function copySecret() {
            const text = 'appKey: ' + newKey.appKey + '\nappSecret: ' + newKey.appSecret;
            try {
                await navigator.clipboard.writeText(text);
                ElMessage.success('已复制到剪贴板');
            } catch (e) {
                ElMessage.warning('浏览器不允许自动复制，请手动选中复制');
            }
        }
        onMounted(load);
        onMounted(loadChannels);
        return { rows, total, page, size, loading, saving, dlg, secretDlg, form, newKey,
            expireDlg, expireForm, chDlg, chForm, channels, isExpired, clientState,
            openExpire, saveExpire, openChannels, saveChannels, scopeTag, scopeDetail, totalChannelCount,
            onScopeModeChange,
            chScope, chWasUnlimited, chIsConsole,
            q, search, resetQuery,
            load, openCreate, create, toggle, resetKey, remove, copySecret, fmtTime };
    }
};

/* ========================= 应用外壳 ========================= */
const ROUTES = {
    overview: Overview,
    devices: Devices,
    channels: Channels,
    live: LiveVideo,
    'ledger-events': LedgerEvents,
    'ledger-sessions': Sessions,
    'ledger-records': Records,
    clients: ApiClients,
    'api-doc': ApiDoc
};
const TITLES = {
    overview: '概览',
    devices: '视频资源 · 设备管理',
    channels: '视频资源 · 通道管理',
    live: '实时视频',
    'ledger-events': '业务台账 · 事件流水',
    'ledger-sessions': '业务台账 · 流会话',
    'ledger-records': '业务台账 · 录像',
    clients: 'API 客户端',
    'api-doc': '接口文档'
};

const App = {
    template: `
    <el-container class="layout">
        <el-aside width="210px" class="aside">
            <div class="brand">
                <span class="dot"></span>
                <div>
                    <div class="brand-title">视频数据网关</div>
                    <div class="brand-sub">Video Data Gateway</div>
                </div>
            </div>
            <el-menu :default-active="route" @select="go" class="menu" background-color="#1f2d3d"
                     text-color="#b6c2cf" active-text-color="#ffffff">
                <el-menu-item index="overview">📊 概览</el-menu-item>
                <el-sub-menu index="res">
                    <template #title>📹 视频资源</template>
                    <el-menu-item index="devices">设备管理</el-menu-item>
                    <el-menu-item index="channels">通道管理</el-menu-item>
                </el-sub-menu>
                <el-menu-item index="live">▶️ 实时视频</el-menu-item>
                <el-sub-menu index="ledger">
                    <template #title>📋 业务台账</template>
                    <el-menu-item index="ledger-events">事件流水</el-menu-item>
                    <el-menu-item index="ledger-sessions">流会话</el-menu-item>
                    <el-menu-item index="ledger-records">录像</el-menu-item>
                </el-sub-menu>
                <el-menu-item index="clients">🔑 API 客户端</el-menu-item>
                <el-menu-item index="api-doc">📄 接口文档</el-menu-item>
            </el-menu>
        </el-aside>

        <el-container>
            <el-header class="header">
                <div class="header-left">{{ title }}</div>
                <div class="header-right">
                    <el-tag size="small" :type="store.zlmOk ? 'success' : 'danger'">
                        {{ store.zlmOk ? '媒体服务器正常' : '媒体服务器不可用' }}
                    </el-tag>
                    <span class="apikey">{{ maskedKey }}</span>
                    <el-button size="small" @click="askKey">切换 Key</el-button>
                </div>
            </el-header>
            <el-main class="main">
                <component :is="view" :key="route"></component>
            </el-main>
        </el-container>
    </el-container>

    <el-dialog v-model="keyDlg" title="请输入 API Key" width="440px"
               :close-on-click-modal="false" :close-on-press-escape="false" :show-close="!!getKey()">
        <div style="font-size:13px;color:#606266;line-height:1.8;margin-bottom:12px">
            本平台所有接口需携带 <code>api-key</code> 请求头。<br>
            请输入管理端客户端的 appKey（演示环境默认：<code>vwg_console</code>）。
        </div>
        <el-input v-model="keyInput" placeholder="vwg_console" @keyup.enter="confirmKey" />
        <div v-if="keyError" style="color:#ef4444;font-size:12px;margin-top:8px">{{ keyError }}</div>
        <template #footer>
            <el-button type="primary" :loading="checking" @click="confirmKey">进入系统</el-button>
        </template>
    </el-dialog>`,
    setup() {
        const route = ref((location.hash || '#/overview').replace(/^#\//, ''));
        if (!ROUTES[route.value]) route.value = 'overview';

        const view = computed(() => ROUTES[route.value] || Overview);
        const title = computed(() => TITLES[route.value] || '');
        const maskedKey = computed(() => {
            const k = getKey();
            if (!k) return '未设置 Key';
            return k.length > 10 ? k.slice(0, 6) + '…' + k.slice(-4) : k;
        });

        const keyDlg = ref(false), keyInput = ref(''), keyError = ref(''), checking = ref(false);

        function go(idx) {
            route.value = idx;
            location.hash = '#/' + idx;
        }
        window.addEventListener('hashchange', () => {
            const r = (location.hash || '').replace(/^#\//, '');
            if (ROUTES[r]) route.value = r;
        });
        window.addEventListener('vgw-unauthorized', (evt) => {
            keyInput.value = '';
            keyError.value = (evt && evt.detail) ? evt.detail : 'API Key 缺失或已失效，请重新输入';
            keyDlg.value = true;
        });

        function askKey() {
            keyInput.value = getKey();
            keyError.value = '';
            keyDlg.value = true;
        }

        async function confirmKey() {
            const k = keyInput.value.trim();
            if (!k) { keyError.value = '请输入 API Key'; return; }
            checking.value = true;
            keyError.value = '';
            try {
                setKey(k);
                // 用受保护的接口验证 key 是否有效
                const d = await api('/api/v1/stats/overview');
                store.zlmOk = !!d.zlmAvailable;
                keyDlg.value = false;
                ElMessage.success('已进入系统');
            } catch (e) {
                setKey('');
                keyError.value = e.message;
            } finally {
                checking.value = false;
            }
        }

        onMounted(() => {
            if (!getKey()) keyDlg.value = true;
        });

        return { route, view, title, maskedKey, keyDlg, keyInput, keyError, checking,
            store, go, askKey, confirmKey, getKey };
    }
};

const app = createApp(App);
app.use(ElementPlus);
app.mount('#app');
