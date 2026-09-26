/*
 * FlowDesk 本机演示入口的脚本（FD-0023-A / FD-0023-B / FD-0023-B-R1）。
 *
 * 约束：
 *   - 所有请求都用**相对路径**（同源），不写死主机名与端口；
 *   - 页面加载时只请求只读的健康端点，不发写请求，也不请求文档向量化、知识检索或模型调用类接口
 *     （本文件里也不写出它们的路径），因此加载页面不产生任何模型费用；
 *   - 工单列表、详情、新建都在用户点击之后才发请求；新建是唯一的写请求；
 *   - 用户输入与服务端数据一律只用 textContent 渲染：不把字符串当 HTML 插入，也不使用拼接 HTML 的写法；
 *   - 分类与优先级的取值与后端枚举一致：TicketCategory / TicketPriority（不修改 API 契约）；
 *   - 成功响应必须是「合法 JSON + 页面必需字段齐全」才算成功：空响应、畸形 JSON、缺字段一律报
 *     「响应格式异常」，绝不把 NaN / undefined 渲染出来，也绝不误报创建成功；
 *   - 列表加载期间禁止重复导航（按钮禁用 + 在飞标记）；列表与详情都用自增令牌丢弃**过时响应**，
 *     保证回来的旧响应不能覆盖最新页面。
 */
(function () {
    'use strict';

    /** 与后端 TicketCategory 一致（顺序即下拉框顺序）。 */
    var CATEGORIES = ['ACCOUNT_ACCESS', 'NETWORK', 'HARDWARE', 'SOFTWARE', 'OTHER'];

    /** 与后端 TicketPriority 一致（顺序即下拉框顺序）。 */
    var PRIORITIES = ['P1', 'P2', 'P3', 'P4'];

    /** 列表每页条数（只作为查询参数；翻页依据服务端返回的 page / hasNext / hasPrevious）。 */
    var PAGE_SIZE = 10;

    var lastPage = null;
    var lastHasPrev = null;
    var lastHasNext = null;
    var listLoading = false;
    var listToken = 0;
    var detailToken = 0;
    var creating = false;

    /** 最近一次成功渲染的详情与它的强 ETag（写请求的 If-Match 只允许来自单条详情的响应头）。 */
    var currentTicket = null;
    var currentETag = null;

    /** 状态变更进行中：禁用重复提交。 */
    var acting = false;

    function element(id) {
        return document.getElementById(id);
    }

    function setText(id, text) {
        var target = element(id);
        if (target) {
            target.textContent = text === undefined || text === null ? '' : String(text);
        }
    }

    function setClass(id, className) {
        var target = element(id);
        if (target) {
            target.className = className;
        }
    }

    function show(id, visible) {
        var target = element(id);
        if (target) {
            target.hidden = !visible;
        }
    }

    function setDisabled(id, disabled) {
        var target = element(id);
        if (target) {
            target.disabled = disabled;
        }
    }

    function describeFailure(result) {
        var body = result && result.body ? result.body : null;
        var parts = ['HTTP ' + (result ? result.status : '?')];
        if (body && body.code) {
            parts.push('code=' + body.code);
        }
        if (body && body.detail) {
            parts.push('detail=' + body.detail);
        }
        else if (body && body.title) {
            parts.push('detail=' + body.title);
        }
        return parts.join(' · ');
    }

    /**
     * 读一次 JSON 接口。
     *
     * @param url     相对路径
     * @param options fetch 选项（写请求才需要传）
     * @returns {Promise<{status:number, body:object|null, transportError:string|null, formatProblem:string|null}>}
     */
    function requestJson(url, options) {
        return fetch(url, options)
            .then(function (response) {
                var eTag = response.headers.get('ETag');
                return response.text().then(function (text) {
                    var trimmed = text === null || text === undefined ? '' : text.trim();
                    if (trimmed === '') {
                        return {
                            status: response.status,
                            body: null,
                            eTag: eTag,
                            transportError: null,
                            formatProblem: response.ok ? '服务端返回了空响应' : null
                        };
                    }
                    var parsed;
                    try {
                        parsed = JSON.parse(trimmed);
                    }
                    catch (error) {
                        return {
                            status: response.status,
                            body: null,
                            eTag: eTag,
                            transportError: null,
                            formatProblem: response.ok ? '服务端返回的不是合法 JSON' : null
                        };
                    }
                    if (parsed === null || typeof parsed !== 'object') {
                        return {
                            status: response.status,
                            body: null,
                            eTag: eTag,
                            transportError: null,
                            formatProblem: response.ok ? '服务端返回的 JSON 不是对象' : null
                        };
                    }
                    return { status: response.status, body: parsed, eTag: eTag, transportError: null, formatProblem: null };
                });
            })
            .catch(function (error) {
                return { status: 0, body: null, eTag: null, transportError: error.message, formatProblem: null };
            });
    }

    function isFiniteNumber(value) {
        return typeof value === 'number' && isFinite(value);
    }

    /**
     * 校验成功响应里页面必需字段是否存在且类型正确。
     *
     * @param body     解析后的响应体
     * @param required 字段名数组
     * @returns {string|null} 有问题时返回「缺少字段 xxx」这类说明，否则返回 null
     */
    function missingField(body, required) {
        for (var index = 0; index < required.length; index++) {
            var name = required[index];
            if (body[name] === undefined || body[name] === null) {
                return '缺少字段 ' + name;
            }
        }
        return null;
    }

    // ---------- 服务状态（页面加载时唯一会发出的请求） ----------

    function loadHealth() {
        requestJson('/actuator/health', { headers: { 'Accept': 'application/json' } })
            .then(function (result) {
                if (result.transportError) {
                    setText('health-main', '请求失败：' + result.transportError);
                    setClass('health-main', 'failed');
                    return;
                }
                if (result.body && result.body.status) {
                    setText('health-main', 'HTTP ' + result.status + ' · ' + result.body.status);
                    setClass('health-main', result.status === 200 && result.body.status === 'UP' ? 'ok' : 'warn');
                    return;
                }
                setText('health-main', '响应格式异常：没有 status 字段');
                setClass('health-main', 'failed');
            });
    }

    // ---------- 工单列表（点击后；只读；加载期间禁止重复导航，过时响应丢弃） ----------

    function setListBusy(busy) {
        listLoading = busy;
        setDisabled('load-tickets', busy);
        setDisabled('prev-page', busy);
        setDisabled('next-page', busy);
    }

    /**
     * 按「最后一次有效分页结果」恢复加载/上一页/下一页：
     * 加载失败时上一页/下一页必须与最后一次有效结果一致；**没有有效结果时两者都禁用**。
     */
    function applyNavigation() {
        setDisabled('load-tickets', false);
        setDisabled('prev-page', lastHasPrev !== true);
        setDisabled('next-page', lastHasNext !== true);
    }

    function renderPageMeta(body) {
        if (body.totalPages === 0) {
            // 后端空列表契约：page=0、totalPages=0、totalElements=0、hasNext=hasPrevious=false
            setText('tickets-meta', '共 0 条');
        }
        else {
            setText('tickets-meta', '第 ' + (body.page + 1) + ' 页 / 共 ' + body.totalPages + ' 页，共 '
                + body.totalElements + ' 条（本页 ' + body.items.length + ' 条）');
        }
        lastPage = body.page;
        lastHasPrev = body.hasPrevious;
        lastHasNext = body.hasNext;
        applyNavigation();
    }

    function renderTicketRows(items) {
        var table = element('tickets-table');
        var rows = table ? table.querySelector('tbody') : null;
        if (!rows) {
            return;
        }
        rows.replaceChildren();
        items.forEach(function (item) {
            var row = document.createElement('tr');
            [item.id, item.title, item.status, item.priority, item.requesterId].forEach(function (value) {
                var cell = document.createElement('td');
                cell.textContent = value === undefined || value === null ? '' : String(value);
                row.appendChild(cell);
            });

            var actions = document.createElement('td');
            var button = document.createElement('button');
            button.type = 'button';
            button.textContent = '详情';
            button.setAttribute('data-ticket-detail', item.id);
            button.addEventListener('click', function () {
                openDetail(item.id);
            });
            actions.appendChild(button);
            row.appendChild(actions);
            rows.appendChild(row);
        });
    }

    function listPageProblem(body) {
        var missing = missingField(body, ['items', 'page', 'size', 'totalPages', 'totalElements',
            'hasNext', 'hasPrevious']);
        if (missing) {
            return missing;
        }
        if (!Array.isArray(body.items)) {
            return 'items 不是数组';
        }
        if (!isFiniteNumber(body.page) || !isFiniteNumber(body.totalPages)
                || !isFiniteNumber(body.totalElements) || !isFiniteNumber(body.size)) {
            return '分页字段不是有效数字';
        }
        if (typeof body.hasNext !== 'boolean' || typeof body.hasPrevious !== 'boolean') {
            return 'hasNext / hasPrevious 不是布尔值';
        }
        for (var index = 0; index < body.items.length; index++) {
            var item = body.items[index];
            if (!item || typeof item !== 'object') {
                return 'items 里有非对象元素';
            }
            var itemMissing = missingField(item, ['id', 'title', 'status', 'priority', 'requesterId']);
            if (itemMissing) {
                return 'items[' + index + '] ' + itemMissing;
            }
        }
        return null;
    }

    function loadTickets(page) {
        var target = typeof page === 'number' ? page : 0;
        if (listLoading) {
            return;
        }
        var token = listToken + 1;
        listToken = token;

        setListBusy(true);
        show('tickets-error', false);
        show('tickets-table', false);
        show('tickets-state', true);
        setText('tickets-state', '加载中…');
        setText('tickets-meta', '');

        requestJson('/api/v1/tickets?page=' + target + '&size=' + PAGE_SIZE,
            { headers: { 'Accept': 'application/json' } })
            .then(function (result) {
                if (token !== listToken) {
                    // 过时响应：已经有更新的列表请求在跑或已完成，直接丢弃，不覆盖最新页面
                    return;
                }
                setListBusy(false);
                // 无论成功失败，先把上一页/下一页恢复成「最后一次有效结果」的样子
                applyNavigation();
                show('tickets-state', false);

                if (result.transportError) {
                    show('tickets-error', true);
                    setText('tickets-error', '请求失败（服务不可达）：' + result.transportError);
                    return;
                }
                if (result.status === 400 || result.status === 404) {
                    show('tickets-error', true);
                    setText('tickets-error', '加载工单失败：' + describeFailure(result));
                    return;
                }
                if (result.status !== 200) {
                    show('tickets-error', true);
                    setText('tickets-error', '加载工单失败：' + describeFailure(result));
                    return;
                }
                if (result.formatProblem) {
                    show('tickets-error', true);
                    setText('tickets-error', '加载工单失败：响应格式异常（' + result.formatProblem + '）');
                    return;
                }
                var problem = listPageProblem(result.body);
                if (problem) {
                    show('tickets-error', true);
                    setText('tickets-error', '加载工单失败：响应格式异常（' + problem + '）');
                    return;
                }

                renderPageMeta(result.body);
                if (result.body.items.length === 0) {
                    show('tickets-state', true);
                    setText('tickets-state', result.body.totalElements === 0
                        ? '还没有任何工单（空列表）'
                        : '本页没有工单');
                    return;
                }
                renderTicketRows(result.body.items);
                show('tickets-table', true);
            });
    }

    // ---------- 工单详情（点击后；只读；后点的那条胜出，旧响应不能覆盖） ----------

    function detailProblem(body) {
        var missing = missingField(body, ['id', 'title', 'description', 'category', 'priority',
            'requesterId', 'status', 'version']);
        if (missing) {
            return missing;
        }
        if (!isFiniteNumber(body.version)) {
            return 'version 不是有效数字';
        }
        return null;
    }

    function openDetail(ticketId) {
        var token = detailToken + 1;
        detailToken = token;

        show('detail-error', false);
        show('detail-body', false);
        show('detail-state', true);
        setText('detail-state', '加载中…');

        requestJson('/api/v1/tickets/' + encodeURIComponent(ticketId), { headers: { 'Accept': 'application/json' } })
            .then(function (result) {
                if (token !== detailToken) {
                    // 过时响应：用户已经点了别的工单，丢弃它
                    return;
                }
                show('detail-state', false);

                if (result.transportError) {
                    show('detail-error', true);
                    setText('detail-error', '请求失败（服务不可达）：' + result.transportError);
                    return;
                }
                if (result.status === 404) {
                    show('detail-error', true);
                    setText('detail-error', '未找到该工单：' + describeFailure(result));
                    return;
                }
                if (result.status !== 200) {
                    show('detail-error', true);
                    setText('detail-error', '加载详情失败：' + describeFailure(result));
                    return;
                }
                if (result.formatProblem) {
                    show('detail-error', true);
                    setText('detail-error', '加载详情失败：响应格式异常（' + result.formatProblem + '）');
                    return;
                }
                var problem = detailProblem(result.body);
                if (problem) {
                    show('detail-error', true);
                    setText('detail-error', '加载详情失败：响应格式异常（' + problem + '）');
                    return;
                }
                currentTicket = result.body;
                currentETag = result.eTag;
                renderDetail(result.body, result.eTag);
            });
    }

    function appendRow(box, label, value) {
        var row = document.createElement('div');
        row.className = 'detail-row';
        var name = document.createElement('span');
        name.className = 'field-name';
        name.textContent = label;
        var text = document.createElement('span');
        text.className = 'field-value';
        text.textContent = value === undefined || value === null || value === '' ? '—' : String(value);
        row.appendChild(name);
        row.appendChild(text);
        box.appendChild(row);
    }

    function renderDetail(ticket, eTag) {
        var box = element('detail-body');
        if (!box) {
            return;
        }
        box.replaceChildren();
        appendRow(box, '编号', ticket.id);
        appendRow(box, '标题', ticket.title);
        appendRow(box, '描述', ticket.description);
        appendRow(box, '分类', ticket.category);
        appendRow(box, '优先级', ticket.priority);
        appendRow(box, '提交人', ticket.requesterId);
        appendRow(box, '处理人', ticket.assigneeId);
        appendRow(box, '状态', ticket.status);
        appendRow(box, '解决说明', ticket.resolution);
        appendRow(box, '创建时间', ticket.createdAt);
        appendRow(box, '更新时间', ticket.updatedAt);
        appendRow(box, '解决时间', ticket.resolvedAt);
        appendRow(box, '关闭时间', ticket.closedAt);
        appendRow(box, '版本', ticket.version);
        appendRow(box, 'ETag', isStrongETag(eTag) ? eTag : (eTag === null || eTag === undefined ? '（响应未带 ETag）' : eTag));
        show('detail-body', true);
        renderActions(ticket);
        var section = element('detail');
        if (section && section.scrollIntoView) {
            section.scrollIntoView({ block: 'start' });
        }
    }

    // ---------- 状态变更（沿用后端已有端点；版本只能来自单条详情的强 ETag） ----------

    var ACTION_LABELS = {
        assign: '分配', reassign: '重新分配', start: '开始处理', resolve: '解决', close: '关闭'
    };

    var ACTION_PATHS = {
        assign: 'assign', reassign: 'reassign', start: 'start', resolve: 'resolve', close: 'close'
    };

    /** 强 ETag 形如 {@code "4"}：必须用双引号包裹，且不能是 {@code W/} 弱校验。 */
    function isStrongETag(value) {
        return typeof value === 'string' && /^"[^"]*"$/.test(value);
    }

    /**
     * 按工单的**真实状态**给出可用操作：
     * NEW → 分配；ASSIGNED → 重新分配 / 开始；IN_PROGRESS → 重新分配 / 解决；RESOLVED → 关闭；CLOSED → 无操作。
     *
     * @param status 工单状态
     * @returns {string[]} 可用操作
     */
    function availableActions(status) {
        if (status === 'NEW') {
            return ['assign'];
        }
        if (status === 'ASSIGNED') {
            return ['reassign', 'start'];
        }
        if (status === 'IN_PROGRESS') {
            return ['reassign', 'resolve'];
        }
        if (status === 'RESOLVED') {
            return ['close'];
        }
        return [];
    }

    function setActionsBusy(busy) {
        acting = busy;
        var buttons = document.querySelectorAll('#detail-action-buttons button, #detail-action-confirm button');
        for (var index = 0; index < buttons.length; index++) {
            buttons[index].disabled = busy;
        }
    }

    function actionFailure(result) {
        var parts = [];
        if (result.body && result.body.code) {
            parts.push('code=' + result.body.code);
        }
        if (result.body && result.body.detail) {
            parts.push('detail=' + result.body.detail);
        }
        return parts.length === 0 ? '响应没有 code/detail' : parts.join(' · ');
    }

    function showActionError(message) {
        show('detail-action-error', true);
        setText('detail-action-error', message);
    }

    function showActionIndeterminate(detail) {
        show('detail-action-error', true);
        setText('detail-action-error',
            '操作结果待确认（' + detail + '）。请先刷新详情确认最新状态，勿直接重试。');
    }

    function showRefreshHint(ticketId) {
        var box = document.getElementById('detail-action-error');
        if (!box || !ticketId) {
            return;
        }
        var refresh = document.createElement('button');
        refresh.type = 'button';
        refresh.id = 'refresh-detail';
        refresh.textContent = '刷新详情';
        refresh.addEventListener('click', function () {
            openDetail(ticketId);
        });
        box.appendChild(document.createTextNode(' '));
        box.appendChild(refresh);
    }

    function renderActions(ticket) {
        var buttons = element('detail-action-buttons');
        var fields = element('detail-action-fields');
        if (!buttons || !fields) {
            return;
        }
        show('detail-action-error', false);
        show('detail-action-result', false);
        show('detail-action-confirm', false);
        buttons.replaceChildren();
        fields.replaceChildren();
        show('detail-actions', true);

        var actions = availableActions(ticket.status);
        if (actions.length === 0) {
            var note = document.createElement('p');
            note.className = 'hint';
            note.textContent = '当前状态（' + ticket.status + '）没有可用的状态变更操作。';
            fields.appendChild(note);
            return;
        }

        if (actions.indexOf('assign') >= 0 || actions.indexOf('reassign') >= 0) {
            var assigneeRow = document.createElement('div');
            assigneeRow.className = 'field';
            var assigneeLabel = document.createElement('label');
            assigneeLabel.setAttribute('for', 'action-assignee');
            assigneeLabel.textContent = '处理人';
            var assigneeInput = document.createElement('input');
            assigneeInput.id = 'action-assignee';
            assigneeInput.type = 'text';
            assigneeInput.maxLength = 64;
            assigneeInput.value = ticket.assigneeId === null || ticket.assigneeId === undefined
                ? '' : String(ticket.assigneeId);
            assigneeRow.appendChild(assigneeLabel);
            assigneeRow.appendChild(assigneeInput);
            fields.appendChild(assigneeRow);
        }
        if (actions.indexOf('resolve') >= 0) {
            var resolutionRow = document.createElement('div');
            resolutionRow.className = 'field';
            var resolutionLabel = document.createElement('label');
            resolutionLabel.setAttribute('for', 'action-resolution');
            resolutionLabel.textContent = '解决说明';
            var resolutionInput = document.createElement('textarea');
            resolutionInput.id = 'action-resolution';
            resolutionInput.rows = 2;
            resolutionInput.maxLength = 2000;
            resolutionRow.appendChild(resolutionLabel);
            resolutionRow.appendChild(resolutionInput);
            fields.appendChild(resolutionRow);
        }

        actions.forEach(function (action) {
            var button = document.createElement('button');
            button.type = 'button';
            button.textContent = ACTION_LABELS[action];
            button.setAttribute('data-action', action);
            button.addEventListener('click', function () {
                requestAction(action);
            });
            buttons.appendChild(button);
        });
    }

    function requestAction(action) {
        if (acting) {
            return;
        }
        if (action !== 'close') {
            performAction(action);
            return;
        }
        // 关闭是不可逆的：先让用户确认
        var confirmBox = element('detail-action-confirm');
        if (!confirmBox) {
            return;
        }
        confirmBox.replaceChildren();
        var text = document.createElement('span');
        text.className = 'field-value';
        text.textContent = '关闭后不能再做状态变更，确认关闭这条工单？';
        confirmBox.appendChild(text);
        var yes = document.createElement('button');
        yes.type = 'button';
        yes.id = 'confirm-close';
        yes.textContent = '确认关闭';
        yes.addEventListener('click', function () {
            show('detail-action-confirm', false);
            performAction('close');
        });
        confirmBox.appendChild(yes);
        var no = document.createElement('button');
        no.type = 'button';
        no.id = 'cancel-close';
        no.textContent = '取消';
        no.addEventListener('click', function () {
            show('detail-action-confirm', false);
        });
        confirmBox.appendChild(no);
        show('detail-action-confirm', true);
    }

    function performAction(action) {
        if (acting || !currentTicket) {
            return;
        }
        var ticketId = currentTicket.id;
        var payload = null;
        if (action === 'assign' || action === 'reassign') {
            var assignee = element('action-assignee');
            payload = { assigneeId: assignee ? assignee.value : '' };
        }
        else if (action === 'resolve') {
            var resolution = element('action-resolution');
            payload = { resolution: resolution ? resolution.value : '' };
        }

        setActionsBusy(true);
        show('detail-action-error', false);
        show('detail-action-result', false);
        show('detail-state', true);
        setText('detail-state', action === 'close' ? '正在关闭…' : '正在提交状态变更…');

        // 第一步：**只**从单条详情的响应头取强 ETag；写请求的 If-Match 原样使用它。
        // 列表接口不返回 ETag，版本也不在请求体里，绝不用列表里的 version 猜造请求头。
        requestJson('/api/v1/tickets/' + encodeURIComponent(ticketId), { headers: { 'Accept': 'application/json' } })
            .then(function (probe) {
                show('detail-state', false);
                if (probe.transportError) {
                    setActionsBusy(false);
                    showActionIndeterminate('取最新详情时网络中断（' + probe.transportError + '）');
                    return;
                }
                if (probe.status !== 200) {
                    setActionsBusy(false);
                    showActionError('操作已取消：取最新详情失败（HTTP ' + probe.status + '，'
                        + actionFailure(probe) + '）。请先刷新详情。');
                    showRefreshHint(ticketId);
                    return;
                }
                if (!isStrongETag(probe.eTag)) {
                    setActionsBusy(false);
                    showActionError('操作已取消：单条详情响应没有可用的强 ETag（收到：'
                        + (probe.eTag === null || probe.eTag === undefined ? '无 ETag 响应头' : probe.eTag)
                        + '）。写请求必须带真实版本，不能用猜造的请求头。');
                    return;
                }
                var requestETag = probe.eTag;

                if (probe.body && probe.body.status !== currentTicket.status) {
                    currentTicket = probe.body;
                    currentETag = requestETag;
                    renderDetail(probe.body, requestETag);
                    setActionsBusy(false);
                    showActionError('这条工单的状态已经变化（现在是 ' + probe.body.status
                        + '），本次操作已取消；请按新的可用操作重试。');
                    return;
                }

                var headers = { 'Accept': 'application/json', 'If-Match': requestETag };
                var options = { method: 'POST', headers: headers };
                if (payload) {
                    headers['Content-Type'] = 'application/json';
                    options.body = JSON.stringify(payload);
                }
                return requestJson('/api/v1/tickets/' + encodeURIComponent(ticketId)
                    + '/' + ACTION_PATHS[action], options)
                    .then(function (result) {
                        handleActionResult(action, requestETag, result);
                    });
            });
    }

    function handleActionResult(action, requestETag, result) {
        setActionsBusy(false);

        if (result.transportError) {
            showActionIndeterminate('请求中断（' + result.transportError + '）');
            return;
        }
        if (result.status === 412) {
            showActionError('版本冲突（HTTP 412 · ' + actionFailure(result)
                + '）：这条工单已被其他操作更新，本次操作没有生效。请刷新详情后重试。');
            showRefreshHint(currentTicket ? currentTicket.id : null);
            return;
        }
        if (result.status === 428) {
            showActionError('缺少前置条件（HTTP 428 · ' + actionFailure(result)
                + '）：服务端要求先取得最新版本才能变更状态。请刷新详情。');
            showRefreshHint(currentTicket ? currentTicket.id : null);
            return;
        }
        if (result.status === 409) {
            showActionError('状态冲突（HTTP 409 · ' + actionFailure(result)
                + '）：当前状态不允许这个操作。请刷新详情后按可用操作重试。');
            showRefreshHint(currentTicket ? currentTicket.id : null);
            return;
        }
        if (result.status === 400) {
            showActionError('请求被拒绝（HTTP 400 · ' + actionFailure(result) + '）。');
            return;
        }
        if (result.status === 404) {
            showActionError('工单不存在（HTTP 404 · ' + actionFailure(result) + '）。');
            return;
        }
        if (result.status >= 400 && result.status < 500) {
            showActionError('操作被拒绝（HTTP ' + result.status + ' · ' + actionFailure(result) + '）。');
            return;
        }
        if (result.status >= 500) {
            showActionIndeterminate('服务端返回 HTTP ' + result.status + '，操作可能已经生效');
            return;
        }
        if (result.status !== 200) {
            showActionIndeterminate('收到意外状态码 HTTP ' + result.status + '，结果不确定');
            return;
        }
        if (result.formatProblem) {
            showActionIndeterminate('响应体异常（' + result.formatProblem + '）');
            return;
        }
        var problem = detailProblem(result.body);
        if (problem) {
            showActionIndeterminate('响应体异常（' + problem + '）');
            return;
        }

        currentTicket = result.body;
        currentETag = result.eTag;
        renderDetail(result.body, result.eTag);
        show('detail-action-result', true);
        setText('detail-action-result', '操作成功：' + ACTION_LABELS[action]
            + ' → 状态 ' + result.body.status
            + '，版本 ' + result.body.version
            + '，新 ETag ' + (isStrongETag(result.eTag) ? result.eTag : '（响应未带强 ETag）')
            + '（本次请求使用的 If-Match 为 ' + requestETag + '）');
    }

    // ---------- 新建工单（唯一的写请求；防重复提交；响应不合法不算成功） ----------

    function fillSelect(id, values) {
        var select = element(id);
        if (!select) {
            return;
        }
        select.replaceChildren();
        values.forEach(function (value) {
            var option = document.createElement('option');
            option.value = value;
            option.textContent = value;
            select.appendChild(option);
        });
    }

    function setCreateBusy(busy) {
        creating = busy;
        setDisabled('create-submit', busy);
    }

    /**
     * 创建结果不确定（网络异常、5xx、201 但响应体异常）：不说是失败，也不说是成功，
     * 明确提示先去列表确认、不要直接重试；表单内容保留，便于对照。
     *
     * @param detail 具体原因（已在调用处描述清楚）
     */
    function renderCreateIndeterminate(detail) {
        show('create-error', true);
        setText('create-error', '创建结果待确认（' + detail + '）。请先查列表确认是否已创建，勿直接重试。');
        var box = element('create-result');
        if (box) {
            box.replaceChildren();
            var actions = document.createElement('div');
            actions.className = 'toolbar';
            var toList = document.createElement('button');
            toList.type = 'button';
            toList.textContent = '去列表查看';
            toList.addEventListener('click', function () {
                loadTickets(0);
            });
            actions.appendChild(toList);
            box.appendChild(actions);
            show('create-result', true);
        }
    }

    function renderCreated(ticket) {
        var box = element('create-result');
        if (!box) {
            return;
        }
        box.replaceChildren();
        var line = document.createElement('div');
        line.className = 'detail-row';
        var text = document.createElement('span');
        text.className = 'field-value';
        text.textContent = '已创建：' + ticket.id + ' · ' + ticket.title + ' · ' + ticket.status;
        line.appendChild(text);
        box.appendChild(line);

        var actions = document.createElement('div');
        actions.className = 'toolbar';
        var open = document.createElement('button');
        open.type = 'button';
        open.textContent = '打开详情';
        open.addEventListener('click', function () {
            openDetail(ticket.id);
        });
        actions.appendChild(open);
        var toList = document.createElement('button');
        toList.type = 'button';
        toList.textContent = '在列表中查看';
        toList.addEventListener('click', function () {
            loadTickets(0);
        });
        actions.appendChild(toList);
        box.appendChild(actions);
        show('create-result', true);
    }

    function submitCreate(event) {
        if (event) {
            event.preventDefault();
        }
        if (creating) {
            return;
        }

        var payload = {
            title: element('create-title') ? element('create-title').value : '',
            description: element('create-description') ? element('create-description').value : '',
            category: element('create-category') ? element('create-category').value : '',
            priority: element('create-priority') ? element('create-priority').value : '',
            requesterId: element('create-requester') ? element('create-requester').value : ''
        };

        show('create-error', false);
        show('create-result', false);
        setText('create-state', '提交中…');
        setCreateBusy(true);

        requestJson('/api/v1/tickets', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', 'Accept': 'application/json' },
            body: JSON.stringify(payload)
        }).then(function (result) {
            setCreateBusy(false);
            setText('create-state', '');

            if (result.transportError) {
                // 没有拿到响应：请求可能已经到达服务端并被处理，不能断言失败
                renderCreateIndeterminate('请求没有拿到响应（' + result.transportError + '）');
                return;
            }
            if (result.status >= 400 && result.status < 500) {
                // 明确的 4xx：服务端已拒绝，按失败显示（含原有 400 文案）
                show('create-error', true);
                setText('create-error', '创建失败：' + describeFailure(result));
                return;
            }
            if (result.status !== 201) {
                // 5xx 等：服务端可能已经处理过，结果不确定
                renderCreateIndeterminate('服务端返回 ' + describeFailure(result));
                return;
            }
            if (result.formatProblem) {
                renderCreateIndeterminate('响应体异常（' + result.formatProblem + '）');
                return;
            }
            var problem = missingField(result.body, ['id', 'title', 'status']);
            if (problem) {
                renderCreateIndeterminate('响应体异常（' + problem + '）');
                return;
            }
            renderCreated(result.body);
            var form = element('create-form');
            if (form) {
                form.reset();
            }
        });
    }

    // ---------- 事件绑定：加载时只调健康检查，其它都由点击触发 ----------

    document.addEventListener('DOMContentLoaded', function () {
        fillSelect('create-category', CATEGORIES);
        fillSelect('create-priority', PRIORITIES);
        loadHealth();

        var load = element('load-tickets');
        if (load) {
            load.addEventListener('click', function () {
                loadTickets(0);
            });
        }
        var prev = element('prev-page');
        if (prev) {
            prev.addEventListener('click', function () {
                loadTickets(Math.max(0, (lastPage === null ? 0 : lastPage) - 1));
            });
        }
        var next = element('next-page');
        if (next) {
            next.addEventListener('click', function () {
                loadTickets((lastPage === null ? 0 : lastPage) + 1);
            });
        }
        var form = element('create-form');
        if (form) {
            form.addEventListener('submit', submitCreate);
        }
    });
})();
